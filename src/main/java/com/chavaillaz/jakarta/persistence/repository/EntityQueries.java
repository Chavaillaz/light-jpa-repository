package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.Pageables.toPage;
import static org.hibernate.query.restriction.Restriction.unrestricted;

import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.hibernate.query.SelectionQuery;
import org.hibernate.query.restriction.Restriction;
import org.hibernate.query.specification.SelectionSpecification;
import org.hibernate.query.sqm.tree.from.SqmAttributeJoin;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmJoin;
import org.jspecify.annotations.Nullable;

/**
 * Typed queries of an entity type, built from the Hibernate {@link Restriction restrictions}, which are checked at
 * compile time against the static metamodel, and from the additional {@link Criteria criteria}, which express what
 * the restrictions cannot, such as the correlated subqueries.
 * <p>
 * One instance is shared by every repository over the same entity: what belongs to the repository a query is
 * written for travels with the {@link RepositoryContext} handed over at each call.
 * <p>
 * The total number of matching items is always derived from the very same query as the results, so that the count
 * can never drift away from them, as it does when both are written as two separate queries.
 *
 * @param <E> The type of the managed entity
 */
public class EntityQueries<E> {

    /**
     * The query support, held per entity type rather than per repository, nothing of a repository being kept
     * here. A {@link ClassValue} is used rather than a plain map keyed by the class, so that the cache cannot
     * hold a class, and therefore its class loader, alive after a redeployment.
     */
    private static final ClassValue<EntityQueries<?>> QUERIES = new ClassValue<>() {

        @Override
        protected EntityQueries<?> computeValue(Class<?> type) {
            return create(type);
        }

    };

    /**
     * The type of the managed entity.
     */
    protected final Class<E> entityType;

    /**
     * The ordering rules of the entity type.
     */
    protected final EntityOrdering<E> ordering;

    /**
     * Creates the queries of an entity type.
     * <p>
     * Prefer {@link #of(Class)}, which shares one instance per entity type.
     *
     * @param entityType The type of the managed entity
     * @param ordering   The ordering rules of the entity type
     */
    public EntityQueries(Class<E> entityType, EntityOrdering<E> ordering) {
        this.entityType = entityType;
        this.ordering = ordering;
    }

    /**
     * Gets the queries of the given entity type.
     * <p>
     * The instance is shared by every repository over that entity, which is possible precisely because it holds
     * nothing of any of them: the entity manager, the ordering hooks and the cursor codecs travel with the
     * {@link RepositoryContext} of each call. Nothing is therefore built per repository, and no entity manager,
     * which is bound to a transaction, is ever captured.
     *
     * @param <E>        The type of the managed entity
     * @param entityType The type of the managed entity
     * @return The corresponding queries
     */
    @SuppressWarnings("unchecked")
    public static <E> EntityQueries<E> of(Class<E> entityType) {
        // A ClassValue erases the link between the key and the value it computes from it, so the cast cannot be
        // proven by the compiler; it holds by construction, create building the queries of that very class
        return (EntityQueries<E>) QUERIES.get(entityType);
    }

    /**
     * Creates the queries of an entity type, the type parameter being captured so that the entity type and its
     * ordering rules are known to be the very same one.
     *
     * @param <T>        The type of the managed entity
     * @param entityType The type of the managed entity
     * @return The corresponding queries
     */
    private static <T> EntityQueries<T> create(Class<T> entityType) {
        return new EntityQueries<>(entityType, EntityOrdering.of(entityType));
    }

    /**
     * Appends a predicate to the restriction of a query, the existing one being possibly absent.
     *
     * @param criteriaBuilder The builder to combine the predicates with
     * @param query           The query to restrict
     * @param predicate       The predicate to append
     */
    protected static void restrict(CriteriaBuilder criteriaBuilder, CriteriaQuery<?> query, Predicate predicate) {
        query.where(and(criteriaBuilder, query.getRestriction(), predicate));
    }

    /**
     * Combines two predicates, the first one being possibly absent.
     *
     * @param criteriaBuilder The builder to combine the predicates with
     * @param existing        The predicate to combine with, or {@code null}
     * @param predicate       The predicate to append
     * @return The combined predicate
     */
    protected static Predicate and(CriteriaBuilder criteriaBuilder, @Nullable Predicate existing, Predicate predicate) {
        return existing == null ? predicate : criteriaBuilder.and(existing, predicate);
    }

