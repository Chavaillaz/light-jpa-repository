package com.chavaillaz.jakarta.persistence.repository;

import static java.util.Comparator.comparing;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EmbeddableType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * Ordering rules of an entity type, resolving the properties exposed by the API into entity attributes and
 * building the ordering of the queries; see {@link Sort} for why the identifier of the entity is always appended.
 * <p>
 * The default ordering and the searchable properties come from the {@link RepositoryContext} of each call, so that
 * they stay overridable by the repositories and one instance can be shared by every repository over the entity.
 *
 * @param <E> The type of the managed entity
 */
public class EntityOrdering<E> {

    /**
     * The ordering rules of each entity type, held in a {@link ClassValue} rather than in a map keyed by the
     * class, so that the cache cannot keep a class loader alive after a redeployment.
     */
    private static final ClassValue<EntityOrdering<?>> ORDERINGS = new ClassValue<>() {

        @Override
        protected EntityOrdering<?> computeValue(Class<?> type) {
            return new EntityOrdering<>(type);
        }

    };

    /**
     * The type of the managed entity.
     */
    protected final Class<E> entityType;

    /**
     * Creates the ordering rules of an entity type.
     * <p>
     * Prefer {@link #of(Class)}, which shares one instance per entity type.
     *
     * @param entityType The type of the managed entity
     */
    public EntityOrdering(Class<E> entityType) {
        this.entityType = entityType;
    }

    /**
     * Gets the ordering rules of the given entity type, shared by every repository over that entity.
     *
     * @param <E>        The type of the managed entity
     * @param entityType The type of the managed entity
     * @return The corresponding ordering rules
     */
    @SuppressWarnings("unchecked")
    public static <E> EntityOrdering<E> of(Class<E> entityType) {
        // A ClassValue loses the link between a class and the value computed from it, which holds by construction
        return (EntityOrdering<E>) ORDERINGS.get(entityType);
    }

    /**
     * Gets the dotted attribute path of the given expression.
     *
     * @param expression The expression to name
     * @return The corresponding property path
     * @throws IllegalArgumentException if the expression is not a plain attribute path, such as a computed
     *                                  {@code lower(name)}, or if it names no attribute, being the root entity
     *                                  itself
     */
    public static String nameOf(Expression<?> expression) {
        Deque<String> names = new ArrayDeque<>();
        for (Path<?> path = asPath(expression); path.getParentPath() != null; path = path.getParentPath()) {
            if (!(path.getModel() instanceof Attribute<?, ?> attribute)) {
                throw new IllegalArgumentException("Cannot use the expression " + expression + " as a cursor key");
            }
            names.addFirst(attribute.getName());
        }
        if (names.isEmpty()) {
            // The root has no parent path, and would otherwise surface later as a sort property with no name
            throw new IllegalArgumentException("Cannot order on the entity " + expression + " itself, an attribute is required");
        }
        return String.join(SortCriterion.NESTING_SEPARATOR, names);
    }

    private static Path<?> asPath(Expression<?> expression) {
        if (expression instanceof Path<?> path) {
            return path;
        }
        throw new IllegalArgumentException("Cursor pagination requires an ordering on plain attributes, got " + expression);
    }

    /**
     * Applies the ordering to the given query, the requested criteria taking precedence over the default ones and
     * the identifier being appended so that the ordering is unique. Nothing is applied when no ordering is
     * requested and the query already carries one, such as one an RSQL visitor built.
     *
     * @param context  The repository the query is written for
     * @param query    The search query to order
     * @param root     The root entity of the query
     * @param pageable The requested page and ordering
     * @see Pageable
     */
    public void applyOrder(RepositoryContext<E> context, CriteriaQuery<E> query, Root<E> root, Pageable pageable) {
        Sort sort = pageable.sort();
        boolean sorted = !sort.isEmpty();
        if (!sorted && !query.getOrderList().isEmpty()) {
            return;
        }
        query.orderBy(buildOrders(context, root, sorted ? sort : Sort.NONE));
    }

    /**
     * Builds the ordering of a query, made of the requested criteria, or of the default ones when none is
     * requested, followed by the identifier of the entity so that the ordering is unique and thus stable.
     * <p>
     * An expression is compared once, the first criterion reaching it winning: a second comparison cannot
     * discriminate two rows the first one left equal, and an API consumer repeating a property would otherwise
     * grow the {@code ORDER BY} clause at will.
     *
     * @param context The repository the query is written for
     * @param root    The root entity of the query
     * @param sort    The requested ordering, {@link Sort#NONE} or {@code null} to apply the default ordering of
     *                the repository
     * @return The ordering to apply
     */
    public List<Order> buildOrders(RepositoryContext<E> context, Root<E> root, @Nullable Sort sort) {
        CriteriaBuilder criteriaBuilder = context.entityManager().getCriteriaBuilder();

        // Keyed by the compared expression, in the order of precedence
        Map<Expression<?>, Order> orders = new LinkedHashMap<>();
        if (sort == null || sort.isEmpty()) {
            context.defaultOrders(criteriaBuilder, root).forEach(order -> keep(orders, order));
        } else {
            sort.criteria().forEach(criterion -> keep(orders, toOrder(context, criteriaBuilder, root, criterion)));
        }

        // Appended unless already part of the ordering, so that the direction asked for it stands
        getIdPaths(context, root)
                .map(criteriaBuilder::asc)
                .forEach(order -> keep(orders, order));
        return List.copyOf(orders.values());
    }

