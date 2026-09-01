package com.chavaillaz.jakarta.persistence.repository;

import static jakarta.persistence.criteria.JoinType.LEFT;

import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.metamodel.SingularAttribute;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.hibernate.Hibernate;
import org.jspecify.annotations.Nullable;

/**
 * Navigation of a dotted entity attribute path, such as {@code roaster.name}, either through the criteria API of
 * a query or reflectively on an entity instance.
 * <p>
 * This is shared by everything naming an attribute by its path: the ordering of any query resolves one here, and
 * so do the {@link Keysets keyset} clauses of the cursor pagination. Both must walk a nested path exactly the same
 * way, which is why the walk lives in one place rather than in each of them.
 */
public final class AttributePaths {

    /**
     * Compiled form of the {@link SortCriterion#NESTING_SEPARATOR}, quoted so that a separator holding a regex
     * metacharacter — which the dot is — splits literally, and compiled once since every query resolves one path
     * per ordering key.
     */
    private static final Pattern NESTING_PATTERN = Pattern.compile(Pattern.quote(SortCriterion.NESTING_SEPARATOR));

    /**
     * Accessors of the entity attributes, resolved once per entity class and attribute rather than at every
     * boundary row of every page. A {@link ClassValue} is used rather than a plain map keyed by the class, so that
     * the cache cannot hold a class, and therefore its class loader, alive after a redeployment.
     */
    private static final ClassValue<Map<String, AccessibleObject>> ACCESSORS = new ClassValue<>() {

        @Override
        protected Map<String, AccessibleObject> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }

    };

    private AttributePaths() {
        // This utility class should not be instantiated
    }

    /**
     * Resolves the path of an already validated entity attribute path, a nested one being expressed with the
     * {@link SortCriterion#NESTING_SEPARATOR}.
     * <p>
     * The type of the resolved attribute is inferred from the call site, as {@link Path#get(String)} itself does:
     * it cannot be checked at compile time since the path is only known as a string, and it is guaranteed instead
     * by {@link EntityOrdering}, which validates every ordering property against the metamodel before a query is
     * built.
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

    /**
     * Splits an entity attribute path into its attributes, shared by the path resolution and by the read back of
     * the attribute values so that both walk a nested path exactly the same way.
     *
     * @param property The entity attribute path to split
     * @return The attributes it is made of, in order
     */
    static String[] split(String property) {
        return NESTING_PATTERN.split(property);
    }

    /**
     * Reads the value of an attribute path on an entity instance, the getter taking precedence over the field.
     *
     * @param entity   The entity to read the value on
     * @param property The entity attribute path, nested parts being separated by the nesting separator
     * @return The corresponding value, or {@code null} when it is unset or when an intermediate owner is
     * @throws IllegalArgumentException if no accessor exists for one of the attributes
     * @throws IllegalStateException    if an accessor exists but cannot be invoked
     */
    static @Nullable Object read(Object entity, String property) {
        Object value = entity;
        for (String attribute : split(property)) {
            if (value == null) {
                return null;
            }
            value = readAttribute(Hibernate.unproxy(value), attribute);
        }
        return value;
    }

    private static Join<?, ?> leftJoin(From<?, ?> owner, String attribute) {
        return owner.getJoins().stream()
                .filter(join -> join.getAttribute().getName().equals(attribute) && join.getJoinType() == LEFT)
                .<Join<?, ?>>map(join -> join)
                .findFirst()
                .orElseGet(() -> owner.join(attribute, LEFT));
    }

    private static @Nullable Object readAttribute(Object owner, String attribute) {
        AccessibleObject accessor = ACCESSORS.get(owner.getClass()).computeIfAbsent(attribute, name -> accessorOf(owner.getClass(), name));
        try {
            return accessor instanceof Method getter ? getter.invoke(owner) : ((Field) accessor).get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read the attribute " + attribute, e);
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
        throw new IllegalArgumentException("Cannot read the attribute " + attribute + " on " + type);
    }

    private static <A extends AccessibleObject> A accessible(A accessor, String attribute) {
        try {
            accessor.setAccessible(true);
            return accessor;
        } catch (InaccessibleObjectException | SecurityException e) {
            // Both are unchecked and thrown by setAccessible itself, not by the reflective call, so neither
            // extends ReflectiveOperationException
            throw new IllegalStateException("Cannot read the attribute " + attribute, e);
        }
    }

}
