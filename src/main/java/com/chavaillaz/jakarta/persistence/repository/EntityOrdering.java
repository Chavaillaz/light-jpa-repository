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
 * The default ordering and the searchable properties are provided by the repository the query is written for,
 * through the {@link RepositoryContext} of each call, so that they stay overridable by its subclasses and that
 * one instance can be shared by every repository over the same entity.
 *
 * @param <E> The type of the managed entity
 */
public class EntityOrdering<E> {

    /**
     * The ordering rules, held per entity type rather than per repository, nothing of a repository being kept
     * here. A {@link ClassValue} is used rather than a plain map keyed by the class, so that the cache cannot
     * hold a class, and therefore its class loader, alive after a redeployment.
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
     * Gets the ordering rules of the given entity type.
     * <p>
     * The instance is shared by every repository over that entity, which is possible precisely because it holds
     * nothing of any of them: the entity manager and the hooks travel with the {@link RepositoryContext} of each
     * call, so two repositories declaring different searchable properties over the same entity still each get
     * their own rules applied.
     *
     * @param <E>        The type of the managed entity
     * @param entityType The type of the managed entity
     * @return The corresponding ordering rules
     */
    @SuppressWarnings("unchecked")
    public static <E> EntityOrdering<E> of(Class<E> entityType) {
        // A ClassValue erases the link between the key and the value it computes from it, so the cast cannot be
        // proven by the compiler; it holds by construction, computeValue building the rules of that very class
        return (EntityOrdering<E>) ORDERINGS.get(entityType);
    }

