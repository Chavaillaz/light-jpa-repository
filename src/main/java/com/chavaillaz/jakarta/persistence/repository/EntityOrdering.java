package com.chavaillaz.jakarta.persistence.repository;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import cz.jirutka.rsql.parser.ast.Node;
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
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;

/**
 * Ordering rules of a repository, resolving the properties exposed by the API into entity attributes and building
 * the ordering of the queries; see {@link Sort} for why the identifier of the entity is always appended.
 * <p>
 * The default ordering and the searchable properties are provided by the owning repository, so that they stay
 * overridable by its subclasses.
 *
 * @param <E> The type of the managed entity
 */
public class EntityOrdering<E> {

    /**
     * The entity manager giving access to the criteria builder and to the metamodel.
     */
    protected final EntityManager entityManager;

    /**
     * The type of the managed entity.
     */
    protected final Class<E> entityType;

    /**
     * The provider of the default ordering of the repository.
     */
    protected final BiFunction<CriteriaBuilder, Root<E>, List<Order>> defaultOrders;

    /**
     * The provider of the properties the API consumers are allowed to sort and filter on.
     */
    protected final Supplier<Map<String, String>> searchableProperties;

    /**
     * Creates the ordering rules of a repository.
     *
     * @param entityManager        The entity manager to use
     * @param entityType           The type of the managed entity
     * @param defaultOrders        The provider of the default ordering of the repository
     * @param searchableProperties The provider of the properties that can be sorted and filtered on
     */
    public EntityOrdering(
            EntityManager entityManager,
            Class<E> entityType,
            BiFunction<CriteriaBuilder, Root<E>, List<Order>> defaultOrders,
            Supplier<Map<String, String>> searchableProperties) {
        this.entityManager = entityManager;
        this.entityType = entityType;
        this.defaultOrders = defaultOrders;
        this.searchableProperties = searchableProperties;
    }

