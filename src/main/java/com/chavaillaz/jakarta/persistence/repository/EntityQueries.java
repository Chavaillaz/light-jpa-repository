package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.Pageables.toPage;
import static org.hibernate.query.restriction.Restriction.unrestricted;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Optional;

import org.hibernate.query.SelectionQuery;
import org.hibernate.query.restriction.Restriction;
import org.hibernate.query.specification.SelectionSpecification;
import org.jspecify.annotations.Nullable;

/**
 * Typed queries of a repository, built from the Hibernate {@link Restriction restrictions}, which are checked at
 * compile time against the static metamodel, and from the additional {@link Criteria criteria}, which express what
 * the restrictions cannot, such as the correlated subqueries.
 * <p>
 * The total number of matching items is always derived from the very same query as the results, so that the count
 * can never drift away from them, as it does when both are written as two separate queries.
 *
 * @param <E> The type of the managed entity
 */
public class EntityQueries<E> {

    /**
     * The entity manager the queries are created from.
     */
    protected final EntityManager entityManager;

    /**
     * The type of the managed entity.
     */
    protected final Class<E> entityType;

    /**
     * The ordering rules of the repository.
     */
    protected final EntityOrdering<E> ordering;

    /**
     * The codec of the cursor tokens.
     */
    protected final CursorCodec cursorCodec;

    /**
     * The codec of the cursor key values.
     */
    protected final CursorKeyCodec cursorKeyCodec;

    /**
     * Creates the queries of a repository.
     *
     * @param entityManager  The entity manager to use
     * @param entityType     The type of the managed entity
     * @param ordering       The ordering rules of the repository
     * @param cursorCodec    The codec of the cursor tokens
     * @param cursorKeyCodec The codec of the cursor key values
     */
    public EntityQueries(EntityManager entityManager, Class<E> entityType, EntityOrdering<E> ordering, CursorCodec cursorCodec, CursorKeyCodec cursorKeyCodec) {
        this.entityManager = entityManager;
        this.entityType = entityType;
        this.ordering = ordering;
        this.cursorCodec = cursorCodec;
        this.cursorKeyCodec = cursorKeyCodec;
    }

    /**
     * Appends a predicate to the restriction of a query, the existing one being possibly absent.
     *
     * @param criteriaBuilder The builder to combine the predicates with
     * @param query           The query to restrict
     * @param predicate       The predicate to append
     */
    protected static void restrict(CriteriaBuilder criteriaBuilder, CriteriaQuery<?> query, Predicate predicate) {
        Predicate existing = query.getRestriction();
        query.where(existing == null ? predicate : criteriaBuilder.and(existing, predicate));
    }

    /**
     * Checks if the given root joins a to-many association, directly or through another join, an entity being
     * otherwise duplicated in the results as many times as it has matching children, which also breaks the
     * pagination and the count.
     *
     * @param from The root or join to inspect
     * @return {@code true} if the joins may produce duplicated rows, {@code false} otherwise
     */
    protected static boolean hasCollectionJoin(From<?, ?> from) {
        return from.getJoins().stream()
                .anyMatch(join -> join.getAttribute().isCollection() || hasCollectionJoin(join));
    }

