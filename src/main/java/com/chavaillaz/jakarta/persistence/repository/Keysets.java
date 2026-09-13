package com.chavaillaz.jakarta.persistence.repository;

import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Selection;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Keyset helpers of the cursor pagination, building the {@code ORDER BY} clause, the seek predicate and the
 * boundary keys of a query from a {@link EntityOrdering#resolveSort(RepositoryContext, Sort) resolved ordering},
 * the attribute paths being resolved by {@link AttributePaths}.
 * <p>
 * The seek predicate is the lexicographic comparison of the ordering keys, emitted as a disjunction of
 * conjunctions, each one adding the equality of one more leading key:
 * <pre>(k1 &gt; v1) OR (k1 = v1 AND k2 &lt; v2) OR (k1 = v1 AND k2 = v2 AND id &gt; v3)</pre>
 * A row value comparison {@code (k1, k2, id) > (v1, v2, v3)} would be shorter, but the criteria API cannot express
 * it, not every database supports it, and it requires every key to share the same direction.
 */
public final class Keysets {

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
                        ? criteriaBuilder.asc(AttributePaths.path(root, criterion.property()))
                        : criteriaBuilder.desc(AttributePaths.path(root, criterion.property())))
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
     * @throws IllegalArgumentException if the ordering is empty, if the keys do not match it, or if a key type is
     *                                  not supported by the given codec
     */
    public static Predicate seek(CriteriaBuilder criteriaBuilder, From<?, ?> root, Sort sort, List<String> values, CursorKeyCodec codec) {
        List<SortCriterion> criteria = sort.criteria();
        if (criteria.isEmpty()) {
            // An empty disjunction is false, and would silently keep no row at all
            throw new IllegalArgumentException("Cannot seek without an ordering to compare the boundary row against");
        }
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
     * Selects the root entity followed by the keys of the ordering, so that a token carries the values the
     * database ordered on rather than what the accessors of the entity return.
     *
     * @param query The cursor query being built
     * @param root  The root entity of the query, selected first
     * @param sort  The resolved ordering, whose keys follow the entity in the ordering order
     * @see #selectedValuesOf(Tuple, Sort, CursorKeyCodec)
     */
    public static void selectAlongside(CriteriaQuery<Tuple> query, From<?, ?> root, Sort sort) {
        List<Selection<?>> selections = new ArrayList<>(sort.criteria().size() + 1);
        selections.add(root);
        sort.criteria().forEach(criterion -> selections.add(AttributePaths.path(root, criterion.property())));
        query.multiselect(selections);
    }

    /**
     * Formats the ordering keys a row {@link #selectAlongside(CriteriaQuery, From, Sort) selected} after its entity,
     * which become the position of the cursor.
     *
     * @param row   The fetched row, whose first element is the entity and whose others are the keys
     * @param sort  The resolved ordering the keys were selected for
     * @param codec The codec formatting each key into its textual representation
     * @return The textual keys, in the ordering order
     * @throws IllegalArgumentException if one of the keys is {@code null} or of a type the codec does not support
     */
    public static List<String> selectedValuesOf(Tuple row, Sort sort, CursorKeyCodec codec) {
        List<SortCriterion> criteria = sort.criteria();
        return IntStream.range(0, criteria.size())
                .mapToObj(index -> codec.format(criteria.get(index).property(), row.get(index + 1)))
                .toList();
    }

    /**
     * Reads the ordering keys of the given entity, which become the position of the cursor, for a query selecting
     * the entity alone.
     * <p>
     * Prefer {@link #selectAlongside(CriteriaQuery, From, Sort) selecting the keys}: an accessor is free to return
     * something else than the column it maps, and the token would then carry a key the database never ordered on.
     *
     * @param entity The entity of the boundary row of the page
     * @param sort   The resolved ordering
     * @param codec  The codec formatting each key into its textual representation
     * @return The textual keys, in the ordering order
     * @throws IllegalArgumentException if one of the keys is {@code null}, of a type the codec does not support, or
     *                                  cannot be read on the entity
     * @throws IllegalStateException    if the accessor of a cursor key cannot be invoked
     */
    public static List<String> valuesOf(Object entity, Sort sort, CursorKeyCodec codec) {
        return sort.criteria().stream()
                .map(criterion -> codec.format(criterion.property(), AttributePaths.read(entity, criterion.property())))
                .toList();
    }

    private static Predicate equal(CriteriaBuilder builder, From<?, ?> root, SortCriterion criterion, String value, CursorKeyCodec codec) {
        Path<?> path = AttributePaths.path(root, criterion.property());
        // equal accepts a plain Object, so no comparability is required here
        return builder.equal(path, codec.parse(value, path.getJavaType()));
    }

    /**
     * Builds the strict comparison of an ordering key against the value of the boundary row, in the direction of
     * the criterion.
     * <p>
     * The type variable ties the path and the parsed bound to the same comparable {@code Y}, inferred at the call
     * site, so that the compiler checks what {@link CriteriaBuilder#greaterThan} requires instead of the check
     * being silenced.
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
        Path<Y> path = AttributePaths.path(root, criterion.property());
        Y bound = codec.parse(value, path.getJavaType());
        return criterion.ascending() ? builder.greaterThan(path, bound) : builder.lessThan(path, bound);
    }

}