    /**
     * Gets the dotted attribute path of the given expression.
     *
     * @param expression The expression to name
     * @return The corresponding property path
     * @throws IllegalArgumentException if the expression is not a plain attribute path, a computed ordering such
     *                                  as {@code lower(name)} being unusable as a cursor key since its value
     *                                  cannot be read back from the returned entity, or if it names no attribute
     *                                  at all, being the root entity itself
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
            // The expression is the root itself, which has no parent path to walk up and therefore names nothing;
            // returning the empty path would surface much later, as an invalid sort property with no name in it
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
     * the identifier being appended so that the ordering is unique, whatever the pagination. Nothing is applied
     * when no ordering is requested and the query already carries one, so that an ordering a custom RSQL visitor
     * already built on the query is not silently overridden.
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
     * requested, followed by the identifier of the entity so that the resulting ordering is always unique and thus
     * stable.
     * <p>
     * An expression is only compared once, the first criterion reaching it winning, exactly as
     * {@link #resolveSort(RepositoryContext, Sort)} keeps a property once: a second comparison cannot discriminate
     * two rows the first one already left equal. The ordering comes from the API consumers, so without it a
     * {@code sort=name,name,name,...} grows the {@code ORDER BY} clause of every query as far as the consumer
     * cares to repeat itself, and two public properties aliasing the same attribute silently compare it twice.
     *
     * @param context The repository the query is written for
     * @param root    The root entity of the query
     * @param sort    The requested ordering, {@link Sort#NONE} or {@code null} to apply the default ordering of
     *                the repository
     * @return The ordering to apply
     */
    public List<Order> buildOrders(RepositoryContext<E> context, Root<E> root, @Nullable Sort sort) {
        CriteriaBuilder criteriaBuilder = context.entityManager().getCriteriaBuilder();

        // Keyed by the expression the criterion resolves to, so that the first one comparing it wins and the
        // insertion order, which is the order of precedence, is preserved
        Map<Expression<?>, Order> orders = new LinkedHashMap<>();
        if (sort == null || sort.isEmpty()) {
            context.defaultOrders(criteriaBuilder, root).forEach(order -> keep(orders, order));
        } else {
            sort.criteria().forEach(criterion -> keep(orders, toOrder(context, criteriaBuilder, root, criterion)));
        }

        // The identifier is only appended when it is not already part of the ordering, so that a repository
        // ordering on it explicitly, in descending order for instance, is not overridden
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
     * Resolves a property exposed by the API into the path of the corresponding entity attribute, be it for
     * sorting or for a dynamic filter expression built on top of this class, such as the RSQL support of the
     * sibling {@code rsql-jpa-repository} artifact.
     * <p>
     * When the repository declares searchable properties, only those are accepted, which both restricts the
     * attributes the API consumers can sort or filter on and decouples the public naming from the entity one.
     * Otherwise, any attribute of the entity is accepted, the path being validated against the metamodel by the
     * caller.
     * <p>
     * An entity attribute path already resolved, such as one built by {@link SortCriterion#asc(Attribute[])} from
     * the static metamodel, is accepted as is under whatever public alias a searchable property declares it: it is
     * compile time safe by construction rather than API consumer supplied, and it reaches an attribute a declared
     * property already exposes, so accepting it widens no restriction. An attribute no property declares stays
     * refused, the metamodel proving that it exists on the entity and not that the repository exposes it.
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
     * {@link SortCriterion#NESTING_SEPARATOR}.
     * <p>
     * The property is first {@link #resolveProperty(RepositoryContext, String) resolved} against the searchable
     * properties of the repository, then validated against the metamodel, as it usually comes from the API
     * consumers.
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
                // A nested property navigates its association with a left join, so that an entity whose
                // association is null keeps being returned and counted, see AttributePaths#step
                path = AttributePaths.step(path, attributes[index], index < attributes.length - 1);
            } catch (IllegalArgumentException | IllegalStateException e) {
                // Both are caught because that is what the contract of Path#get raises: an unknown attribute is an
                // IllegalArgumentException, while dereferencing one that is already terminal, which a property such
                // as name.length is, is an IllegalStateException. The property comes from the API consumers, so the
                // second one must not escape as the runtime failure it would otherwise be
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
     * The returned ordering is what both the {@code ORDER BY} clause and the seek predicate are built from, so that
     * they can never drift apart and silently return wrong pages. The criteria are expressed as entity attribute
     * paths, already validated by {@link #resolvePath(RepositoryContext, Root, String)}.
     * <p>
     * A property is only kept once, the first occurrence winning as the database itself does: a second comparison
     * on a property cannot discriminate two rows the first one already left equal, and each redundant key would
     * still be selected, sought on and carried within every cursor token. Two public properties aliasing the very
     * same attribute therefore collapse into one, whichever names them.
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
        // A throwaway root is enough: only the resolved names and directions are kept
        Root<E> root = criteriaBuilder.createQuery(entityType).from(entityType);

        // Keyed by the resolved attribute path, so that the first criterion naming it wins and the insertion
        // order, which is the order of precedence, is preserved
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

        // The identifier is appended so that the ordering is unique, unless it already is part of it, in which
        // case the direction the repository or the consumer asked for is the one that stands
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
     * Checks that a property can be sought on, which a nullable one cannot: a seek predicate compares the key of
     * a row against the key of the boundary one, and a comparison against {@code null} is never true, so no row
     * whose key is {@code null} is ever returned once a page has been left behind.
     * <p>
     * Whether that shows is left to the database, which is why it is caught here rather than when a token is
     * built. A {@code null} key sorting first, as H2 and MySQL place it in an ascending order, lands on the first
     * page, and the token issued for it is {@link CursorValues#format rejected} as soon as the consumer asks for
     * the next one. A {@code null} key sorting last, which is what PostgreSQL and Oracle do in that very same
     * ascending order, and what every one of them does in a descending one, is instead never reached: the walk
     * ends on the last non null key, reporting no following page, and every row behind it is silently dropped.
     * The same ordering therefore fails loudly on one database and truncates the results on another, so the
     * attribute is refused on all of them, before a single row is read.
     * <p>
     * The nullability is the one the mapping declares, an attribute being optional unless a
     * {@code @Column(nullable = false)} or an {@code optional = false} says otherwise. Each attribute of a nested
     * path is checked, an optional association making the key of the entities having none {@code null} just as an
     * optional column does. An embeddable is skipped, being no key of its own: its nullability lives in the
     * components the rest of the path walks through, which are checked as any other attribute is.
     * <p>
     * Only the cursor queries check this; the offset ones order on a nullable attribute perfectly well, the
     * database placing its {@code null} keys wherever it does and the count being derived from that very same
     * query.
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
     * Gets the paths to the attributes composing the identifier of the managed entity, taking the entity manager
     * on its own rather than the whole context of a repository.
     * <p>
     * The identifier is the one part of an ordering a repository has no say over: no hook of it is read here,
     * only the metamodel. This is therefore what the queries over a type no repository is written for are ordered
     * with, such as the
     * {@link EntityQueries#search(RepositoryContext, Class, org.hibernate.query.restriction.Restriction) related entity search},
     * whose rows would otherwise come back in whatever order the database happened to produce.
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

        // Embedded identifier, ordered on each of its components, navigated through the very same step a requested
        // ordering on one of them navigates it with, so that both resolve to the same paths and the identifier is
        // appended once rather than twice, in a direction the repository may not have asked for
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
