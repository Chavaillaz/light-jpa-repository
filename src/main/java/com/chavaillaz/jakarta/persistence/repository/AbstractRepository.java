package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.Pageable.sortedBy;
import static com.chavaillaz.jakarta.persistence.repository.Pageable.unpaged;
import static jakarta.transaction.Transactional.TxType.MANDATORY;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceUnitUtil;
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
 * The queries are delegated to {@link #ordering()} and {@link #queries()}, which are shared per entity type and
 * receive everything of this repository — its entity manager, its ordering hooks and its cursor codecs — through
 * {@link #context()} at each call, so that nothing outliving a transaction captures its entity manager.
 * <p>
 * The {@code protected} methods are the ones a repository writes its business queries with. The
 * {@code restriction} they accept is a Hibernate {@link Restriction}, checked at compile time against the static
 * metamodel, {@link Restriction#unrestricted()} matching every entity; the {@code criteria}, built with the plain
 * criteria API, expresses what a restriction cannot, such as a correlated subquery.
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
     * The type of the managed entity, as the subclass declared it to the constructor.
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
     * cursor codecs, the hooks being only invoked through it once a query is run.
     *
     * @return The context of this repository, handed over at each call to the collaborators
     */
    protected RepositoryContext<E> context() {
        return context;
    }

    /**
     * Gets the ordering rules of the managed entity, resolving the sortable properties and building the query
     * ordering, shared by every repository over that entity.
     *
     * @return The ordering rules of the managed entity
     */
    protected EntityOrdering<E> ordering() {
        return EntityOrdering.of(entityType);
    }

    /**
     * Gets the query support of the managed entity, building the search, count and scroll queries from the
     * restrictions and criteria, shared by every repository over that entity.
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
            // An identifier class spreads the identifier over several attributes, which no single predicate compares
            return entityManager.find(entityType, id) != null;
        }

        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Integer> query = criteriaBuilder.createQuery(Integer.class);
        Root<E> root = query.from(entityType);
        query.select(criteriaBuilder.literal(1)).where(criteriaBuilder.equal(root.get(idAttribute.get()), id));

        // Listed since getSingleResult() throws when nothing matches
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
            // An identifier class spreads the identifier over several attributes, which no single predicate compares
            return distinctIds.stream().map(id -> entityManager.find(entityType, id)).filter(Objects::nonNull).toList();
        }

        // Looked up by chunks, the databases rejecting a long IN list; the hook is read once and validated, since a
        // non-positive size would never advance the loop
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
     * therefore checked at compile time, the total number of matching items being derived from that very
     * restriction.
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
     * @return The matching entities, ordered by their identifier
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
     * Scrolls through the entities matching the given criteria only.
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
     * Both are combined with the seek predicate of the cursor, so that a page never escapes the scope its token
     * was issued within.
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
     * This is the filtered counterpart of {@link #streamAll(Sort, int)}, with the same constraints: the stream must
     * be consumed within the transaction it was obtained from, and every entity walked stays managed until that
     * transaction ends.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param sort        The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @param pageSize    The number of items fetched per underlying page, capped to {@link Cursor#MAX_SIZE}
     * @return The lazy stream of every matching entity, in the requested ordering
     * @throws IllegalArgumentException if the ordering is not usable as a cursor key, raised when the stream is
     *                                  first consumed, nothing being queried until then
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
     * Unlike {@code count(...) > 0}, the database stops at the first matching row, and unlike
     * {@code first(...).isPresent()}, nothing is ordered nor loaded.
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
     * {@link #deleteAll(Collection)} when any of that matters, see
     * {@link EntityQueries#delete(RepositoryContext, Restriction, Criteria)}.
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
     * This is how the next row to process is claimed: the ordering makes the choice deterministic, and the lock
     * keeps a concurrent transaction from claiming the same row. Order such a claim on attributes of the entity
     * itself, since a nested property is navigated with a left join, and PostgreSQL, among others, refuses to lock
     * the nullable side of an outer join.
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
     * Override to restrict the reachable attributes and to decouple the public naming from the entity one,
     * preferably building the paths from the static metamodel. When the returned map is empty, every attribute of
     * the entity is reachable, for sorting as for a dynamic filter built on top, such as the RSQL support of the
     * sibling {@code rsql-jpa-repository} artifact.
     *
     * @return The searchable properties, an empty map to allow every attribute
     */
    protected Map<String, String> searchableProperties() {
        return Map.of();
    }

    /**
     * Gets the default ordering of the repository, applied when no ordering is requested, none by default.
     * <p>
     * Override to sort on business attributes, the identifier being appended automatically (see {@link Sort}). A
     * path must not reach a collection, which would duplicate the entity once per child.
     * <p>
     * Navigate an association with a left join, such as {@code root.join("roaster", JoinType.LEFT).get("name")},
     * as the repository does for a requested ordering: {@code root.get("roaster").get("name")} is an implicit inner
     * join, which drops the entities having no roaster from the results while the count, derived from the same
     * query without its {@code order by} clause, still includes them, and which the cursor pagination, resolving
     * this ordering into attribute paths it left joins, does not reproduce.
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
     * Override to support an attribute type {@link CursorValues} does not, such as a custom identifier type,
     * typically by delegating to {@link CursorKeyCodec#DEFAULT} for every other type.
     *
     * @return The codec of the cursor key values
     */
    protected CursorKeyCodec cursorKeyCodec() {
        return CursorKeyCodec.DEFAULT;
    }

    @Override
    public void lock(E entity, LockModeType lockMode) {
        E managedEntity = reattach(entity);
        // Refreshed, the managed copy possibly predating a change committed in between
        entityManager.refresh(managedEntity, lockMode);
    }

    /**
     * Re-attaches a possibly detached entity with a fresh lookup rather than a merge, which would silently persist
     * the local changes a stale detached copy carries.
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
            // Refused here, the provider only reporting a null identifier otherwise
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
     * Each flush dirty checks every entity the persistence context holds, so saving without clearing costs the
     * square of the number of entities; by batches, both the memory and that cost stay bounded, and
     * {@code hibernate.jdbc.batch_size} can group the statements.
     * <p>
     * Clearing detaches <em>every</em> entity of the persistence context, not only the saved ones, and the
     * generated identifiers are the only state guaranteed on the given entities: call this from a method owning its
     * transaction and holding nothing else. Only the number of saved entities is returned, a detached entity being
     * merged into a copy the caller never held, which is what holding a batch of would defeat.
     *
     * @param entities The entities to save
     * @return The number of saved entities
     */
    public int saveAllInBatches(Collection<E> entities) {
        // Read once and validated, a size of zero failing on the modulo and a negative one never flushing
        int batchSize = requirePositive(saveBatchSize(), "saveBatchSize");

        int saved = 0;
        for (E entity : entities) {
            save(entity);
            if (++saved % batchSize == 0) {
                flushAndClear();
            }
        }
        // The trailing partial batch, if any
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
     * Checks that the value a batch size hook returned can drive a loop, so that a broken override fails on the
     * spot instead of hanging or throwing further away.
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
        E managedEntity = reattach(entity);
        if (managedEntity != entity) {
            requireCurrentVersion(entity, managedEntity);
        }
        entityManager.remove(managedEntity);
    }

    /**
     * Checks that a detached entity still carries the version of the row it is about to delete, as a merge would.
     * <p>
     * The managed copy was looked up, so it holds the version the database has now, which is what the deletion is
     * checked against: without this, a copy read before another transaction committed a change would silently
     * delete that change. A reference that was never initialised carries no version to compare.
     *
     * @param detached The detached entity the caller asked to delete
     * @param managed  The managed copy re-attaching it
     * @throws OptimisticLockException if the entity is versioned and the detached copy carries another version
     */
    private void requireCurrentVersion(E detached, E managed) {
        PersistenceUnitUtil persistenceUnit = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        if (!persistenceUnit.isLoaded(detached)) {
            // Reading its version would initialise a proxy whose session is gone
            return;
        }

        Object version = persistenceUnit.getVersion(detached);
        if (version != null && !version.equals(persistenceUnit.getVersion(managed))) {
            throw new OptimisticLockException("Cannot delete the entity with the identifier %s in %s, modified since its detached copy was read"
                    .formatted(managed.getId(), getClass().getSimpleName()), null, detached);
        }
    }

}