    /**
     * Checks if the given root joins what may match several rows per entity, directly or through another join: a
     * to-many association, or a join following no association at all, such as an entity join. The entity is
     * otherwise duplicated in the results once per matching row, which also breaks the pagination and the count.
     *
     * @param from The root or join to inspect
     * @return {@code true} if the joins may produce duplicated rows, {@code false} otherwise
     */
    protected static boolean hasCollectionJoin(From<?, ?> from) {
        // Read from the Hibernate query tree, since From#getJoins leaves out every join but the attribute ones
        return ((SqmFrom<?, ?>) from).getSqmJoins().stream().anyMatch(EntityQueries::multipliesRows);
    }

    private static boolean multipliesRows(SqmJoin<?, ?> join) {
        if (join instanceof SqmAttributeJoin<?, ?> attributeJoin) {
            // A fetch filters nothing, and From#getJoins leaves it out as well
            return !attributeJoin.isFetched() && (attributeJoin.getAttribute().isCollection() || hasCollectionJoin(attributeJoin));
        }
        return true;
    }

    /**
     * Builds the selection query matching the given restriction and additional criteria, ordered by the requested
     * criteria or by the default ones.
     * <p>
     * The ordering is applied through an augmentation, so that it relies on the very same
     * {@link EntityOrdering#buildOrders(RepositoryContext, jakarta.persistence.criteria.Root, Sort) criteria logic} as the other
     * queries of the repository, the ordering rules being therefore defined only once.
     *
     * @param context     The repository the query is written for
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param sort        The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding query
     * @throws IllegalArgumentException if the ordering refers to an unknown property or to a collection
     */
    public SelectionQuery<E> createQuery(RepositoryContext<E> context, @Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Sort sort) {
        boolean semiJoined = joinsCollection(context, restriction, criteria);

        return SelectionSpecification.create(entityType)
                // A restriction joining a collection is moved into the semi join below, and must therefore not be
                // applied to the root as well, which would join it a second time
                .restrict(restriction == null || semiJoined ? unrestricted() : restriction)
                .augment((criteriaBuilder, query, root) -> {
                    if (semiJoined) {
                        restrict(criteriaBuilder, query, semiJoin(criteriaBuilder, query, root, restriction, criteria));
                    } else if (criteria != null) {
                        // Appended through the null safe helper, the restriction of the query being absent when
                        // the given one matches every entity
                        restrict(criteriaBuilder, query, criteria.toPredicate(criteriaBuilder, query, root));
                    }
                    query.orderBy(ordering.buildOrders(context, root, sort));
                })
                .createQuery(context.entityManager());
    }

    /**
     * Checks whether the given restriction and criteria join a to-many association, which duplicates the root
     * entity as many times as it has matching children.
     * <p>
     * The predicates are built against a throwaway root, which is the only way to know what they join: they are
     * opaque until they are applied. Nothing but criteria nodes is created, no query reaching the database.
     *
     * @param context     The repository the query is written for
     * @param restriction The restriction to inspect, or {@code null}
     * @param criteria    The criteria to inspect, or {@code null}
     * @return {@code true} if applying them to a root would join a collection, {@code false} otherwise
     */
    protected boolean joinsCollection(RepositoryContext<E> context, @Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        if (restriction == null && criteria == null) {
            return false;
        }

        CriteriaBuilder criteriaBuilder = context.entityManager().getCriteriaBuilder();
        CriteriaQuery<E> probe = criteriaBuilder.createQuery(entityType);
        Root<E> root = probe.from(entityType);

        if (restriction != null) {
            restriction.toPredicate(root, criteriaBuilder);
        }
        if (criteria != null) {
            criteria.toPredicate(criteriaBuilder, probe, root);
        }
        return hasCollectionJoin(root);
    }

