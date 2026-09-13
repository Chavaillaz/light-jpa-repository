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
 * Keyset helpers shared by the query collaborators, building the {@code ORDER BY} clause, the seek predicate and
 * the boundary keys of a cursor query from a {@link EntityOrdering#resolveSort(RepositoryContext, Sort) resolved ordering}.
 * <p>
 * The predicate is the lexicographic comparison of the ordering keys, so that a row is returned as soon as it
 * comes strictly after the boundary row in the very same ordering, emitted as a flat disjunction of conjunctions,
 * one per ordering key, each one growing by the equality of one more leading key:
 * <pre>(k1 &gt; v1) OR (k1 = v1 AND k2 &lt; v2) OR (k1 = v1 AND k2 = v2 AND id &gt; v3)</pre>
 * A row value comparison {@code (k1, k2, id) > (v1, v2, v3)} would be shorter and better optimized, but it is
 * neither expressible with the criteria API nor supported by every database, and it only applies when all the
 * keys share the same direction.
 * <p>
 * The attribute paths the keys are named by are resolved by {@link AttributePaths}, which the plain ordering of
 * the offset queries shares, so that a nested key is walked the very same way whichever pagination asks for it.
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
            // An empty disjunction is false, so seeking on no ordering at all would silently return no row rather
            // than the page the consumer asked for; a resolved ordering always holds the identifier at least
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
     * Selects the entity alongside the very columns the ordering compares, so that the keys travelling within the
     * tokens are the values the database ordered on, and not what an accessor of the entity happens to return for
     * them.
     * <p>
     * The entity occupies the first element of each row, the keys following it in the ordering order, which is
     * what {@link #selectedValuesOf(Tuple, Sort, CursorKeyCodec)} reads them back with.
     *
     * @param query The cursor query being built
     * @param root  The root entity of the query
     * @param sort  The resolved ordering, whose keys are selected in the order they are compared in
     */
    public static void selectAlongside(CriteriaQuery<Tuple> query, From<?, ?> root, Sort sort) {
        List<Selection<?>> selections = new ArrayList<>(sort.criteria().size() + 1);
        selections.add(root);
        sort.criteria().forEach(criterion -> selections.add(AttributePaths.path(root, criterion.property())));
        query.multiselect(selections);
    }

    /**
     * Formats the ordering keys a cursor query returned alongside an entity, which become the position of the
     * cursor.
     *
     * @param row   The fetched row, whose first element is the entity and whose others are the keys
     * @param sort  The resolved ordering the keys were selected for
     * @param codec The codec formatting each key into its textual representation
     * @return The textual keys, in the ordering order
     * @throws IllegalArgumentException if one of the keys is {@code null}, a nullable attribute being unusable as
     *                                  a cursor key since the databases do not agree on where the nulls sort
     * @see #selectAlongside(CriteriaQuery, From, Sort)
     */
    public static List<String> selectedValuesOf(Tuple row, Sort sort, CursorKeyCodec codec) {
        List<SortCriterion> criteria = sort.criteria();
        // The entity occupies the first element, the keys following it in the ordering order
        return IntStream.range(0, criteria.size())
                .mapToObj(index -> codec.format(criteria.get(index).property(), row.get(index + 1)))
                .toList();
    }

    /**
     * Reads the ordering keys of the given entity, which become the position of the cursor.
     * <p>
     * This is the fallback of a query selecting the entity alone, a cursor query selecting its keys
     * {@link #selectAlongside(CriteriaQuery, From, Sort) alongside} the entity so that the token carries the values
     * the database ordered on: an accessor is free to return something else than the column it maps, and the seek
     * predicate would then be expressed in terms the {@code order by} clause never used.
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
        Path<Y> path = AttributePaths.path(root, criterion.property());
        Y bound = codec.parse(value, path.getJavaType());
        return criterion.ascending() ? builder.greaterThan(path, bound) : builder.lessThan(path, bound);
    }

}