    /**
     * Keeps an ordering unless the query already compares its expression.
     *
     * @param orders The orderings resolved so far, keyed by the expression they compare
     * @param order  The ordering to keep
     */
    private static void keep(Map<Expression<?>, Order> orders, Order order) {
        orders.putIfAbsent(order.getExpression(), order);
    }

    /**
     * Converts a requested criterion into a criteria ordering.
     *
     * @param context         The repository the query is written for
     * @param criteriaBuilder The builder to use to create the ordering
     * @param root            The root entity of the query
     * @param criterion       The requested criterion
     * @return The corresponding ordering
     * @throws IllegalArgumentException if the criterion refers to an unknown property or to a collection
     */
    public Order toOrder(RepositoryContext<E> context, CriteriaBuilder criteriaBuilder, Root<E> root, SortCriterion criterion) {
        Path<?> path = resolvePath(context, root, criterion.property());
        return criterion.ascending() ? criteriaBuilder.asc(path) : criteriaBuilder.desc(path);
    }

    /**
     * Resolves a property exposed by the API into the path of the corresponding entity attribute, for sorting as
     * for a dynamic filter built on top, such as the RSQL support of the sibling {@code rsql-jpa-repository}.
     * <p>
     * When the repository declares searchable properties, only those are accepted, along with the attribute paths
     * they target, such as one built from the static metamodel by {@link SortCriterion#asc(Attribute[])}: such a
     * path is reachable through its property anyway, so accepting it widens no restriction. Otherwise, any
     * attribute is accepted, the caller validating it against the metamodel.
     *
     * @param context  The repository the query is written for
     * @param property The property to resolve
     * @return The path of the corresponding attribute
     * @throws IllegalArgumentException if the property is neither searchable nor an already resolved path
     */
    public String resolveProperty(RepositoryContext<E> context, String property) {
        Map<String, String> properties = context.searchableProperties();

        if (properties.isEmpty() || properties.containsValue(property)) {
            return property;
        }

        String path = properties.get(property);
        if (path == null) {
            throw new IllegalArgumentException("Cannot sort or filter on the unknown property " + property);
        }
        return path;
    }

    /**
     * Resolves the path to the given property of the managed entity, a nested property being expressed with
     * {@link SortCriterion#NESTING_SEPARATOR}, once {@link #resolveProperty(RepositoryContext, String) resolved}
     * against the searchable properties and validated against the metamodel.
     *
     * @param context  The repository the query is written for
     * @param root     The root entity of the query
     * @param property The property to resolve
     * @return The corresponding path
     * @throws IllegalArgumentException if the property is unknown or refers to a collection
     */
    public Path<?> resolvePath(RepositoryContext<E> context, Root<E> root, String property) {
        String[] attributes = AttributePaths.split(resolveProperty(context, property));

        Path<?> path = root;
        for (int index = 0; index < attributes.length; index++) {
            try {
                // A nested association is navigated with a left join, see AttributePaths#step
                path = AttributePaths.step(path, attributes[index], index < attributes.length - 1);
            } catch (IllegalArgumentException | IllegalStateException e) {
                // What Path#get raises for an unknown attribute, and for one reached through a basic attribute, such
                // as name.length, both of which an API consumer is free to send
                throw new IllegalArgumentException("Cannot sort on the unknown property " + property, e);
            }
            if (path.getModel() instanceof PluralAttribute) {
                // Ordering on a to-many association would join it, duplicating the entity once per child
                throw new IllegalArgumentException("Cannot sort on the collection property " + property);
            }
        }
        return path;
    }

    /**
     * Resolves the complete ordering of a query into entity attribute paths: the requested criteria, or the
     * default ones when none is requested, followed by the identifier so that the ordering is unique.
     * <p>
     * Both the {@code ORDER BY} clause and the seek predicate of a cursor query are built from it, so that they
     * cannot drift apart. A property is kept once, the first occurrence winning, as
     * {@link #buildOrders(RepositoryContext, Root, Sort)} keeps an expression.
     *
     * @param context The repository the query is written for
     * @param sort    The requested ordering, {@link Sort#NONE} or {@code null} to apply the default ordering of
     *                the repository
     * @return The complete ordering, made of path based criteria only
     * @throws IllegalArgumentException if a criterion refers to an unknown property, to a collection, or if the
     *                                  default ordering of the repository is not expressed with plain paths
     */
    public Sort resolveSort(RepositoryContext<E> context, @Nullable Sort sort) {
        CriteriaBuilder criteriaBuilder = context.entityManager().getCriteriaBuilder();
        // Only the resolved names and directions are kept, so a throwaway root is enough
        Root<E> root = criteriaBuilder.createQuery(entityType).from(entityType);

        // Keyed by the resolved attribute path, in the order of precedence
        Map<String, SortCriterion> criteria = new LinkedHashMap<>();
        if (sort == null || sort.isEmpty()) {
            for (Order order : context.defaultOrders(criteriaBuilder, root)) {
                keep(criteria, new SortCriterion(nameOf(order.getExpression()), order.isAscending()));
            }
        } else {
            for (SortCriterion criterion : sort.criteria()) {
                keep(criteria, new SortCriterion(nameOf(resolvePath(context, root, criterion.property())), criterion.ascending()));
            }
        }

        // Appended unless already part of the ordering, so that the direction asked for it stands
        getIdPaths(context, root)
                .map(EntityOrdering::nameOf)
                .map(SortCriterion::asc)
                .forEach(criterion -> keep(criteria, criterion));
        return new Sort(List.copyOf(criteria.values()));
    }

