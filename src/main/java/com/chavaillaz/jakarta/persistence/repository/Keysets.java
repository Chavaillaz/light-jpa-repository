package com.chavaillaz.jakarta.persistence.repository;

import static jakarta.persistence.criteria.JoinType.LEFT;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.metamodel.SingularAttribute;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.hibernate.Hibernate;
import org.jspecify.annotations.Nullable;

/**
 * Keyset helpers shared by the query collaborators, building the {@code ORDER BY} clause, the seek predicate and
 * the boundary keys of a cursor query from a {@link EntityOrdering#resolveSort(Sort) resolved ordering}.
 * <p>
 * The predicate is the lexicographic comparison of the ordering keys, so that a row is returned as soon as it
 * comes strictly after the boundary row in the very same ordering, emitted as a flat disjunction of conjunctions,
 * one per ordering key, each one growing by the equality of one more leading key:
 * <pre>(k1 &gt; v1) OR (k1 = v1 AND k2 &lt; v2) OR (k1 = v1 AND k2 = v2 AND id &gt; v3)</pre>
 * A row value comparison {@code (k1, k2, id) > (v1, v2, v3)} would be shorter and better optimized, but it is
 * neither expressible with the criteria API nor supported by every database, and it only applies when all the
 * keys share the same direction.
 */
public final class Keysets {

    /**
     * Compiled form of the {@link SortCriterion#NESTING_SEPARATOR}, quoted so that a separator holding a regex
     * metacharacter — which the dot is — splits literally, and compiled once since every cursor query resolves
     * one path per ordering key.
     */
    private static final Pattern NESTING_PATTERN = Pattern.compile(Pattern.quote(SortCriterion.NESTING_SEPARATOR));

