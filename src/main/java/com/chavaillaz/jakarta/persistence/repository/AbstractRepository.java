package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.Pageable.sortedBy;
import static com.chavaillaz.jakarta.persistence.repository.Pageable.unpaged;
import static jakarta.transaction.Transactional.TxType.MANDATORY;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.EntityType;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.hibernate.query.restriction.Restriction;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;

/**
 * Base implementation of the {@link Repository} contract, relying on the JPA {@link EntityManager}.
 * <p>
 * The queries are delegated to two collaborators, reachable through {@link #ordering()} and {@link #queries()},
 * so that each concern stays isolated and testable on its own. Both are shared per entity type rather than built
 * per repository: they hold nothing but the entity type, everything of the repository asking — its entity
 * manager, its ordering hooks and its cursor codecs — being handed over at each call through {@link #context()}.
 * The entity manager stays a constructor parameter, so that the subclasses remain simple and dependency-injected
 * by their constructor, but nothing outliving a transaction ever captures it.
 * <p>
 * Only the methods a repository writes its business queries with are exposed here; the plumbing stays on the
 * collaborators and on the {@link Pageables} and {@link Criteria} helpers, so that overriding cannot break
 * their invariants. The {@code restriction} accepted throughout this class is a Hibernate
 * {@link Restriction}, checked at compile time against the static metamodel, {@link Restriction#unrestricted()}
 * matching every entity; the {@code criteria}, built with the plain criteria API, is what a restriction cannot
 * express, such as a correlated subquery.
 *
 * @param <E> The type of the managed entity
 * @param <I> The type of the entity identifier
 */
@Transactional(MANDATORY)
public abstract class AbstractRepository<E extends Identifiable<I>, I> implements Repository<E, I> {

    /**
     * Number of identifiers looked up by a single {@code IN} predicate, staying under the limit of the strictest
     * databases, Oracle rejecting a list of more than a thousand elements.
     */
    protected static final int DEFAULT_ID_BATCH_SIZE = 1_000;

    /**
     * Number of entities saved between two flushes of the persistence context, matching the default JDBC batch
     * size a persistence unit is usually configured with.
     */
    protected static final int DEFAULT_SAVE_BATCH_SIZE = 50;

    /**
     * The entity manager the repository operates on.
     */
    protected final EntityManager entityManager;

    /**
     * The type of the managed entity, resolved from the type parameters of the subclass.
     */
    protected final Class<E> entityType;

    /**
     * @see #context()
     */
    private final RepositoryContext<E> context = new RepositoryContext<>() {

        @Override
        public EntityManager entityManager() {
            return entityManager;
        }

        @Override
        public List<Order> defaultOrders(CriteriaBuilder criteriaBuilder, Root<E> root) {
            return getDefaultOrders(criteriaBuilder, root);
        }

        @Override
        public Map<String, String> searchableProperties() {
            return AbstractRepository.this.searchableProperties();
        }

        @Override
        public CursorCodec cursorCodec() {
            return AbstractRepository.this.cursorCodec();
        }

        @Override
        public CursorKeyCodec cursorKeyCodec() {
            return AbstractRepository.this.cursorKeyCodec();
        }

    };

    /**
     * Creates a repository.
     *
     * @param entityManager The entity manager the repository operates on
     * @param entityType    The type of the managed entity
     */
    protected AbstractRepository(EntityManager entityManager, Class<E> entityType) {
        this.entityManager = entityManager;
        this.entityType = entityType;
    }

    /**
     * Gets what the query collaborators need from this repository: its entity manager, its ordering hooks and its
     * cursor codecs.
     * <p>
     * The hooks are only ever invoked through it, and never before the subclass is fully constructed, since
     * nothing calls them until a query is run.
     *
     * @return The context of this repository, handed over at each call to the collaborators
     */
    protected RepositoryContext<E> context() {
        return context;
    }

