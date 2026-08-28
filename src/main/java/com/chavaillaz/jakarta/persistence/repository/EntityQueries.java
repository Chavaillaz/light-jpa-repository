package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.Pageables.toPage;
import static org.hibernate.query.restriction.Restriction.unrestricted;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;

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
     * Creates the queries of a repository.
     *
     * @param entityManager The entity manager to use
     * @param entityType    The type of the managed entity
     * @param ordering      The ordering rules of the repository
     * @param cursorCodec   The codec of the cursor tokens
     */
    public EntityQueries(EntityManager entityManager, Class<E> entityType, EntityOrdering<E> ordering, CursorCodec cursorCodec) {
        this.entityManager = entityManager;
        this.entityType = entityType;
        this.ordering = ordering;
        this.cursorCodec = cursorCodec;
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
                        query.where(query.getRestriction(), criteria.toPredicate(criteriaBuilder, query, root));
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
                        restrict(criteriaBuilder, query, Keysets.seek(criteriaBuilder, root, direction, position.values()));
                    }
                    query.orderBy(Keysets.toOrders(criteriaBuilder, root, direction));
                })
                .createQuery(entityManager)
                .setMaxResults(cursor.limit())
                .getResultList();

        return Cursors.toResult(cursorCodec, fetched, cursor, resolvedSort, position);
    }

}

