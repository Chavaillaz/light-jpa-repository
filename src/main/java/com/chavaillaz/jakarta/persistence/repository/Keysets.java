package com.chavaillaz.jakarta.persistence.repository;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.Hibernate;

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
     * @return The corresponding predicate
     *
     * @throws IllegalArgumentException if the keys do not match the ordering
     */
    public static Predicate seek(CriteriaBuilder criteriaBuilder, From<?, ?> root, Sort sort, List<String> values) {
        List<SortCriterion> criteria = sort.criteria();
        if (criteria.size() != values.size()) {
            throw new IllegalArgumentException("The cursor does not match the requested ordering");
        }

        List<Predicate> disjunction = new ArrayList<>();
        for (int index = 0; index < criteria.size(); index++) {
            List<Predicate> conjunction = new ArrayList<>();
            for (int previous = 0; previous < index; previous++) {
                conjunction.add(equal(criteriaBuilder, root, criteria.get(previous), values.get(previous)));
            }
            conjunction.add(after(criteriaBuilder, root, criteria.get(index), values.get(index)));
            disjunction.add(criteriaBuilder.and(conjunction.toArray(Predicate[]::new)));
        }
        return criteriaBuilder.or(disjunction.toArray(Predicate[]::new));
    }

    /**
     * Reads the ordering keys of the given entity, which become the position of the cursor.
     *
     * @param entity The entity of the boundary row of the page
     * @param sort   The resolved ordering
     * @return The textual keys, in the ordering order
     *
     * @throws IllegalArgumentException if one of the keys is {@code null} or cannot be read on the entity, a
     *                                  nullable attribute being unusable as a cursor key since the databases do
     *                                  not agree on where the nulls sort
     * @throws IllegalStateException    if the accessor of a cursor key cannot be invoked
     */
    public static List<String> valuesOf(Object entity, Sort sort) {
        return sort.criteria().stream()
                .map(criterion -> CursorValues.format(criterion.property(), read(entity, criterion.property())))
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
    public static <Y> Path<Y> path(From<?, ?> from, String property) {
        String[] attributes = split(property);

        Path<?> parent = from;
        for (int index = 0; index < attributes.length - 1; index++) {
            parent = parent.get(attributes[index]);
        }
        return parent.get(attributes[attributes.length - 1]);
    }

    private static Predicate equal(CriteriaBuilder builder, From<?, ?> root, SortCriterion criterion, String value) {
        Path<?> path = path(root, criterion.property());
        // equal accepts a plain Object, so no comparability is required here
        return builder.equal(path, CursorValues.parse(value, path.getJavaType()));
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
     * @return The corresponding predicate
     *
     * @throws IllegalArgumentException if the type of the attribute is not a supported cursor key type
     */
    private static <Y extends Comparable<? super Y>> Predicate after(CriteriaBuilder builder, From<?, ?> root, SortCriterion criterion, String value) {
        Path<Y> path = path(root, criterion.property());
        Y bound = CursorValues.parse(value, path.getJavaType());
        return criterion.ascending() ? builder.greaterThan(path, bound) : builder.lessThan(path, bound);
    }

    private static Object read(Object entity, String property) {
        Object value = entity;
        for (String attribute : split(property)) {
            if (value == null) {
                return null;
            }
            value = readAttribute(Hibernate.unproxy(value), attribute);
        }
        return value;
    }

    private static Object readAttribute(Object owner, String attribute) {
        // Only the first and the last rows of a page are read, so the plain reflection stays negligible
        String capitalized = Character.toUpperCase(attribute.charAt(0)) + attribute.substring(1);
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (String name : List.of("get" + capitalized, "is" + capitalized, attribute)) {
                try {
                    Method getter = type.getDeclaredMethod(name);
                    getter.setAccessible(true);
                    return getter.invoke(owner);
                } catch (NoSuchMethodException e) {
                    // Try the next candidate
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot read the cursor key " + attribute, e);
                }
            }
            try {
                Field field = type.getDeclaredField(attribute);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException e) {
                // Try the superclass
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read the cursor key " + attribute, e);
            }
        }
        throw new IllegalArgumentException("Cannot read the cursor key " + attribute + " on " + owner.getClass());
    }

    /**
     * Splits an entity attribute path into its attributes, shared by the path resolution and by the read back of
     * the ordering keys so that both walk a nested path exactly the same way.
     */
    static String[] split(String property) {
        return NESTING_PATTERN.split(property);
    }

}