    /**
     * Builds the predicate keeping the entities matching the given restriction and criteria as a semi join: the
     * predicates are applied to a correlated subquery instead of to the root of the query itself.
     * <p>
     * A collection join multiplies the root entity by its matching children, which used to be compensated by a
     * {@code distinct}. That compensation is not portable: a {@code select distinct} may only be ordered by
     * expressions of its own select list, so ordering on a joined attribute, which the pagination of this library
     * does as soon as a nested property is sorted on, is rejected by PostgreSQL and Oracle, whereas H2 and MySQL
     * accept it. It also forces the database to deduplicate a result set it had to multiply first.
     * <p>
     * An {@code exists} subquery has neither problem: the row is never duplicated in the first place, so no
     * {@code distinct} is needed, the ordering is free to reach whatever it needs, and the count matches the
     * results without any further care.
     *
     * @param criteriaBuilder The builder to use
     * @param query           The query being built, to create the subquery from
     * @param root            The root entity of the query
     * @param restriction     The restriction to apply, or {@code null}
     * @param criteria        The criteria to apply, or {@code null}
     * @return The corresponding predicate
     */
    protected Predicate semiJoin(
            CriteriaBuilder criteriaBuilder,
            CommonAbstractCriteria query,
            Root<E> root,
            @Nullable Restriction<? super E> restriction,
            @Nullable Criteria<E> criteria) {
        Subquery<Integer> matching = query.subquery(Integer.class);
        Root<E> matched = matching.from(entityType);

        // Comparing the two roots as entities correlates the subquery on the identifier, whatever it is made of
        Predicate predicate = criteriaBuilder.equal(matched, root);
        if (restriction != null) {
            predicate = criteriaBuilder.and(predicate, restriction.toPredicate(matched, criteriaBuilder));
        }
        if (criteria != null) {
            predicate = criteriaBuilder.and(predicate, criteria.toPredicate(criteriaBuilder, matching, matched));
        }

        return criteriaBuilder.exists(matching.select(criteriaBuilder.literal(1)).where(predicate));
    }

    /**
     * Combines the given restriction and additional criteria into the predicate restricting a query, either one
     * being possibly absent.
     * <p>
     * This is what every query but the paginated search is restricted with, so that a restriction and a criteria
     * are combined the very same way whether the query counts, checks an existence, scrolls or deletes.
     *
     * @param criteriaBuilder The builder to use
     * @param query           The query being built, to create the subqueries of the criteria from
     * @param root            The root entity of the query
     * @param restriction     The restriction to apply, or {@code null}
     * @param criteria        The additional criteria to apply, or {@code null}
     * @return The corresponding predicate, or {@code null} when neither is given, the query then matching every
     * entity
     */
    protected @Nullable Predicate toPredicate(
            CriteriaBuilder criteriaBuilder,
            CommonAbstractCriteria query,
            Root<E> root,
            @Nullable Restriction<? super E> restriction,
            @Nullable Criteria<E> criteria) {
        Predicate predicate = null;
        if (restriction != null) {
            predicate = restriction.toPredicate(root, criteriaBuilder);
        }
        if (criteria != null) {
            predicate = and(criteriaBuilder, predicate, criteria.toPredicate(criteriaBuilder, query, root));
        }
        return predicate;
    }

