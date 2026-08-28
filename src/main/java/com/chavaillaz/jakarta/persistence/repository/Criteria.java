package com.chavaillaz.jakarta.persistence.repository;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import org.hibernate.query.restriction.Restriction;
import org.jspecify.annotations.Nullable;

/**
 * Additional criteria of a query, expressed with the criteria API so that the constructs the
 * {@link Restriction restrictions} cannot express, such as the correlated subqueries, stay available.
 *
 * @param <T> The type of the queried entity
 */
@FunctionalInterface
public interface Criteria<T> {

    /**
     * Builds criteria from a restriction, so that both can be combined.
     *
     * @param <T>         The type of the queried entity
     * @param restriction The restriction to adapt
     * @return The corresponding criteria
     */
    static <T> Criteria<T> of(Restriction<? super T> restriction) {
        return (criteriaBuilder, query, root) -> restriction.toPredicate(root, criteriaBuilder);
    }

    /**
     * Combines the given criteria with a logical or, ignoring the {@code null} ones.
     *
     * @param <T>      The type of the queried entity
     * @param criteria The criteria to combine
     * @return The combined criteria, or {@code null} when none is given or all the given ones are {@code null}
     */
    @SafeVarargs
    static <T> @Nullable Criteria<T> anyOf(@Nullable Criteria<T>... criteria) {
        return combine(CriteriaBuilder::or, criteria);
    }

    /**
     * Combines the given criteria with a logical and, ignoring the {@code null} ones.
     *
     * @param <T>      The type of the queried entity
     * @param criteria The criteria to combine
     * @return The combined criteria, or {@code null} when none is given or all the given ones are {@code null}
     */
    @SafeVarargs
    static <T> @Nullable Criteria<T> allOf(@Nullable Criteria<T>... criteria) {
        return combine(CriteriaBuilder::and, criteria);
    }

    /**
     * Combines the given criteria with the given operator, ignoring the {@code null} ones.
     *
     * @param <T>      The type of the queried entity
     * @param operator The operator combining the resulting predicates
     * @param criteria The criteria to combine
     * @return The combined criteria, or {@code null} when none is given or all the given ones are {@code null}
     */
    @SafeVarargs
    private static <T> @Nullable Criteria<T> combine(BiFunction<CriteriaBuilder, Predicate[], Predicate> operator, @Nullable Criteria<T>... criteria) {
        List<Criteria<T>> present = Stream.of(criteria).filter(Objects::nonNull).toList();
        if (present.isEmpty()) {
            return null;
        }
        return (criteriaBuilder, query, root) -> operator.apply(criteriaBuilder, present.stream()
                .map(criterion -> criterion.toPredicate(criteriaBuilder, query, root))
                .toArray(Predicate[]::new));
    }

    /**
     * Builds criteria matching the entities having at least one related entity of the given type satisfying the
     * given predicate, which translates into an {@code EXISTS} correlated subquery.
     *
     * @param <T>           The type of the queried entity
     * @param <R>           The type of the related entity
     * @param relatedType   The type of the related entity
     * @param backReference The name of the attribute of the related entity referring back to the queried one
     * @param predicate     The additional predicate the related entity must satisfy
     * @return The corresponding criteria
     */
    static <T, R> Criteria<T> exists(Class<R> relatedType, String backReference, BiFunction<CriteriaBuilder, Root<R>, Predicate> predicate) {
        return (criteriaBuilder, query, root) -> {
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<R> related = subquery.from(relatedType);
            return criteriaBuilder.exists(subquery.select(criteriaBuilder.literal(1))
                    .where(criteriaBuilder.equal(related.get(backReference), root),
                            predicate.apply(criteriaBuilder, related)));
        };
    }

    /**
     * Builds the predicate to add to the query.
     *
     * @param criteriaBuilder The builder to use to create the predicate
     * @param query           The query being built, to create the subqueries from
     * @param root            The root entity of the query
     * @return The corresponding predicate
     */
    Predicate toPredicate(CriteriaBuilder criteriaBuilder, CriteriaQuery<T> query, Root<T> root);

}

