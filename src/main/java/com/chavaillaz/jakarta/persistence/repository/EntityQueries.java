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
import jakarta.persistence.criteria.Subquery;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.hibernate.Session;
import org.hibernate.query.SelectionQuery;
import org.hibernate.query.restriction.Restriction;
import org.hibernate.query.specification.SelectionSpecification;
import org.hibernate.query.sqm.tree.from.SqmAttributeJoin;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmJoin;
import org.jspecify.annotations.Nullable;

/**
 * Typed queries of an entity type, built from the Hibernate {@link Restriction restrictions}, checked at compile
 * time against the static metamodel, and from the additional {@link Criteria criteria}, which express what the
 * restrictions cannot, such as the correlated subqueries.
 * <p>
 * One instance is shared by every repository over the same entity, what belongs to a repository travelling with
 * the {@link RepositoryContext} of each call. The total number of matching items is always derived from the very
 * same query as the results, so that the two cannot drift apart.
 *
 * @param <E> The type of the managed entity
 */
public class EntityQueries<E> {

    /**
     * The queries of each entity type, held in a {@link ClassValue} rather than in a map keyed by the class, so
     * that the cache cannot keep a class loader alive after a redeployment.
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
     * Gets the queries of the given entity type, shared by every repository over that entity.
     *
     * @param <E>        The type of the managed entity
     * @param entityType The type of the managed entity
     * @return The corresponding queries
     */
    @SuppressWarnings("unchecked")
    public static <E> EntityQueries<E> of(Class<E> entityType) {
        // A ClassValue loses the link between a class and the value computed from it, which holds by construction
        return (EntityQueries<E>) QUERIES.get(entityType);
    }

    /**
     * Creates the queries of an entity type, its type parameter being captured so that the entity type and its
     * ordering rules agree.
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
     * Checks if the given root joins what may match several rows per entity, directly or through another join or a
     * treat: a to-many association, or a join following no association at all, such as an entity join. The entity
     * is otherwise duplicated in the results once per matching row, which also breaks the pagination and the count.
     *
     * @param from The root or join to inspect
     * @return {@code true} if the joins may produce duplicated rows, {@code false} otherwise
     */
    protected static boolean hasCollectionJoin(From<?, ?> from) {
        // Read from the Hibernate query tree, since From#getJoins leaves out every join but the attribute ones, and
        // a join made through a treat belongs to that treat rather than to the from it downcasts
        SqmFrom<?, ?> sqmFrom = (SqmFrom<?, ?>) from;
        return sqmFrom.getSqmJoins().stream().anyMatch(EntityQueries::multipliesRows)
                || sqmFrom.getSqmTreats().stream().anyMatch(EntityQueries::hasCollectionJoin);
    }

    private static boolean multipliesRows(SqmJoin<?, ?> join) {
        if (join instanceof SqmAttributeJoin<?, ?> attributeJoin) {
            // A fetch filters nothing, and From#getJoins leaves it out as well
            return !attributeJoin.isFetched() && (attributeJoin.getAttribute().isCollection() || hasCollectionJoin(attributeJoin));
        }
        return true;
    }

    /**
     * Unwraps the Hibernate session a {@link SelectionSpecification} creates its query with, rather than letting its
     * {@code createQuery(EntityManager)} cast the entity manager, which fails on the proxy a container injects
     * implementing nothing but the JPA interface, such as the transaction scoped entity manager of WildFly.
     *
     * @param context The repository the query is written for
     * @return The session behind the entity manager of the repository
     */
    private static Session sessionOf(RepositoryContext<?> context) {
        return context.entityManager().unwrap(Session.class);
    }