    /**
     * Gets the dotted attribute path of the given expression.
     *
     * @param expression The expression to name
     * @return The corresponding property path
     *
     * @throws IllegalArgumentException if the expression is not a plain attribute path, a computed ordering such
     *                                  as {@code lower(name)} being unusable as a cursor key since its value
     *                                  cannot be read back from the returned entity
     */
    public static String nameOf(Expression<?> expression) {
        Deque<String> names = new ArrayDeque<>();
        for (Path<?> path = asPath(expression); path.getParentPath() != null; path = path.getParentPath()) {
            if (!(path.getModel() instanceof Attribute<?, ?> attribute)) {
                throw new IllegalArgumentException("Cannot use the expression " + expression + " as a cursor key");
            }
            names.addFirst(attribute.getName());
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
     * the identifier being appended so that the ordering is unique, whatever the pagination. Nothing is applied
     * when no ordering is requested and the query already carries one, so that an ordering a custom RSQL visitor
     * already built on the query is not silently overridden.
     *
     * @param query    The search query to order
     * @param root     The root entity of the query
     * @param pageable The requested page and ordering
     * @see Pageable
     */
    public void applyOrder(CriteriaQuery<E> query, Root<E> root, Pageable pageable) {
        Sort sort = pageable.sort();
        boolean sorted = !sort.isEmpty();
        if (!sorted && !query.getOrderList().isEmpty()) {
            return;
        }
        query.orderBy(buildOrders(root, sorted ? sort : Sort.NONE));
    }

    /**
     * Builds the ordering of a query, made of the requested criteria, or of the default ones when none is
     * requested, followed by the identifier of the entity so that the resulting ordering is always unique and thus
     * stable.
     *
     * @param root The root entity of the query
     * @param sort The requested ordering
     * @return The ordering to apply
     */
    public List<Order> buildOrders(Root<E> root, Sort sort) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();

        List<Order> orders = new ArrayList<>(sort == null || sort.isEmpty()
                ? defaultOrders.apply(criteriaBuilder, root)
                : sort.criteria().stream().map(criterion -> toOrder(criteriaBuilder, root, criterion)).toList());

        // The identifier is only appended when it is not already part of the ordering, so that a repository
        // ordering on it explicitly, in descending order for instance, is not overridden
        Set<Expression<?>> ordered = orders.stream().map(Order::getExpression).collect(toSet());
        getIdPaths(root)
                .filter(path -> !ordered.contains(path))
                .map(criteriaBuilder::asc)
                .forEach(orders::add);
        return orders;
    }

    /**
     * Converts a requested criterion into a criteria ordering.
     *
     * @param criteriaBuilder The builder to use to create the ordering
     * @param root            The root entity of the query
     * @param criterion       The requested criterion
     * @return The corresponding ordering
     *
     * @throws IllegalArgumentException if the criterion refers to an unknown property or to a collection
     */
    public Order toOrder(CriteriaBuilder criteriaBuilder, Root<E> root, SortCriterion criterion) {
        Path<?> path = resolvePath(root, criterion.property());
        return criterion.ascending() ? criteriaBuilder.asc(path) : criteriaBuilder.desc(path);
    }

    /**
     * Resolves a property exposed by the API into the path of the corresponding entity attribute, be it for
     * sorting or, through {@link RsqlQueries#resolveProperties(Node)}, for RSQL filtering.
     * <p>
     * When the repository declares searchable properties, only those are accepted, which both restricts the
     * attributes the API consumers can sort or filter on and decouples the public naming from the entity one.
     * Otherwise, any attribute of the entity is accepted, the path being validated against the metamodel by the
     * caller.
     * <p>
     * An entity attribute path already resolved, such as one built by {@link SortCriterion#asc(Attribute[])} from
     * the static metamodel, is also accepted as is, whether or not it is declared as the target of a searchable
     * property: it is compile time safe by construction rather than API consumer supplied, and it can only reach
     * an attribute a declared property already exposes under its own alias, so accepting it widens no restriction.
     *
     * @param property The property to resolve
     * @return The path of the corresponding attribute
     *
     * @throws IllegalArgumentException if the property is neither searchable nor an already resolved path
     */
    public String resolveProperty(String property) {
        Map<String, String> properties = searchableProperties.get();

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
     * {@link SortCriterion#NESTING_SEPARATOR}.
     * <p>
     * The property is first {@link #resolveProperty(String) resolved} against the searchable properties of the
     * repository, then validated against the metamodel, as it usually comes from the API consumers.
     *
     * @param root     The root entity of the query
     * @param property The property to resolve
     * @return The corresponding path
     *
     * @throws IllegalArgumentException if the property is unknown or refers to a collection
     */
    public Path<?> resolvePath(Root<E> root, String property) {
        Path<?> path = root;
        for (String attribute : resolveProperty(property).split("\\.")) {
            try {
                path = path.get(attribute);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Cannot sort on the unknown property " + property, e);
            }
            if (path.getModel() instanceof PluralAttribute) {
                // Ordering on a to-many association duplicates the rows and is rejected by the distinct queries
                throw new IllegalArgumentException("Cannot sort on the collection property " + property);
            }
        }
        return path;
    }

    /**
     * Resolves the complete ordering of a query into entity attribute paths: the requested criteria, or the
     * default ones when none is requested, followed by the identifier so that the ordering is unique.
     * <p>
     * The returned ordering is what both the {@code ORDER BY} clause and the seek predicate are built from, so that
     * they can never drift apart and silently return wrong pages. The criteria are expressed as entity attribute
     * paths, already validated by {@link #resolvePath(Root, String)}.
     *
     * @param sort The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The complete ordering, made of path based criteria only
     *
     * @throws IllegalArgumentException if a criterion refers to an unknown property, to a collection, or if the
     *                                  default ordering of the repository is not expressed with plain paths
     */
    public Sort resolveSort(Sort sort) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        // A throwaway root is enough: only the resolved names and directions are kept
        Root<E> root = criteriaBuilder.createQuery(entityType).from(entityType);

        List<SortCriterion> criteria = new ArrayList<>();
        if (sort == null || sort.isEmpty()) {
            for (Order order : defaultOrders.apply(criteriaBuilder, root)) {
                criteria.add(new SortCriterion(nameOf(order.getExpression()), order.isAscending()));
            }
        } else {
            for (SortCriterion criterion : sort.criteria()) {
                criteria.add(new SortCriterion(nameOf(resolvePath(root, criterion.property())), criterion.ascending()));
            }
        }

        // The identifier is only appended when it is not already part of the ordering
        Set<String> ordered = criteria.stream().map(SortCriterion::property).collect(toSet());
        getIdPaths(root)
                .map(EntityOrdering::nameOf)
                .filter(property -> !ordered.contains(property))
                .map(SortCriterion::asc)
                .forEach(criteria::add);
        return new Sort(criteria);
    }

    /**
     * Gets the paths to the attributes composing the identifier of the managed entity, supporting the simple,
     * embedded and composite identifiers.
     *
     * @param root The root entity of the query
     * @return The paths to order on
     */
    public Stream<Path<?>> getIdPaths(Root<E> root) {
        EntityType<E> entityMetamodel = entityManager.getMetamodel().entity(entityType);

        // Composite identifier declared with an identifier class, spread over several attributes
        if (!entityMetamodel.hasSingleIdAttribute()) {
            return sortedByName(entityMetamodel.getIdClassAttributes()).map(attribute -> root.get(attribute.getName()));
        }

        SingularAttribute<? super E, ?> idAttribute = entityMetamodel.getId(entityMetamodel.getIdType().getJavaType());
        Path<?> idPath = root.get(idAttribute);

        // Embedded identifier, ordered on each of its components
        if (idAttribute.getType() instanceof EmbeddableType<?> embeddable) {
            return sortedByName(embeddable.getSingularAttributes()).map(attribute -> idPath.get(attribute.getName()));
        }

        return Stream.of(idPath);
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