    /**
     * Builds the selection query matching the given restriction and additional criteria, ordered by the requested
     * criteria or by the default ones.
     * <p>
     * The ordering is applied through an augmentation, so that it relies on the very same
     * {@link EntityOrdering#buildOrders(jakarta.persistence.criteria.Root, Sort) criteria logic} as the other
     * queries of the repository, the ordering rules being therefore defined only once.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param sort        The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding query
     * @throws IllegalArgumentException if the ordering refers to an unknown property or to a collection
     */
    public SelectionQuery<E> createQuery(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Sort sort) {
        return SelectionSpecification.create(entityType)
                .restrict(restriction == null ? unrestricted() : restriction)
                .augment((criteriaBuilder, query, root) -> {
                    if (criteria != null) {
                        // Appended through the null safe helper, the restriction of the query being absent when
                        // the given one matches every entity
                        restrict(criteriaBuilder, query, criteria.toPredicate(criteriaBuilder, query, root));
                    }
                    // A restriction or a criteria joining a to-many association duplicates the root entity as
                    // many times as it has matching children; distinct is applied automatically rather than
                    // left to the caller, so a forgotten join cannot silently corrupt the results or the count
                    if (hasCollectionJoin(root)) {
                        query.distinct(true);
                    }
                    query.orderBy(ordering.buildOrders(root, sort));
                })
                .createQuery(entityManager);
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
     * @throws IllegalArgumentException if the requested ordering refers to an unknown property or to a collection
     * @see #createQuery(Restriction, Criteria, Sort)
     */
    public PaginationResult<E> search(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Pageable pageable) {
        SelectionQuery<E> query = createQuery(restriction, criteria, pageable.sort());

        if (pageable.isPaginated()) {
            // The count is intentionally computed from the same query, before the pagination is applied
            long totalItems = query.getResultCount();

            // A page number far beyond the end overflows the int offset the JDBC drivers take, which the
            // providers reject as a negative first result; such a page is empty anyway, so it is returned as is
            // rather than surfaced as a server error on what is a plain query parameter
            if (Pageables.overflows(pageable)) {
                return PaginationResult.of(List.of(), pageable.page(), pageable.size(), totalItems);
            }

            return PaginationResult.of(query.setPage(toPage(pageable)).getResultList(), pageable.page(), pageable.size(), totalItems);
        }

        return PaginationResult.single(query.getResultList());
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
     */
    public <R> List<R> search(Class<R> relatedType, @Nullable Restriction<? super R> restriction) {
        return SelectionSpecification.create(relatedType)
                .restrict(restriction == null ? unrestricted() : restriction)
                .createQuery(entityManager)
                .getResultList();
    }

    /**
     * Counts the entities matching the given restriction and additional criteria.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to count all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @return The total number of matching entities
     */
    public long count(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        return createQuery(restriction, criteria, Sort.NONE).getResultCount();
    }

    /**
     * Checks whether at least one entity matches the given restriction and additional criteria.
     * <p>
     * Unlike {@link #count(Restriction, Criteria)}, the database stops at the first matching row and no entity is
     * hydrated: only a literal is selected, so nothing is added to the persistence context either.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @return {@code true} if at least one entity matches, {@code false} otherwise
     */
    public boolean exists(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Integer> query = criteriaBuilder.createQuery(Integer.class);
        Root<E> root = query.from(entityType);
        query.select(criteriaBuilder.literal(1));

        if (restriction != null) {
            restrict(criteriaBuilder, query, restriction.toPredicate(root, criteriaBuilder));
        }
        if (criteria != null) {
            restrict(criteriaBuilder, query, criteria.toPredicate(criteriaBuilder, query, root));
        }

        // No ordering is applied, the question being whether a row exists and not which one comes first, and a
        // plain list is used rather than getSingleResult(), which would throw when nothing matches
        return !entityManager.createQuery(query).setMaxResults(1).getResultList().isEmpty();
    }

    /**
     * Deletes every entity matching the given restriction and additional criteria, in a single statement.
     * <p>
     * This is a bulk deletion, which the database performs on its own: it does not cascade to the associations,
     * does not honour {@code orphanRemoval}, does not run the {@code @PreRemove} callbacks and leaves the already
     * loaded entities in the persistence context, which therefore holds rows that no longer exist. Prefer
     * deleting the entities one by one when any of that matters, and refresh or clear the persistence context
     * afterwards when it does not.
     * <p>
     * A restriction joining an association cannot be expressed by a bulk deletion, which has no {@code from}
     * clause to join: restrict on the attributes of the entity itself, or select the entities to delete with
     * {@link Criteria#exists(Class, String, java.util.function.BiFunction)}, which is a subquery.
     *
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to delete
     *                    every entity
     * @param criteria    The additional criteria to apply, or {@code null}
     * @return The number of deleted entities
     */
    public int delete(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaDelete<E> delete = criteriaBuilder.createCriteriaDelete(entityType);
        Root<E> root = delete.from(entityType);

        Predicate predicate = null;
        if (restriction != null) {
            predicate = restriction.toPredicate(root, criteriaBuilder);
        }
        if (criteria != null) {
            Predicate additional = criteria.toPredicate(criteriaBuilder, delete, root);
            predicate = predicate == null ? additional : criteriaBuilder.and(predicate, additional);
        }
        if (predicate != null) {
            delete.where(predicate);
        }

        return entityManager.createQuery(delete).executeUpdate();
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
     * @see #createQuery(Restriction, Criteria, Sort)
     */
    public Optional<E> first(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Sort sort) {
        // A plain list is used rather than getResultStream(), which the caller would have to close explicitly
        return createQuery(restriction, criteria, sort)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }

    /**
     * Scrolls through the entities matching the given restriction and criteria.
     *
     * @param restriction The restriction to apply, or {@code null}
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param cursor      The requested position, size and ordering
     * @return The corresponding page with the tokens of the surrounding ones
     * @throws IllegalArgumentException if the ordering is not usable as a cursor key, if an ordering key of the
     *                                  boundary row is {@code null}, or if the cursor is malformed or was issued
     *                                  for another ordering
     */
    public CursorResult<E> scroll(@Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Cursor cursor) {
        Sort resolvedSort = ordering.resolveSort(cursor.sort());
        CursorPosition position = Cursors.position(cursorCodec, cursor, resolvedSort);
        Sort direction = Cursors.direction(resolvedSort, position);

        List<E> fetched = SelectionSpecification.create(entityType)
                .restrict(restriction == null ? unrestricted() : restriction)
                .augment((criteriaBuilder, query, root) -> {
                    if (criteria != null) {
                        restrict(criteriaBuilder, query, criteria.toPredicate(criteriaBuilder, query, root));
                    }
                    if (position != null) {
                        restrict(criteriaBuilder, query, Keysets.seek(criteriaBuilder, root, direction, position.values(), cursorKeyCodec));
                    }
                    if (hasCollectionJoin(root)) {
                        query.distinct(true);
                    }
                    query.orderBy(Keysets.toOrders(criteriaBuilder, root, direction));
                })
                .createQuery(entityManager)
                .setMaxResults(cursor.limit())
                .getResultList();

        return Cursors.toResult(cursorCodec, fetched, cursor, resolvedSort, position, cursorKeyCodec);
    }

}

