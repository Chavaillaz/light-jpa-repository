package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.Pageable.sortedBy;
import static com.chavaillaz.jakarta.persistence.repository.Pageable.unpaged;
import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;
import static jakarta.transaction.Transactional.TxType.MANDATORY;
import static org.hibernate.query.restriction.Restriction.unrestricted;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.EntityType;
import jakarta.transaction.Transactional;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import org.hibernate.query.restriction.Restriction;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;

/**
 * Base implementation of the {@link Repository} contract, relying on the JPA {@link EntityManager}.
 * <p>
 * The queries are delegated to two collaborators, reachable through {@link #ordering()} and {@link #queries()},
 * so that each concern stays isolated and testable on its own. They are created on the first use, the entity
 * manager being passed to the repository constructor so that the subclasses can stay simple and
 * dependency-injected by their constructor.
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
     * @see #ordering()
     */
    private volatile @Nullable EntityOrdering<E> ordering;

    /**
     * @see #queries()
     */
    private volatile @Nullable EntityQueries<E> queries;

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
     * Gets the ordering rules of the repository, built on first use rather than eagerly in the constructor so
     * that {@link #getDefaultOrders} and {@link #searchableProperties} are never invoked before the subclass is
     * fully constructed.
     * <p>
     * Built under double checked locking rather than plainly, since a repository is not guaranteed to be confined
     * to a single thread by whatever scope its owning dependency injection container gives it: without it, two
     * threads racing on the first call could each observe a stale {@code null} and build their own instance.
     *
     * @return The ordering rules, resolving the sortable properties and building the query ordering
     */
    protected EntityOrdering<E> ordering() {
        EntityOrdering<E> current = ordering;
        if (current == null) {
            synchronized (this) {
                current = ordering;
                if (current == null) {
                    // The hooks are passed as method references, so that the overriding subclasses stay in charge
                    ordering = current = new EntityOrdering<>(entityManager, entityType, this::getDefaultOrders, this::searchableProperties);
                }
            }
        }
        return current;
    }

    /**
     * Gets the query support of the repository, built on first use for the same reason and under the same double
     * checked locking as {@link #ordering()}.
     *
     * @return The query support, building the search, count and scroll queries from the restrictions and criteria
     */
    protected EntityQueries<E> queries() {
        EntityQueries<E> current = queries;
        if (current == null) {
            synchronized (this) {
                current = queries;
                if (current == null) {
                    queries = current = new EntityQueries<>(entityManager, entityType, ordering(), cursorCodec(), cursorKeyCodec());
                }
            }
        }
        return current;
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

        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<E> query = criteriaBuilder.createQuery(entityType);
        Root<E> root = query.from(entityType);
        query.select(root).where(root.get(idAttribute.get()).in(distinctIds));

        return entityManager.createQuery(query).getResultList();
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
        return queries().scroll(restriction, criteria, cursor);
    }

    @Override
    public long count() {
        return queries().count(unrestricted(), null);
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
    public void lock(E entity) {
        E managedEntity = reattach(entity);
        // Refresh to get last state of the entity if being already locked and changed
        entityManager.refresh(managedEntity, PESSIMISTIC_WRITE);
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

    @Override
    public void refresh(E entity) {
        entityManager.refresh(entity);
    }

    @Override
    public void delete(E entity) {
        entityManager.remove(reattach(entity));
    }

}