    /**
     * Builds the selection query matching the given restriction and additional criteria, ordered by the requested
     * criteria or by the default ones.
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
                // A restriction moved into the semi join must not join the root a second time
                .restrict(restriction == null || semiJoined ? unrestricted() : restriction)
                .augment((criteriaBuilder, query, root) -> {
                    if (semiJoined) {
                        restrict(criteriaBuilder, query, semiJoin(criteriaBuilder, query, root, restriction, criteria));
                    } else if (criteria != null) {
                        restrict(criteriaBuilder, query, criteria.toPredicate(criteriaBuilder, query, root));
                    }
                    query.orderBy(ordering.buildOrders(context, root, sort));
                })
                .createQuery(sessionOf(context));
    }

    /**
     * Checks whether the given restriction and criteria join what may duplicate the root entity, see
     * {@link #hasCollectionJoin(From)}.
     * <p>
     * What they join is only known once they are applied, so they are applied to a throwaway root, which issues
     * no query.
     *
     * @param context     The repository the query is written for
     * @param restriction The restriction to inspect, or {@code null}
     * @param criteria    The criteria to inspect, or {@code null}
     * @return {@code true} if applying them to a root would duplicate it, {@code false} otherwise
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
     * Builds the predicate keeping the entities matching the given restriction and criteria as a semi join: both
     * are applied to a correlated {@code exists} subquery, which never duplicates the root entity.
     * <p>
     * A {@code distinct} would not be portable: a {@code select distinct} may only be ordered by expressions of its
     * select list, which PostgreSQL and Oracle enforce as soon as the ordering reaches a joined attribute.
     *
     * @param criteriaBuilder The builder to use
     * @param query           The query being built, to create the subquery from
     * @param root            The root entity of the query
     * @param restriction     The restriction to apply, or {@code null}
     * @param criteria        The criteria to apply, or {@code null}
     * @return The corresponding predicate
     */
    protected Predicate semiJoin(CriteriaBuilder criteriaBuilder, CommonAbstractCriteria query, Root<E> root, @Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
        Subquery<Integer> matching = query.subquery(Integer.class);
        Root<E> matched = matching.from(entityType);

        // Comparing the two roots as entities correlates them on the identifier, whatever it is made of
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
     * being possibly absent, so that every query but the paginated search combines them the same way.
     *
     * @param criteriaBuilder The builder to use
     * @param query           The query being built, to create the subqueries of the criteria from
     * @param root            The root entity of the query
     * @param restriction     The restriction to apply, or {@code null}
     * @param criteria        The additional criteria to apply, or {@code null}
     * @return The corresponding predicate, or {@code null} when neither is given
     */
    protected @Nullable Predicate toPredicate(CriteriaBuilder criteriaBuilder, CommonAbstractCriteria query, Root<E> root, @Nullable Restriction<? super E> restriction, @Nullable Criteria<E> criteria) {
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
            // Counted from the same query, before the pagination is applied
            long totalItems = query.getResultCount();

            // An offset overflowing an int is rejected by the providers, and such a page is empty anyway
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
     * The results are ordered by the identifier of the related type, the ordering rules of the repository only
     * describing the entity it manages. Order them on business attributes by writing the query in the repository
     * managing the related type.
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
                .augment((criteriaBuilder, query, root) -> query.orderBy(
                        relatedOrdering
                                .getIdPaths(context.entityManager(), root)
                                .map(criteriaBuilder::asc)
                                .toList()))
                .createQuery(sessionOf(context))
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
     * Unlike {@link #count(RepositoryContext, Restriction, Criteria)}, the database stops at the first matching row,
     * and only a literal is selected, so no entity is hydrated.
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

        // Left unordered, and listed since getSingleResult() throws when nothing matches
        return !context.entityManager()
                .createQuery(query)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    /**
     * Deletes every entity matching the given restriction and additional criteria, in a single statement.
     * <p>
     * This is a bulk deletion: it does not cascade, does not honour {@code orphanRemoval}, does not run the
     * {@code @PreRemove} callbacks and leaves the already loaded entities in the persistence context. It cannot
     * join an association either, so restrict on the attributes of the entity itself, or use a subquery such as
     * {@link Criteria#exists(Class, String, java.util.function.BiFunction)}.
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

        return context.entityManager()
                .createQuery(delete)
                .executeUpdate();
    }

    /**
     * Gets the first entity matching the given restriction and additional criteria, following the requested
     * ordering.
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
     * This is how the next row to process is claimed: the ordering makes the choice deterministic, and the lock
     * keeps a concurrent transaction from claiming the same row. Order such a claim on attributes of the entity
     * itself, since a nested property is navigated with a left join, and PostgreSQL, among others, refuses to lock
     * the nullable side of an outer join.
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
        // Listed rather than streamed, a result stream having to be closed by the caller
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

        // Refused before a single row is read, see EntityOrdering#requireSeekable
        resolvedSort.criteria().forEach(criterion -> ordering.requireSeekable(context, criterion.property()));

        CursorPosition position = Cursors.position(context.cursorCodec(), cursor, resolvedSort);
        Sort direction = Cursors.direction(resolvedSort, position);

        CriteriaBuilder criteriaBuilder = context.entityManager().getCriteriaBuilder();
        CriteriaQuery<Tuple> query = criteriaBuilder.createTupleQuery();
        Root<E> root = query.from(entityType);

        // Semi joined when needed, a duplicated boundary row silently shortening the page
        Predicate predicate = joinsCollection(context, restriction, criteria)
                ? semiJoin(criteriaBuilder, query, root, restriction, criteria)
                : toPredicate(criteriaBuilder, query, root, restriction, criteria);
        if (position != null) {
            predicate = and(criteriaBuilder, predicate, Keysets.seek(criteriaBuilder, root, direction, position.values(), context.cursorKeyCodec()));
        }
        if (predicate != null) {
            query.where(predicate);
        }

        Keysets.selectAlongside(criteriaBuilder, query, root, resolvedSort);
        query.orderBy(Keysets.toOrders(criteriaBuilder, root, direction));

        List<Tuple> rows = context.entityManager()
                .createQuery(query)
                .setMaxResults(cursor.limit())
                .getResultList();

        return Cursors.toResult(
                context.cursorCodec(),
                rows.stream().map(row -> row.get(0, entityType)).toList(),
                rows.stream().<Supplier<List<String>>>map(row -> () -> Keysets.selectedValuesOf(row, resolvedSort, context.cursorKeyCodec())).toList(),
                cursor,
                resolvedSort,
                position);
    }

}