    /**
     * Keeps a criterion unless the ordering already compares its property.
     *
     * @param criteria  The criteria resolved so far, keyed by their property
     * @param criterion The criterion to keep
     */
    private static void keep(Map<String, SortCriterion> criteria, SortCriterion criterion) {
        criteria.putIfAbsent(criterion.property(), criterion);
    }

    /**
     * Checks that a property can be sought on, which a nullable one cannot: a comparison against {@code null} is
     * never true, so no row whose key is {@code null} is returned once a page has been left behind.
     * <p>
     * How that shows depends on where the database sorts the nulls: sorted first, the first page lands on such a
     * row and the token issued for it is refused; sorted last, the walk silently ends before them. The attribute
     * is therefore refused before a single row is read, as nullable as the mapping declares it, and so is each
     * attribute of a nested path, an optional association leaving the key {@code null} as an optional column
     * does. An embeddable is skipped in favour of its components, being no key of its own.
     * <p>
     * Only the cursor queries check this, an offset query ordering on a nullable attribute perfectly well.
     *
     * @param context  The repository the query is written for
     * @param property The resolved entity attribute path to check
     * @throws IllegalArgumentException if any attribute of the path is nullable
     */
    protected void requireSeekable(RepositoryContext<E> context, String property) {
        ManagedType<?> owner = context.entityManager().getMetamodel().entity(entityType);

        for (String attribute : AttributePaths.split(property)) {
            SingularAttribute<?, ?> singular = owner.getSingularAttribute(attribute);
            Type<?> type = singular.getType();

            if (!(type instanceof EmbeddableType<?>) && singular.isOptional()) {
                throw new IllegalArgumentException("Cannot build a cursor on the nullable property %s: a cursor key must be non nullable".formatted(property));
            }
            if (type instanceof ManagedType<?> managed) {
                owner = managed;
            }
        }
    }

    /**
     * Gets the paths to the attributes composing the identifier of the managed entity, supporting the simple,
     * embedded and composite identifiers.
     *
     * @param context The repository the query is written for
     * @param root    The root entity of the query
     * @return The paths to order on
     */
    public Stream<Path<?>> getIdPaths(RepositoryContext<E> context, Root<E> root) {
        return getIdPaths(context.entityManager(), root);
    }

    /**
     * Gets the paths to the attributes composing the identifier of the managed entity, reading nothing but the
     * metamodel, for the queries over a type no repository is written for, such as the
     * {@link EntityQueries#search(RepositoryContext, Class, org.hibernate.query.restriction.Restriction) related entity search}.
     *
     * @param entityManager The entity manager holding the metamodel the identifier is read from
     * @param root          The root entity of the query
     * @return The paths to order on
     */
    public Stream<Path<?>> getIdPaths(EntityManager entityManager, Root<E> root) {
        EntityType<E> entityMetamodel = entityManager.getMetamodel().entity(entityType);

        // Composite identifier declared with an identifier class, spread over several attributes
        if (!entityMetamodel.hasSingleIdAttribute()) {
            return sortedByName(entityMetamodel.getIdClassAttributes()).map(attribute -> root.get(attribute.getName()));
        }

        SingularAttribute<? super E, ?> idAttribute = entityMetamodel.getId(entityMetamodel.getIdType().getJavaType());

        // Embedded identifier, navigated with the step a requested ordering on one of its components takes, so that
        // both resolve to the same paths and the identifier is not appended a second time
        if (idAttribute.getType() instanceof EmbeddableType<?> embeddable) {
            Path<?> idPath = AttributePaths.step(root, idAttribute.getName(), true);
            return sortedByName(embeddable.getSingularAttributes()).map(attribute -> idPath.get(attribute.getName()));
        }

        return Stream.of(root.get(idAttribute));
    }

    /**
     * Sorts the given attributes by name, the metamodel returning them in an undefined order.
     *
     * @param <A>        The type of the attributes
     * @param attributes The attributes to sort
     * @return The sorted attributes
     */
    public <A extends SingularAttribute<?, ?>> Stream<A> sortedByName(Set<A> attributes) {
        return attributes.stream().sorted(comparing(Attribute::getName));
    }

}