    /**
     * Gets the ordering rules of the managed entity, resolving the sortable properties and building the query
     * ordering.
     * <p>
     * The instance is shared by every repository over that entity rather than built per repository: it holds
     * nothing but the entity type, whatever is specific to a repository travelling with the {@link #context()} of
     * each call. There is therefore nothing to initialise lazily, and no entity manager, which is bound to a
     * transaction, is captured by anything outliving it.
     *
     * @return The ordering rules of the managed entity
     */
    protected EntityOrdering<E> ordering() {
        return EntityOrdering.of(entityType);
    }

    /**
     * Gets the query support of the managed entity, building the search, count and scroll queries from the
     * restrictions and criteria.
     * <p>
     * Shared per entity type for the same reason as {@link #ordering()}.
     *
     * @return The query support of the managed entity
     */
    protected EntityQueries<E> queries() {
        return EntityQueries.of(entityType);
    }

    @Override
    public PaginationResult<E> findAll(Pageable pageable) {
        return queries().search(context(), null, null, pageable);
    }

    @Override
    public CursorResult<E> findAll(Cursor cursor) {
        return queries().scroll(context(), null, null, cursor);
    }

    @Override
    public Optional<E> findById(@Nullable I id) {
        return Optional.ofNullable(id).map(identifier -> entityManager.find(entityType, identifier));
    }

    @Override
    public Optional<E> findById(@Nullable I id, LockModeType lockMode) {
        return Optional.ofNullable(id).map(identifier -> entityManager.find(entityType, identifier, lockMode));
    }

    @Override
    public boolean existsById(@Nullable I id) {
        if (id == null) {
            return false;
        }

        Optional<String> idAttribute = singleIdAttributeName();
        if (idAttribute.isEmpty()) {
            // A composite identifier declared with an identifier class cannot be expressed as a single equality
            // predicate; falling back to a lookup still avoids the caller having to unwrap an Optional itself
            return entityManager.find(entityType, id) != null;
        }

        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Integer> query = criteriaBuilder.createQuery(Integer.class);
        Root<E> root = query.from(entityType);
        query.select(criteriaBuilder.literal(1)).where(criteriaBuilder.equal(root.get(idAttribute.get()), id));

        // A plain list is used rather than getSingleResult(), which would throw when no row matches
        return !entityManager.createQuery(query).setMaxResults(1).getResultList().isEmpty();
    }

    @Override
    public List<E> findAllById(Collection<I> ids) {
        List<I> distinctIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return List.of();
        }

        Optional<String> idAttribute = singleIdAttributeName();
        if (idAttribute.isEmpty()) {
            // A composite identifier declared with an identifier class cannot be expressed as a single IN predicate
            return distinctIds.stream().map(id -> entityManager.find(entityType, id)).filter(Objects::nonNull).toList();
        }

        // The identifiers are looked up by chunks, several databases rejecting an IN list beyond a few thousand
        // elements (a thousand on Oracle) and every one of them degrading long before that. The hook is read once
        // and validated: a non-positive size would never advance the loop, which would hang instead of failing
        int batchSize = requirePositive(idBatchSize(), "idBatchSize");

