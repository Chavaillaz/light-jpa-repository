package com.chavaillaz.jakarta.persistence.repository;

import static jakarta.persistence.criteria.JoinType.LEFT;

import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.metamodel.EmbeddableType;
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
 * Every ordering resolves its attributes here, that of an offset query as the {@link Keysets keyset} clauses of a
 * cursor one, so that a nested path is walked the same way whichever pagination asks for it.
 */
public final class AttributePaths {

    /**
     * Compiled form of the {@link SortCriterion#NESTING_SEPARATOR}, quoted since the dot is a regex metacharacter.
     */
    private static final Pattern NESTING_PATTERN = Pattern.compile(Pattern.quote(SortCriterion.NESTING_SEPARATOR));

    /**
     * Accessors of the entity attributes, resolved once per class and attribute, held in a {@link ClassValue}
     * rather than in a map keyed by the class, so that the cache cannot keep a class loader alive after a
     * redeployment.
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
     * The type of the attribute is inferred from the call site, as {@link Path#get(String)} infers it, the path
     * being only known as a string; {@link EntityOrdering} validates it against the metamodel beforehand.
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
     * Resolves a single attribute of a nested path, navigating an intermediate association with a left join
     * rather than with the implicit inner join {@link Path#get(String)} produces.
     * <p>
     * An inner join drops the entities whose association is {@code null} from the results, while the count,
     * derived from the same query without its {@code order by} clause, still includes them. An intermediate
     * embeddable is joined as well, which costs no join in the emitted SQL, since only a {@link From} can be left
     * joined to reach an association behind it. A join the query already has is reused, see
     * {@link #reusedJoin(From, String)}.
     *
     * @param parent       The path to resolve the attribute against
     * @param attribute    The name of the attribute to resolve
     * @param intermediate {@code true} when the attribute is not the last one of the path, and is therefore
     *                     navigated rather than read
     * @return The corresponding path, a join for an intermediate association or embeddable
     * @throws IllegalArgumentException if the attribute does not exist on the parent path
     * @throws IllegalStateException    if the parent path is a basic attribute, which has nothing to dereference
     */
    static Path<?> step(Path<?> parent, String attribute, boolean intermediate) {
        Path<?> path = parent.get(attribute);

        // A plural attribute is left on its path for the callers to reject, a join on it duplicating the rows
        if (intermediate
                && parent instanceof From<?, ?> owner
                && path.getModel() instanceof SingularAttribute<?, ?> singular
                && (singular.isAssociation() || singular.getType() instanceof EmbeddableType<?>)) {
            return reusedJoin(owner, attribute);
        }
        return path;
    }

    /**
     * Splits an entity attribute path into its attributes.
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

    /**
     * Navigates an association through the join the query already has on it, creating a left one only when it has
     * none: whatever its type, the existing join already decides which rows the query returns, and a second one
     * would only join the same table twice.
     *
     * @param owner     The root or join owning the association
     * @param attribute The name of the association to navigate
     * @return The join to resolve the rest of the path against
     */
    private static Join<?, ?> reusedJoin(From<?, ?> owner, String attribute) {
        return owner.getJoins().stream()
                .filter(join -> join.getAttribute().getName().equals(attribute))
                .<Join<?, ?>>map(join -> join)
                .findFirst()
                .orElseGet(() -> owner.join(attribute, LEFT));
    }

    private static @Nullable Object readAttribute(Object owner, String attribute) {
        AccessibleObject accessor = ACCESSORS.get(owner.getClass()).computeIfAbsent(attribute, name -> accessorOf(owner.getClass(), name));
        try {
            return accessor instanceof Method getter ? getter.invoke(owner) : ((Field) accessor).get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read attribute " + attribute, e);
        }
    }

    /**
     * Resolves the accessor of an attribute, the getter taking precedence over the field so that a computed or
     * decorated one is honoured, walking up the hierarchy so that a mapped superclass is covered.
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
        throw new IllegalArgumentException("Cannot read attribute " + attribute + " on " + type);
    }

    private static <A extends AccessibleObject> A accessible(A accessor, String attribute) {
        try {
            accessor.setAccessible(true);
            return accessor;
        } catch (InaccessibleObjectException | SecurityException e) {
            // Thrown by setAccessible itself, neither of them being a ReflectiveOperationException
            throw new IllegalStateException("Cannot read attribute " + attribute, e);
        }
    }

}
