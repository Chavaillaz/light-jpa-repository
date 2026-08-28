package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.Pageable.sortedBy;
import static com.chavaillaz.jakarta.persistence.repository.Pageable.unpaged;
import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;
import static jakarta.transaction.Transactional.TxType.MANDATORY;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.hibernate.query.restriction.Restriction.unrestricted;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import com.chavaillaz.jakarta.persistence.Identifiable;
import com.github.tennaito.rsql.jpa.JpaCriteriaCountQueryVisitor;
import com.github.tennaito.rsql.jpa.JpaCriteriaQueryVisitor;
import cz.jirutka.rsql.parser.RSQLParser;
import cz.jirutka.rsql.parser.ast.Node;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;
import org.hibernate.query.restriction.Restriction;
import org.jspecify.annotations.Nullable;

/**
 * Base implementation of the {@link Repository} contract, relying on the JPA {@link EntityManager}.
 * <p>
 * The queries are delegated to three collaborators, reachable through {@link #ordering()},
 * {@link #queries()} and {@link #rsqlQueries()}, so that each concern stays isolated and testable on its own.
 * They are created on the first use, the entity manager being passed to the repository constructor so that
 * the subclasses can stay simple and dependency-injected by their constructor.
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
     * The entity manager the repository operates on.
     */
    protected final EntityManager entityManager;

    /**
     * The type of the managed entity, resolved from the type parameters of the subclass.
     */
    protected final Class<E> entityType;

    /**
     * The parser converting the RSQL queries into nodes.
     */
    protected final RSQLParser rsqlParser;

    /**
     * @see #ordering()
     */
    private @Nullable EntityOrdering<E> ordering;

    /**
     * @see #queries()
     */
    private @Nullable EntityQueries<E> queries;

    /**
     * @see #rsqlQueries()
     */
    private @Nullable RsqlQueries<E> rsqlQueries;

    /**
     * Creates a repository using the default RSQL parser.
     *
     * @param entityManager The entity manager the repository operates on
     * @param entityType    The type of the managed entity
     */
    protected AbstractRepository(EntityManager entityManager, Class<E> entityType) {
        this(entityManager, entityType, new RSQLParser());
    }

    /**
     * Creates a repository using the given RSQL parser, to support custom operators for instance.
     *
     * @param entityManager The entity manager the repository operates on
     * @param entityType    The type of the managed entity
     * @param rsqlParser    The parser used to build the RSQL query nodes
     */
    protected AbstractRepository(EntityManager entityManager, Class<E> entityType, RSQLParser rsqlParser) {
        this.entityManager = entityManager;
        this.entityType = entityType;
        this.rsqlParser = rsqlParser;
    }

    /**
     * Gets the ordering rules of the repository.
     *
     * @return The ordering rules, resolving the sortable properties and building the query ordering
     */
    protected EntityOrdering<E> ordering() {
        if (ordering == null) {
            // The hooks are passed as method references, so that the overriding subclasses stay in charge of them.
            ordering = new EntityOrdering<>(entityManager, entityType, this::getDefaultOrders, this::searchableProperties);
        }
        return ordering;
    }

    /**
     * Gets the query support of the repository.
     *
     * @return The query support, building the search, count and scroll queries from the restrictions and criteria
     */
    protected EntityQueries<E> queries() {
        if (queries == null) {
            queries = new EntityQueries<>(entityManager, entityType, ordering(), cursorCodec());
        }
        return queries;
    }

    /**
     * Gets the RSQL support of the repository.
     *
     * @return The RSQL support, translating the filter expressions into predicates over the searchable properties
     */
    protected RsqlQueries<E> rsqlQueries() {
        if (rsqlQueries == null) {
            // The hooks are passed as method references, so that the overriding subclasses stay in charge of them.
            rsqlQueries = new RsqlQueries<>(entityManager, entityType, rsqlParser, this::createQueryVisitor, this::createCountVisitor, ordering(), cursorCodec());
        }
        return rsqlQueries;
    }

    @Override
    public PaginationResult<E> findAll(Pageable pageable) {
        return queries().search(unrestricted(), null, pageable);
    }

    @Override
    public CursorResult<E> findAll(Cursor cursor) {
        return queries().scroll(unrestricted(), null, cursor);
    }

    @Override
    public Optional<E> findById(@Nullable I id) {
        return Optional.ofNullable(id).map(identifier -> entityManager.find(entityType, identifier));
    }

    @Override
    public PaginationResult<E> search(@Nullable String rsql, Pageable pageable) {
        if (isBlank(rsql)) {
            return findAll(pageable);
        }

        return rsqlQueries().search(rsqlQueries().parse(rsql), pageable);
    }

    @Override
    public CursorResult<E> search(@Nullable String rsql, Cursor cursor) {
        if (isBlank(rsql)) {
            return findAll(cursor);
        }
        return rsqlQueries().scroll(rsqlQueries().parse(rsql), cursor);
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
        return queries().search(restriction, null, pageable);
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
        return queries().search(restriction, criteria, pageable);
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
        return queries().search(null, criteria, pageable);
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
        return queries().search(restriction, null, unpaged()).items();
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
        return queries().search(restriction, null, sortedBy(sort)).items();
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
        return queries().search(null, criteria, sortedBy(sort)).items();
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
        return queries().search(restriction, criteria, unpaged()).items();
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
        return queries().search(null, criteria, unpaged()).items();
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
     * @see EntityQueries#search(Class, Restriction)
     */
    protected <R> List<R> search(Class<R> relatedType, @Nullable Restriction<? super R> restriction) {
        return queries().search(relatedType, restriction);
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
        return queries().scroll(restriction, null, cursor);
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
        return queries().scroll(null, criteria, cursor);
    }

    /**
     * Scrolls through the entities matching the given restriction and additional criteria, seeking to the
     * requested position instead of skipping the preceding rows.
     * <p>
     * The restriction carries the reusable scope of the repository, the criteria what is specific to a single
     * lookup. Both are combined with the seek predicate of the cursor, so a page can never escape the scope it was
     * issued within. A criteria joining a collection must apply a distinct itself, a duplicated boundary row
     * shortening the page.
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
        return queries().scroll(restriction, criteria, cursor);
    }

    @Override
    public long count() {
        return queries().count(unrestricted(), null);
    }

    @Override
    public long count(@Nullable String rsql) {
        if (isBlank(rsql)) {
            return count();
        }

        return count(rsqlQueries().parse(rsql));
    }

    /**
     * Counts the entities matching the given RSQL query, without any pagination applied.
     *
     * @param rsqlNode The parsed RSQL query
     * @return The total number of matching entities
     * @see RsqlQueries#count(Node)
     */
    protected long count(Node rsqlNode) {
        return rsqlQueries().count(rsqlNode);
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
        return queries().count(restriction, null);
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
        return queries().count(restriction, criteria);
    }

    /**
     * Counts the entities matching the given criteria only.
     *
     * @param criteria The criteria to apply, or {@code null} to count all the entities
     * @return The total number of matching entities
     * @see #count(Restriction, Criteria)
     */
    protected long count(@Nullable Criteria<E> criteria) {
        return queries().count(null, criteria);
    }

    /**
     * Gets the first entity matching the given restriction, following the default ordering of the repository.
     *
     * @param restriction The restriction to apply, or {@code null}
     * @return The corresponding entity, or {@link Optional#empty()} if none matches
     * @see #first(Restriction, Criteria, Sort)
     */
    protected Optional<E> first(@Nullable Restriction<? super E> restriction) {
        return queries().first(restriction, null, Sort.NONE);
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
        return queries().first(restriction, criteria, Sort.NONE);
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
        return queries().first(restriction, criteria, sort);
    }

    /**
     * Gets the first entity matching the given criteria only, following the default ordering of the repository.
     *
     * @param criteria The criteria to apply, or {@code null} to match all the entities
     * @return The corresponding entity, or {@link Optional#empty()} if none matches
     * @see #first(Criteria, Sort)
     */
    protected Optional<E> first(@Nullable Criteria<E> criteria) {
        return queries().first(null, criteria, Sort.NONE);
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
        return queries().first(null, criteria, sort);
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
     * Creates the visitor converting an RSQL query node into a JPA criteria query returning the matching entities.
     * <p>
     * Override to customize the property mapping, the argument parsing or the predicate building.
     *
     * @return The visitor to use to build the search query
     */
    protected JpaCriteriaQueryVisitor<E> createQueryVisitor() {
        return RsqlQueries.defaultQueryVisitor(entityType);
    }

    /**
     * Creates the visitor converting an RSQL query node into a JPA criteria query counting the matching entities.
     * <p>
     * Override to customize the property mapping, the argument parsing or the predicate building.
     *
     * @return The visitor to use to build the count query
     */
    protected JpaCriteriaCountQueryVisitor<E> createCountVisitor() {
        return RsqlQueries.defaultCountVisitor(entityType);
    }

    @Override
    public void lock(E entity) {
        // A detached entity must first be re-attached with a fresh load rather than merged: locking must not
        // implicitly persist local changes carried by a stale detached copy
        E managedEntity = entityManager.contains(entity) ? entity : entityManager.find(entityType, entity.getId());
        if (managedEntity == null) {
            throw new NoSuchElementException("No entity found with the identifier %s in %s".formatted(entity.getId(), getClass().getSimpleName()));
        }
        // Refresh to get last state of the entity if being already locked and changed
        entityManager.refresh(managedEntity, PESSIMISTIC_WRITE);
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

    @Override
    public void refresh(E entity) {
        entityManager.refresh(entity);
    }

    @Override
    public void delete(E entity) {
        // A detached entity must first be re-attached, otherwise the removal is silently ignored
        entityManager.remove(entityManager.contains(entity) ? entity : entityManager.merge(entity));
    }

}