    /**
     * Accessors of the cursor keys, resolved once per entity class and attribute rather than at every boundary
     * row of every page. A {@link ClassValue} is used rather than a plain map keyed by the class, so that the
     * cache cannot hold a class, and therefore its class loader, alive after a redeployment.
     */
    private static final ClassValue<Map<String, AccessibleObject>> ACCESSORS = new ClassValue<>() {

        @Override
        protected Map<String, AccessibleObject> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }

    };

    private Keysets() {
        // This utility class should not be instantiated
    }

    /**
     * Builds the ordering of a cursor query.
     *
     * @param criteriaBuilder The builder to use
     * @param root            The root entity of the query
     * @param sort            The resolved ordering, already reversed when walking backwards
     * @return The ordering to apply
     */
    public static List<Order> toOrders(CriteriaBuilder criteriaBuilder, From<?, ?> root, Sort sort) {
        return sort.criteria().stream()
                .map(criterion -> criterion.ascending()
                        ? criteriaBuilder.asc(path(root, criterion.property()))
                        : criteriaBuilder.desc(path(root, criterion.property())))
                .toList();
    }

    /**
     * Builds the predicate keeping only the rows coming strictly after the boundary one.
     *
     * @param criteriaBuilder The builder to use
     * @param root            The root entity of the query
     * @param sort            The resolved ordering, already reversed when walking backwards
     * @param values          The textual keys of the boundary row, in the ordering order
     * @param codec           The codec parsing the textual keys back into the Java type of their attribute
     * @return The corresponding predicate
     * @throws IllegalArgumentException if the keys do not match the ordering, or if a key type is not supported
     *                                  by the given codec
     */
    public static Predicate seek(CriteriaBuilder criteriaBuilder, From<?, ?> root, Sort sort, List<String> values, CursorKeyCodec codec) {
        List<SortCriterion> criteria = sort.criteria();
        if (criteria.size() != values.size()) {
            throw new IllegalArgumentException("The cursor does not match the requested ordering");
        }

        List<Predicate> disjunction = new ArrayList<>();
        for (int index = 0; index < criteria.size(); index++) {
            List<Predicate> conjunction = new ArrayList<>();
            for (int previous = 0; previous < index; previous++) {
                conjunction.add(equal(criteriaBuilder, root, criteria.get(previous), values.get(previous), codec));
            }
            conjunction.add(after(criteriaBuilder, root, criteria.get(index), values.get(index), codec));
            disjunction.add(criteriaBuilder.and(conjunction.toArray(Predicate[]::new)));
        }
        return criteriaBuilder.or(disjunction.toArray(Predicate[]::new));
    }

    /**
     * Reads the ordering keys of the given entity, which become the position of the cursor.
     * <p>
     * This is the fallback of the cursor queries, which select the keys alongside the entity so that the token
     * carries the values the database ordered on: an accessor is free to return something else than the column
     * it maps, and the seek predicate would then be expressed in terms the {@code order by} clause never used.
     *
     * @param entity The entity of the boundary row of the page
     * @param sort   The resolved ordering
     * @param codec  The codec formatting each key into its textual representation
     * @return The textual keys, in the ordering order
     * @throws IllegalArgumentException if one of the keys is {@code null} or cannot be read on the entity, a
     *                                  nullable attribute being unusable as a cursor key since the databases do
     *                                  not agree on where the nulls sort
     * @throws IllegalStateException    if the accessor of a cursor key cannot be invoked
     */
    public static List<String> valuesOf(Object entity, Sort sort, CursorKeyCodec codec) {
        return sort.criteria().stream()
                .map(criterion -> codec.format(criterion.property(), read(entity, criterion.property())))
                .toList();
    }

    /**
     * Resolves the path of an already validated entity attribute path, a nested one being expressed with the
     * {@link SortCriterion#NESTING_SEPARATOR}.
     * <p>
     * The type of the resolved attribute is inferred from the call site, as {@link Path#get(String)} itself does:
     * it cannot be checked at compile time since the path is only known as a string, and it is guaranteed instead
     * by {@link EntityOrdering}, which validates every ordering property against the metamodel before a cursor
     * query is built.
     *
     * @param <Y>      The type of the resolved attribute, inferred from the call site
     * @param from     The root or join to resolve the path against
     * @param property The entity attribute path, nested parts being separated by the nesting separator
     * @return The corresponding path
     */
    @SuppressWarnings("unchecked")
    public static <Y> Path<Y> path(From<?, ?> from, String property) {
        String[] attributes = split(property);

        Path<?> path = from;
        for (int index = 0; index < attributes.length; index++) {
            path = step(path, attributes[index], index < attributes.length - 1);
        }
        return (Path<Y>) path;
    }

    /**
     * Resolves a single attribute of a nested path, navigating an intermediate association with a reused left
     * join rather than with the implicit inner join {@link Path#get(String)} produces.
     * <p>
     * An inner join silently drops the entities whose association is {@code null}, which is wrong for an
     * ordering: the row disappears from the results while it still is counted by the very same query, the count
     * being derived without the {@code order by} clause. A left join keeps it, the database then placing its
     * {@code null} key first or last depending on its own null ordering.
     * <p>
     * The joins are reused rather than created anew for each key, so that ordering on two attributes of the same
     * association, or ordering and seeking on the same one, does not join it twice.
     *
     * @param parent       The path to resolve the attribute against
     * @param attribute    The name of the attribute to resolve
     * @param intermediate {@code true} when the attribute is not the last one of the path, and is therefore
     *                     navigated rather than read
     * @return The corresponding path, a left join for an intermediate association
     * @throws IllegalArgumentException if the attribute does not exist on the parent path
     */
    static Path<?> step(Path<?> parent, String attribute, boolean intermediate) {
        Path<?> path = parent.get(attribute);

        // A plural attribute is deliberately left untouched, so that the callers reject it as they always did:
        // joining a collection duplicates the rows, which no ordering nor seek predicate can recover from
        if (intermediate
                && parent instanceof From<?, ?> owner
                && path.getModel() instanceof SingularAttribute<?, ?> singular
                && singular.isAssociation()) {
            return leftJoin(owner, attribute);
        }
        return path;
    }

    private static Join<?, ?> leftJoin(From<?, ?> owner, String attribute) {
        return owner.getJoins().stream()
                .filter(join -> join.getAttribute().getName().equals(attribute) && join.getJoinType() == LEFT)
                .<Join<?, ?>>map(join -> join)
                .findFirst()
                .orElseGet(() -> owner.join(attribute, LEFT));
    }

    private static Predicate equal(CriteriaBuilder builder, From<?, ?> root, SortCriterion criterion, String value, CursorKeyCodec codec) {
        Path<?> path = path(root, criterion.property());
        // equal accepts a plain Object, so no comparability is required here
        return builder.equal(path, codec.parse(value, path.getJavaType()));
    }

    /**
     * Builds the strict comparison of an ordering key against the value of the boundary row, in the direction of
     * the criterion.
     * <p>
     * The type variable is what makes the comparison type safe: the path and the parsed bound are expressed in
     * terms of the very same {@code Y}, which is the type the metamodel reports for the attribute, so the
     * comparability required by {@link CriteriaBuilder#greaterThan} is checked by the compiler instead of being
     * silenced. {@code Y} is inferred at the call site, where it is unconstrained, which is the usual capture
     * helper pattern.
     *
     * @param <Y>       The type of the ordering key, which must be comparable to be sought on
     * @param builder   The builder to use
     * @param root      The root entity of the query
     * @param criterion The ordering criterion the key belongs to
     * @param value     The textual key of the boundary row
     * @param codec     The codec parsing the textual key back into the Java type of its attribute
     * @return The corresponding predicate
     * @throws IllegalArgumentException if the type of the attribute is not supported by the given codec
     */
    private static <Y extends Comparable<? super Y>> Predicate after(CriteriaBuilder builder, From<?, ?> root, SortCriterion criterion, String value, CursorKeyCodec codec) {
        Path<Y> path = path(root, criterion.property());
        Y bound = codec.parse(value, path.getJavaType());
        return criterion.ascending() ? builder.greaterThan(path, bound) : builder.lessThan(path, bound);
    }

    private static @Nullable Object read(Object entity, String property) {
        Object value = entity;
        for (String attribute : split(property)) {
            if (value == null) {
                return null;
            }
            value = readAttribute(Hibernate.unproxy(value), attribute);
        }
        return value;
    }

    private static @Nullable Object readAttribute(Object owner, String attribute) {
        AccessibleObject accessor = ACCESSORS.get(owner.getClass()).computeIfAbsent(attribute, name -> accessorOf(owner.getClass(), name));
        try {
            return accessor instanceof Method getter ? getter.invoke(owner) : ((Field) accessor).get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read the cursor key " + attribute, e);
        }
    }

    /**
     * Resolves the accessor of an attribute, the getter taking precedence over the field so that a computed or
     * decorated one is honoured, walking the hierarchy up so that an inherited mapped superclass is covered.
     * <p>
     * The resolution is cached per entity class, since a cursor query reads the keys of both boundary rows of
     * every page it walks, and {@code getDeclaredMethod} copies the whole method array of the class at each call.
     *
     * @param type      The type to resolve the accessor on
     * @param attribute The name of the attribute to read
     * @return The corresponding accessor, already made accessible
     * @throws IllegalArgumentException if no accessor exists for the attribute
     * @throws IllegalStateException    if an accessor exists but cannot be made accessible
     */
    private static AccessibleObject accessorOf(Class<?> type, String attribute) {
        String capitalized = Character.toUpperCase(attribute.charAt(0)) + attribute.substring(1);
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String name : List.of("get" + capitalized, "is" + capitalized, attribute)) {
                try {
                    return accessible(current.getDeclaredMethod(name), attribute);
                } catch (NoSuchMethodException e) {
                    // Try the next candidate
                }
            }
            try {
                return accessible(current.getDeclaredField(attribute), attribute);
            } catch (NoSuchFieldException e) {
                // Try the superclass
            }
        }
        throw new IllegalArgumentException("Cannot read the cursor key " + attribute + " on " + type);
    }

    private static <A extends AccessibleObject> A accessible(A accessor, String attribute) {
        try {
            accessor.setAccessible(true);
            return accessor;
        } catch (InaccessibleObjectException | SecurityException e) {
            // Both are unchecked and thrown by setAccessible itself, not by the reflective call, so neither
            // extends ReflectiveOperationException
            throw new IllegalStateException("Cannot read the cursor key " + attribute, e);
        }
    }

    /**
     * Splits an entity attribute path into its attributes, shared by the path resolution and by the read back of
     * the ordering keys so that both walk a nested path exactly the same way.
     */
    static String[] split(String property) {
        return NESTING_PATTERN.split(property);
    }

}