    /**
     * Searches for the entities matching the given restriction and additional criteria.
     *
     * @param context     The repository the query is written for
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param pageable    The requested page and ordering, {@link Pageable#UNPAGED} to return all the matching
     *                    entities with the default ordering of the repository
     * @return The entities of the requested page with the total number of matching entities
     * @throws IllegalArgumentException if the requested ordering refers to an unknown property or to a collection
     * @see #createQuery(RepositoryContext, Restriction, Criteria, Sort)
     */
    public PaginationResult<E> search(RepositoryContext<E> context, @Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Pageable pageable) {
        SelectionQuery<E> query = createQuery(context, restriction, criteria, pageable.sort());

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
     * <p>
     * The results are ordered by the identifier of the <em>related</em> entity, and not by the ordering rules of
     * the repository, which are those of the entity it manages and say nothing about another type. That is the
     * one ordering the metamodel alone provides, and it is enough for the results to come back in a stable order
     * rather than in whatever order the database happened to produce, which is what every other query of this
     * library guarantees. Order a related search on business attributes by writing it as a query of the
     * repository managing that type.
     *
     * @param context     The repository the query is written for
     * @param <R>         The type of the related entity
     * @param relatedType The type of the related entity
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @return The matching entities, ordered by their identifier
     */
    public <R> List<R> search(RepositoryContext<E> context, Class<R> relatedType, @Nullable Restriction<? super R> restriction) {
        EntityOrdering<R> relatedOrdering = EntityOrdering.of(relatedType);

        return SelectionSpecification.create(relatedType)
                .restrict(restriction == null ? unrestricted() : restriction)
                .augment((criteriaBuilder, query, root) -> query.orderBy(relatedOrdering
                        .getIdPaths(context.entityManager(), root)
                        .map(criteriaBuilder::asc)
                        .toList()))
                .createQuery(context.entityManager())
                .getResultList();
    }

    /**
     * Counts the entities matching the given restriction and additional criteria.
     *
     * @param context     The repository the query is written for
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to count all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @return The total number of matching entities
     */
    public long count(RepositoryContext<E> context, @Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        return createQuery(context, restriction, criteria, Sort.NONE).getResultCount();
    }

    /**
     * Checks whether at least one entity matches the given restriction and additional criteria.
     * <p>
     * Unlike {@link #count(RepositoryContext, Restriction, Criteria)}, the database stops at the first matching row and no entity is
     * hydrated: only a literal is selected, so nothing is added to the persistence context either.
     *
     * @param context     The repository the query is written for
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to match all
     *                    the entities
     * @param criteria    The additional criteria to apply, or {@code null}
     * @return {@code true} if at least one entity matches, {@code false} otherwise
     */
    public boolean exists(RepositoryContext<E> context, @Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        CriteriaBuilder criteriaBuilder = context.entityManager().getCriteriaBuilder();
        CriteriaQuery<Integer> query = criteriaBuilder.createQuery(Integer.class);
        Root<E> root = query.from(entityType);
        query.select(criteriaBuilder.literal(1));

        Predicate predicate = toPredicate(criteriaBuilder, query, root, restriction, criteria);
        if (predicate != null) {
            query.where(predicate);
        }

        // No ordering is applied, the question being whether a row exists and not which one comes first, and a
        // plain list is used rather than getSingleResult(), which would throw when nothing matches
        return !context.entityManager().createQuery(query).setMaxResults(1).getResultList().isEmpty();
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
     * @param context     The repository the query is written for
     * @param restriction The restriction to apply, {@code null} or {@link Restriction#unrestricted()} to delete
     *                    every entity
     * @param criteria    The additional criteria to apply, or {@code null}
     * @return The number of deleted entities
     */
    public int delete(RepositoryContext<E> context, @Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        CriteriaBuilder criteriaBuilder = context.entityManager().getCriteriaBuilder();
        CriteriaDelete<E> delete = criteriaBuilder.createCriteriaDelete(entityType);
        Root<E> root = delete.from(entityType);

        Predicate predicate = toPredicate(criteriaBuilder, delete, root, restriction, criteria);
        if (predicate != null) {
            delete.where(predicate);
        }

        return context.entityManager().createQuery(delete).executeUpdate();
    }

    /**
     * Gets the first entity matching the given restriction and additional criteria, following the requested
     * ordering.
     * <p>
     * Only the first row is fetched, the ordering making it deterministic.
     *
     * @param context     The repository the query is written for
     * @param restriction The restriction to apply, or {@code null}
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param sort        The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding entity, or {@link Optional#empty()} if none matches
     * @throws IllegalArgumentException if the ordering refers to an unknown property or to a collection
     * @see #createQuery(RepositoryContext, Restriction, Criteria, Sort)
     */
    public Optional<E> first(RepositoryContext<E> context, @Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Sort sort) {
        return first(context, restriction, criteria, sort, LockModeType.NONE);
    }

    /**
     * Gets the first entity matching the given restriction and additional criteria, following the requested
     * ordering, holding the requested lock on its row.
     * <p>
     * This is what claiming the next row to process is written with: the ordering makes the choice
     * deterministic, and the lock is taken as the row is read, so that a concurrent transaction ordering on the
     * very same criteria does not claim it as well.
     * <p>
     * Order the claim on attributes of the entity itself when a lock is taken: an ordering on a nested property
     * navigates its association with a left join, and PostgreSQL, among others, refuses to lock the nullable side
     * of an outer join. Sorting the candidates in the database and locking them by their own columns is portable,
     * ordering on a joined column and locking in the same statement is not.
     *
     * @param context     The repository the query is written for
     * @param restriction The restriction to apply, or {@code null}
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param sort        The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @param lockMode    The lock to hold on the row until the end of the transaction,
     *                    {@link LockModeType#NONE} to take none
     * @return The corresponding entity, or {@link Optional#empty()} if none matches
     * @throws IllegalArgumentException if the ordering refers to an unknown property or to a collection
     */
    public Optional<E> first(RepositoryContext<E> context, @Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Sort sort, LockModeType lockMode) {
        // A plain list is used rather than getResultStream(), which the caller would have to close explicitly
        return createQuery(context, restriction, criteria, sort)
                .setLockMode(lockMode)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }

    /**
     * Scrolls through the entities matching the given restriction and criteria.
     *
     * @param context     The repository the query is written for
     * @param restriction The restriction to apply, or {@code null}
     * @param criteria    The additional criteria to apply, or {@code null}
     * @param cursor      The requested position, size and ordering
     * @return The corresponding page with the tokens of the surrounding ones
     * @throws IllegalArgumentException if the ordering is not usable as a cursor key, if an ordering key of the
     *                                  boundary row is {@code null}, or if the cursor is malformed or was issued
     *                                  for another ordering
     */
    public CursorResult<E> scroll(RepositoryContext<E> context, @Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria, Cursor cursor) {
        Sort resolvedSort = ordering.resolveSort(context, cursor.sort());

        // A nullable key is refused here rather than when a token is built on it, which only catches the rows the
        // database happens to sort onto the first page, see EntityOrdering#requireSeekable
        resolvedSort.criteria().forEach(criterion -> ordering.requireSeekable(context, criterion.property()));

        CursorPosition position = Cursors.position(context.cursorCodec(), cursor, resolvedSort);
        Sort direction = Cursors.direction(resolvedSort, position);
        List<String> keyProperties = resolvedSort.criteria().stream().map(SortCriterion::property).toList();

        CriteriaBuilder criteriaBuilder = context.entityManager().getCriteriaBuilder();
        CriteriaQuery<Tuple> query = criteriaBuilder.createTupleQuery();
        Root<E> root = query.from(entityType);

        // A restriction or a criteria joining a collection is moved into a semi join, which keeps the boundary row
        // from being duplicated and therefore the page from being silently shortened
        Predicate predicate = joinsCollection(context, restriction, criteria)
                ? semiJoin(criteriaBuilder, query, root, restriction, criteria)
                : toPredicate(criteriaBuilder, query, root, restriction, criteria);
        if (position != null) {
            predicate = and(criteriaBuilder, predicate, Keysets.seek(criteriaBuilder, root, direction, position.values(), context.cursorKeyCodec()));
        }
        if (predicate != null) {
            query.where(predicate);
        }

        selectKeysAlongside(query, root, keyProperties);
        query.orderBy(Keysets.toOrders(criteriaBuilder, root, direction));

        List<Tuple> rows = context.entityManager().createQuery(query)
                .setMaxResults(cursor.limit())
                .getResultList();

        return Cursors.toResult(
                context.cursorCodec(),
                rows.stream().map(row -> row.get(0, entityType)).toList(),
                rows.stream().<Supplier<List<String>>>map(row -> () -> keysOf(context, row, keyProperties)).toList(),
                cursor,
                resolvedSort,
                position);
    }

    /**
     * Selects the entity alongside the very columns the ordering compares, so that the keys travelling within the
     * tokens are the values the database ordered on, and not what an accessor of the entity happens to return for
     * them.
     * <p>
     * The entity occupies the first element of each row, the keys following it in the ordering order, which is
     * what {@link #keysOf(RepositoryContext, Tuple, List)} reads them back with.
     *
     * @param query      The cursor query being built
     * @param root       The root entity of the query
     * @param properties The ordering properties, in the order they are compared in
     */
    protected void selectKeysAlongside(CriteriaQuery<Tuple> query, Root<E> root, List<String> properties) {
        List<Selection<?>> selections = new ArrayList<>(properties.size() + 1);
        selections.add(root);
        properties.forEach(property -> selections.add(AttributePaths.path(root, property)));
        query.multiselect(selections);
    }

    /**
     * Formats the ordering keys the query returned alongside an entity into their textual representation.
     *
     * @param context    The repository the query is written for
     * @param row        The fetched row, whose first element is the entity and whose others are the keys
     * @param properties The ordering properties, in the order they were selected in
     * @return The textual keys, in the ordering order
     * @throws IllegalArgumentException if one of the keys is {@code null}, a nullable attribute being unusable as
     *                                  a cursor key since the databases do not agree on where the nulls sort
     */
    protected List<String> keysOf(RepositoryContext<E> context, Tuple row, List<String> properties) {
        List<String> keys = new ArrayList<>(properties.size());
        for (int index = 0; index < properties.size(); index++) {
            // The entity occupies the first element, the keys following it in the ordering order
            keys.add(context.cursorKeyCodec().format(properties.get(index), row.get(index + 1)));
        }
        return keys;
    }

}