        List<E> entities = new ArrayList<>(distinctIds.size());
        for (int start = 0; start < distinctIds.size(); start += batchSize) {
            entities.addAll(findAllByIdChunk(distinctIds.subList(start, Math.min(start + batchSize, distinctIds.size())), idAttribute.get()));
        }
        return List.copyOf(entities);
    }

    private List<E> findAllByIdChunk(List<I> ids, String idAttribute) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<E> query = criteriaBuilder.createQuery(entityType);
        Root<E> root = query.from(entityType);
        query.select(root).where(root.get(idAttribute).in(ids));

        return entityManager.createQuery(query).getResultList();
    }

    /**
     * Gets the number of identifiers looked up by a single {@code IN} predicate, {@value #DEFAULT_ID_BATCH_SIZE}
     * by default.
     * <p>
     * Override to match the limit of the underlying database, which rejects a longer list of parameters, or to
     * lower it so that the query plans stay cacheable.
     *
     * @return The maximum number of identifiers per query, which must be strictly positive, {@link #findAllById(Collection)} rejecting anything else
     */
    protected int idBatchSize() {
        return DEFAULT_ID_BATCH_SIZE;
    }

    /**
     * Gets the name of the single identifier attribute of the managed entity, as reported by the metamodel.
     *
     * @return The attribute name, or {@link Optional#empty()} for a composite identifier declared with an
     * identifier class, spread over several attributes with no single path to compare as a whole
     */
    private Optional<String> singleIdAttributeName() {
        EntityType<E> entityMetamodel = entityManager.getMetamodel().entity(entityType);
        if (!entityMetamodel.hasSingleIdAttribute()) {
            return Optional.empty();
        }
        return Optional.of(entityMetamodel.getId(entityMetamodel.getIdType().getJavaType()).getName());
    }

    /**
     * Searches for the entities matching the given restriction, which is built from the static metamodel and is
     * therefore checked at compile time.
     * <p>
     * The total number of matching items is derived from the very same restriction, so that the count can never
     * drift away from the results, as it does when both are written as two separate queries.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param pageable    The requested page and ordering, {@link Pageable#UNPAGED} to return all the matching
     *                    entities with the default ordering of the repository
     * @return The entities of the requested page with the total number of matching entities
     * @throws IllegalArgumentException if the requested ordering refers to an unknown property or to a collection
     */
    protected PaginationResult<E> search(@Nullable Restriction<? super E> restriction, Pageable pageable) {
        return queries().search(context(), restriction, null, pageable);
    }

    /**
     * Searches for the entities matching the given restriction and additional criteria.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param pageable    The requested page and ordering, {@link Pageable#UNPAGED} to return all the matching
     *                    entities with the default ordering of the repository
     * @return The entities of the requested page with the total number of matching entities
     * @see #search(Restriction, Pageable)
     */
    protected PaginationResult<E> search(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Pageable pageable) {
        return queries().search(context(), restriction, criteria, pageable);
    }

    /**
     * Searches for the entities matching the given criteria only, for the queries a restriction cannot express.
     *
     * @param criteria The criteria to apply, or {@code null} to match all the entities
     * @param pageable The requested page and ordering, {@link Pageable#UNPAGED} to return all the matching
     *                 entities with the default ordering of the repository
     * @return The entities of the requested page with the total number of matching entities
     * @see #search(Restriction, Criteria, Pageable)
     */
    protected PaginationResult<E> search(@Nullable Criteria<E> criteria, Pageable pageable) {
        return queries().search(context(), null, criteria, pageable);
    }

    /**
     * Searches for all the entities matching the given restriction, following the default ordering of the
     * repository, with no pagination.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @return The matching entities
     * @see #search(Restriction, Pageable)
     */
    protected List<E> search(@Nullable Restriction<? super E> restriction) {
        return queries().search(context(), restriction, null, unpaged()).items();
    }

    /**
     * Searches for all the entities matching the given restriction, following the requested ordering, with no
     * pagination.
     * <p>
     * The pagination is not the only reason to order a query, so the ordering stays usable on its own, without
     * having to build a {@link Pageable}.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param sort        The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The matching entities
     * @throws IllegalArgumentException if the ordering refers to an unknown property or to a collection
     */
    protected List<E> search(@Nullable Restriction<? super E> restriction, Sort sort) {
        return queries().search(context(), restriction, null, sortedBy(sort)).items();
    }

    /**
     * Searches for the entities matching the given criteria only, following the requested ordering.
     *
     * @param criteria The criteria to apply, or {@code null} to match all the entities
     * @param sort     The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The matching entities
     * @see #search(Restriction, Sort)
     * @see #search(Criteria, Pageable)
     */
    protected List<E> search(@Nullable Criteria<E> criteria, Sort sort) {
        return queries().search(context(), null, criteria, sortedBy(sort)).items();
    }

    /**
     * Searches for all the entities matching the given restriction and additional criteria, following the default
     * ordering of the repository, with no pagination.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @return The matching entities
     * @see #search(Restriction, Criteria, Pageable)
     */
    protected List<E> search(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        return queries().search(context(), restriction, criteria, unpaged()).items();
    }

    /**
     * Searches for the entities matching the given criteria only, following the default ordering of the repository,
     * with no pagination.
     *
     * @param criteria The criteria to apply, or {@code null} to match all the entities
     * @return The matching entities
     * @see #search(Criteria, Pageable)
     */
    protected List<E> search(@Nullable Criteria<E> criteria) {
        return queries().search(context(), null, criteria, unpaged()).items();
    }

    /**
     * Searches for the entities of a related type matching the given restriction, for the repositories exposing
     * the entities gravitating around the managed one, such as the children of an association.
     *
     * @param <R>         The type of the related entity
     * @param relatedType The type of the related entity
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @return The matching entities
     * @see EntityQueries#search(RepositoryContext, Class, Restriction)
     */
    protected <R> List<R> search(Class<R> relatedType, @Nullable Restriction<? super R> restriction) {
        return queries().search(context(), relatedType, restriction);
    }

    /**
     * Scrolls through the entities matching the given restriction only.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     * @param cursor      The requested position, size and ordering
     * @return The corresponding page with the tokens of the surrounding ones, never {@code null}
     * @see #scroll(Restriction, Criteria, Cursor)
     */
    protected CursorResult<E> scroll(@Nullable Restriction<? super E> restriction, Cursor cursor) {
        return queries().scroll(context(), restriction, null, cursor);
    }

    /**
     * Scrolls through the entities matching the given criteria only, for the lookups needing no reusable scope.
     * <p>
     * Prefer a {@link Restriction} as soon as the same condition is needed twice, so that it stays shared with the
     * offset queries.
     *
     * @param criteria The criteria to apply, {@code null} to match all the entities
     * @param cursor   The requested position, size and ordering
     * @return The corresponding page with the tokens of the surrounding ones, never {@code null}
     * @see #scroll(Restriction, Criteria, Cursor)
     */
    protected CursorResult<E> scroll(@Nullable Criteria<E> criteria, Cursor cursor) {
        return queries().scroll(context(), null, criteria, cursor);
    }

    /**
     * Scrolls through the entities matching the given restriction and additional criteria, seeking to the
     * requested position instead of skipping the preceding rows.
     * <p>
     * The restriction carries the reusable scope of the repository, the criteria what is specific to a single
     * lookup. Both are combined with the seek predicate of the cursor, so a page can never escape the scope it was
     * issued within. A restriction or a criteria joining a collection is automatically made distinct, so that a
     * duplicated boundary row does not silently shorten the page.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param cursor      The requested position, size and ordering
     * @return The corresponding page with the tokens of the surrounding ones, never {@code null}
     * @throws IllegalArgumentException if the ordering is not usable as a cursor key, if an ordering key of the
     *                                  boundary row is {@code null}, or if the cursor is malformed or was issued
     *                                  for another ordering
     */
    protected CursorResult<E> scroll(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Cursor cursor) {
        return queries().scroll(context(), restriction, criteria, cursor);
    }

    /**
     * Lazily walks the entities matching the given restriction, fetching a page at a time through the cursor
     * pagination instead of loading the whole result set at once.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param sort        The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @param pageSize    The number of items fetched per underlying page, capped to {@link Cursor#MAX_SIZE}
     * @return The lazy stream of every matching entity, in the requested ordering
     * @see #stream(Restriction, Criteria, Sort, int)
     */
    protected Stream<E> stream(@Nullable Restriction<? super E> restriction, Sort sort, int pageSize) {
        return stream(restriction, null, sort, pageSize);
    }

    /**
     * Lazily walks the entities matching the given restriction, applying {@link Cursor#DEFAULT_SIZE}.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param sort        The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The lazy stream of every matching entity, in the requested ordering
     * @see #stream(Restriction, Criteria, Sort, int)
     */
    protected Stream<E> stream(@Nullable Restriction<? super E> restriction, Sort sort) {
        return stream(restriction, null, sort, Cursor.DEFAULT_SIZE);
    }

    /**
     * Lazily walks the entities matching the given criteria only.
     *
     * @param criteria The criteria to apply, or {@code null} to match all the entities
     * @param sort     The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @param pageSize The number of items fetched per underlying page, capped to {@link Cursor#MAX_SIZE}
     * @return The lazy stream of every matching entity, in the requested ordering
     * @see #stream(Restriction, Criteria, Sort, int)
     */
    protected Stream<E> stream(@Nullable Criteria<E> criteria, Sort sort, int pageSize) {
        return stream(null, criteria, sort, pageSize);
    }

    /**
     * Lazily walks the entities matching the given restriction and additional criteria, fetching a page at a time
     * through the cursor pagination instead of loading the whole result set at once.
     * <p>
     * This is the filtered counterpart of {@link #streamAll(Sort, int)}, and it carries the very same
     * constraints: the pages are fetched on demand, so a short-circuiting operation only fetches what it needs,
     * but the stream must be consumed within the transaction it was obtained from, and every entity walked stays
     * managed by the persistence context until that transaction ends.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param sort        The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @param pageSize    The number of items fetched per underlying page, capped to {@link Cursor#MAX_SIZE}
     * @return The lazy stream of every matching entity, in the requested ordering
     * @throws IllegalArgumentException if the ordering is not usable as a cursor key
     */
    protected Stream<E> stream(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Sort sort, int pageSize) {
        return Cursors.stream(cursor -> queries().scroll(context(), restriction, criteria, cursor), sort, pageSize);
    }

    @Override
    public long count() {
        return queries().count(context(), null, null);
    }

    /**
     * Counts the entities matching the given restriction.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to count all
     *                    the entities
     * @return The total number of matching entities
     * @see #count(Restriction, Criteria)
     */
    protected long count(@Nullable Restriction<? super E> restriction) {
        return queries().count(context(), restriction, null);
    }

    /**
     * Counts the entities matching the given restriction and additional criteria.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to count all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @return The total number of matching entities
     */
    protected long count(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        return queries().count(context(), restriction, criteria);
    }

    /**
     * Counts the entities matching the given criteria only.
     *
     * @param criteria The criteria to apply, or {@code null} to count all the entities
     * @return The total number of matching entities
     * @see #count(Restriction, Criteria)
     */
    protected long count(@Nullable Criteria<E> criteria) {
        return queries().count(context(), null, criteria);
    }

    /**
     * Checks whether at least one entity matches the given restriction, without hydrating it.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @return {@code true} if at least one entity matches, {@code false} otherwise
     * @see #exists(Restriction, Criteria)
     */
    protected boolean exists(@Nullable Restriction<? super E> restriction) {
        return queries().exists(context(), restriction, null);
    }

    /**
     * Checks whether at least one entity matches the given restriction and additional criteria, without
     * hydrating it.
     * <p>
     * Prefer this over {@code count(...) > 0}: the database stops at the first matching row instead of counting
     * them all, and over {@code first(...).isPresent()}: no ordering is applied and no entity is loaded.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @return {@code true} if at least one entity matches, {@code false} otherwise
     */
    protected boolean exists(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        return queries().exists(context(), restriction, criteria);
    }

    /**
     * Checks whether at least one entity matches the given criteria only, without hydrating it.
     *
     * @param criteria The criteria to apply, or {@code null} to match all the entities
     * @return {@code true} if at least one entity matches, {@code false} otherwise
     * @see #exists(Restriction, Criteria)
     */
    protected boolean exists(@Nullable Criteria<E> criteria) {
        return queries().exists(context(), null, criteria);
    }

    /**
     * Deletes every entity matching the given restriction, in a single statement.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to delete
     *                    every entity
     * @return The number of deleted entities
     * @see #deleteAll(Restriction, Criteria)
     */
    protected int deleteAll(@Nullable Restriction<? super E> restriction) {
        return queries().delete(context(), restriction, null);
    }

    /**
     * Deletes every entity matching the given restriction and additional criteria, in a single statement.
     * <p>
     * This is a bulk deletion: it does not cascade, does not honour {@code orphanRemoval}, does not run the
     * {@code @PreRemove} callbacks and leaves the already loaded entities in the persistence context. Prefer
     * {@link #deleteAll(Collection)} when any of that matters; see {@link EntityQueries#delete(RepositoryContext, Restriction, Criteria)} for the details.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to delete
     *                    every entity
     * @param criteria    The additional criteria to apply, or {@code null}
     * @return The number of deleted entities
     */
    protected int deleteAll(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        return queries().delete(context(), restriction, criteria);
    }

    /**
     * Deletes every entity matching the given criteria only, in a single statement.
     *
     * @param criteria The criteria to apply, or {@code null} to delete every entity
     * @return The number of deleted entities
     * @see #deleteAll(Restriction, Criteria)
     */
    protected int deleteAll(@Nullable Criteria<E> criteria) {
        return queries().delete(context(), null, criteria);
    }

    /**
     * Gets the first entity matching the given restriction, following the default ordering of the repository.
     *
     * @param restriction The restriction to apply, or {@code null}
     * @return The corresponding entity, or {@link Optional#empty()} if none matches
     * @see #first(Restriction, Criteria, Sort)
     */
    protected Optional<E> first(@Nullable Restriction<? super E> restriction) {
        return queries().first(context(), restriction, null, Sort.NONE);
    }

    /**
     * Gets the first entity matching the given restriction and additional criteria, following the default ordering
     * of the repository.
     *
     * @param restriction The restriction to apply, or {@code null}
     * @param criteria    The additional criteria to apply, or {@code null}
     * @return The corresponding entity, or {@link Optional#empty()} if none matches
     * @see #first(Restriction, Criteria, Sort)
     */
    protected Optional<E> first(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        return queries().first(context(), restriction, criteria, Sort.NONE);
    }

    /**
     * Gets the first entity matching the given restriction and additional criteria, following the requested
     * ordering.
     * <p>
     * Only the first row is fetched, the ordering making it deterministic.
     *
     * @param restriction The restriction to apply, or {@code null}
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param sort        The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding entity, or {@link Optional#empty()} if none matches
     * @throws IllegalArgumentException if the ordering refers to an unknown property or to a collection
     */
    protected Optional<E> first(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Sort sort) {
        return queries().first(context(), restriction, criteria, sort);
    }

    /**
     * Gets the first entity matching the given restriction and additional criteria, following the requested
     * ordering, holding the requested lock on its row.
     * <p>
     * This is what claiming the next row to process is written with: the ordering makes the choice
     * deterministic, and the lock is taken as the row is read, so that a concurrent transaction ordering on the
     * very same criteria does not claim it as well.
     *
     * @param restriction The restriction to apply, or {@code null}
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param sort        The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @param lockMode    The lock to hold on the row until the end of the transaction,
     *                    {@link LockModeType#NONE} to take none
     * @return The corresponding entity, or {@link Optional#empty()} if none matches
     * @throws IllegalArgumentException if the ordering refers to an unknown property or to a collection
     */
    protected Optional<E> first(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Sort sort, LockModeType lockMode) {
        return queries().first(context(), restriction, criteria, sort, lockMode);
    }

    /**
     * Gets the first entity matching the given criteria only, following the default ordering of the repository.
     *
     * @param criteria The criteria to apply, or {@code null} to match all the entities
     * @return The corresponding entity, or {@link Optional#empty()} if none matches
     * @see #first(Criteria, Sort)
     */
    protected Optional<E> first(@Nullable Criteria<E> criteria) {
        return queries().first(context(), null, criteria, Sort.NONE);
    }

    /**
     * Gets the first entity matching the given criteria only, following the requested ordering.
     *
     * @param criteria The criteria to apply, or {@code null} to match all the entities
     * @param sort     The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding entity, or {@link Optional#empty()} if none matches
     * @see #first(Restriction, Criteria, Sort)
     */
    protected Optional<E> first(@Nullable Criteria<E> criteria, Sort sort) {
        return queries().first(context(), null, criteria, sort);
    }

    /**
     * Gets the properties the API consumers are allowed to sort and filter on, mapped to the path of the
     * corresponding entity attribute, none by default.
     * <p>
     * Override to restrict the reachable attributes and to decouple the public naming from the entity one, the
     * paths being preferably built from the static metamodel so that they are checked at compile time. When the
     * returned map is empty, every attribute of the entity is reachable, both for sorting and for an RSQL filter
     * expression.
     *
     * @return The searchable properties, an empty map to allow every attribute
     */
    protected Map<String, String> searchableProperties() {
        return Map.of();
    }

    /**
     * Gets the default ordering of the repository, applied when no ordering is requested, none by default.
     * <p>
     * Override to sort on business attributes; the identifier is appended automatically (see {@link Sort}), so it
     * does not need to be added here. The returned paths must belong to the root entity, a path on a joined
     * collection being incompatible with the distinct queries.
     * <p>
     * Unlike a requested ordering, which the repository resolves itself, a default ordering on a nested property
     * is built here with the raw criteria API: {@code root.get("roaster").get("name")} is an implicit inner join
     * and silently drops the entities having no roaster, whereas
     * {@code root.join("roaster", JoinType.LEFT).get("name")} keeps them, which is what the repository does for a
     * requested ordering.
     *
     * @param criteriaBuilder The builder to use to create the ordering
     * @param root            The root entity of the query
     * @return The default ordering, an empty list to only sort on the identifier
     */
    protected List<Order> getDefaultOrders(CriteriaBuilder criteriaBuilder, Root<E> root) {
        return List.of();
    }

    /**
     * Gets the codec of the cursor tokens, the default one by default.
     * <p>
     * Override to sign or encrypt the tokens when the ordering keys must not leak to the API consumers.
     *
     * @return The codec of the cursor tokens
     */
    protected CursorCodec cursorCodec() {
        return CursorCodec.DEFAULT;
    }

    /**
     * Gets the codec of the cursor key values, the default one by default.
     * <p>
     * Override to support an attribute type {@link CursorValues} does not, such as a legacy {@code java.sql.Date}
     * mapping or a custom identifier type, typically by delegating to {@link CursorKeyCodec#DEFAULT} for every
     * other type.
     *
     * @return The codec of the cursor key values
     */
    protected CursorKeyCodec cursorKeyCodec() {
        return CursorKeyCodec.DEFAULT;
    }

    @Override
    public void lock(E entity, LockModeType lockMode) {
        E managedEntity = reattach(entity);
        // Refresh to get last state of the entity if being already locked and changed
        entityManager.refresh(managedEntity, lockMode);
    }

    /**
     * Re-attaches a possibly detached entity, so that a caller needing a managed copy does not have to repeat the
     * same {@code contains}-or-{@code find} dance at every call site.
     * <p>
     * The lookup is a fresh load rather than a merge: re-attaching must not implicitly persist local field edits
     * carried by a stale detached copy, which a merge would silently do.
     *
     * @param entity The entity to re-attach
     * @return The managed copy of the entity
     * @throws IllegalArgumentException if the entity is transient, having no identifier yet
     * @throws NoSuchElementException   if the entity is detached and no entity with its identifier exists
     */
    protected E reattach(E entity) {
        if (entityManager.contains(entity)) {
            return entity;
        }

        I id = entity.getId();
        if (id == null) {
            // A transient entity was never persisted, so there is nothing to look up nor a managed copy to return;
            // letting it fall through to entityManager.find(entityType, null) would instead surface a confusing
            // provider level IllegalArgumentException about the identifier itself, not about the entity's state
            throw new IllegalArgumentException("Cannot reattach a transient entity in %s".formatted(getClass().getSimpleName()));
        }

        E managedEntity = entityManager.find(entityType, id);
        if (managedEntity == null) {
            throw new NoSuchElementException("No entity found with the identifier %s in %s".formatted(id, getClass().getSimpleName()));
        }
        return managedEntity;
    }

    @Override
    public @Nullable E getReference(@Nullable I id) {
        return getReference(entityType, id);
    }

    @Override
    public <T extends Identifiable<K>, K> @Nullable T getReference(Class<T> type, @Nullable K identifier) {
        return Optional.ofNullable(identifier)
                .map(id -> entityManager.getReference(type, id))
                .orElse(null);
    }

    @Override
    public E save(E entity) {
        if (entity.getId() == null) {
            entityManager.persist(entity);
        } else {
            entity = entityManager.merge(entity);
        }
        return entity;
    }

    /**
     * Saves the given entities, flushing and clearing the persistence context every {@link #saveBatchSize()}
     * entities, for the bulk loads a plain {@link #saveAll(Collection)} cannot hold in memory.
     * <p>
     * Saving a large collection through the persistence context grows it with every entity, and each flush then
     * dirty checks everything it already holds, so the cost grows with the square of the number of entities.
     * Flushing and clearing by batches keeps both bounded, which is also what lets the JDBC batching configured
     * by {@code hibernate.jdbc.batch_size} group the statements.
     * <p>
     * Clearing detaches <em>every</em> entity of the persistence context, not only the saved ones: any entity the
     * caller still holds becomes detached, and the generated identifiers are the only state guaranteed to be
     * populated on the given ones. Call this from a method that owns its transaction and holds nothing else,
     * and use {@link #saveAll(Collection)} otherwise.
     *
     * @param entities The entities to save
     * @return The number of saved entities
     */
    public int saveAllInBatches(Collection<E> entities) {
        // The hook is read once and validated: a size of zero would fail on the modulo itself, and a negative one
        // would never trigger a flush, which is exactly what this method exists to do
        int batchSize = requirePositive(saveBatchSize(), "saveBatchSize");

        int saved = 0;
        for (E entity : entities) {
            save(entity);
            if (++saved % batchSize == 0) {
                flushAndClear();
            }
        }
        // The trailing partial batch still has to be flushed, an empty collection leaving nothing to flush at all
        if (saved % batchSize != 0) {
            flushAndClear();
        }
        return saved;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Checks that the value a batch size hook returned can actually drive a loop, so that an override returning
     * zero or a negative size fails loudly on the spot instead of hanging or throwing further away.
     *
     * @param size The size the hook returned
     * @param hook The name of the hook, to name it in the error message
     * @return The very same size
     * @throws IllegalArgumentException if the size is not strictly positive
     */
    private static int requirePositive(int size, String hook) {
        if (size < 1) {
            throw new IllegalArgumentException("The batch size returned by %s() must be strictly positive, got %d".formatted(hook, size));
        }
        return size;
    }

    /**
     * Gets the number of entities saved between two flushes by {@link #saveAllInBatches(Collection)},
     * {@value #DEFAULT_SAVE_BATCH_SIZE} by default.
     * <p>
     * Override to match the {@code hibernate.jdbc.batch_size} of the persistence unit, the statements only being
     * grouped by the driver up to that size.
     *
     * @return The number of entities per batch, which must be strictly positive, {@link #saveAllInBatches(Collection)} rejecting anything else
     */
    protected int saveBatchSize() {
        return DEFAULT_SAVE_BATCH_SIZE;
    }

    @Override
    public void refresh(E entity) {
        entityManager.refresh(entity);
    }

    @Override
    public void delete(E entity) {
        entityManager.remove(reattach(entity));
    }

}

