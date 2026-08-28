package com.chavaillaz.jakarta.persistence.repository;

import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.isBlank;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * Ordering requested for a query, as an ordered list of criteria.
 * <p>
 * The identifier of the entity is always appended by the repositories, so that the ordering is unique and the
 * pagination therefore stable, whatever the criteria requested.
 *
 * @param criteria The criteria to apply, in order of precedence
 */
public record Sort(
        List<SortCriterion> criteria) {

    /**
     * Empty ordering, letting the repository apply its own default ordering.
     */
    public static final Sort NONE = new Sort(List.of());

    /**
     * Separator of the criteria within the textual representation of an ordering.
     */
    public static final String SEPARATOR = ",";

    /**
     * Defaults a {@code null} list of criteria to an empty one, and defends the ordering against a later
     * modification of the list it was built from.
     */
    public Sort {
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
    }

    /**
     * Creates an ordering from the given criteria.
     *
     * @param criteria The criteria to apply, in order of precedence
     * @return The corresponding ordering
     */
    public static Sort of(SortCriterion... criteria) {
        return new Sort(List.of(criteria));
    }

    /**
     * Parses an ordering expressed as a comma separated list of properties, each one being optionally prefixed by
     * {@value SortCriterion#DESCENDING_PREFIX} for a descending order, such as {@code -startDate,name}.
     *
     * @param sort The ordering to parse, {@link #NONE} being returned when blank
     * @return The corresponding ordering
     * @throws IllegalArgumentException if one of the properties is not a valid property name
     */
    public static Sort parse(String sort) {
        if (isBlank(sort)) {
            return NONE;
        }

        return new Sort(Arrays.stream(sort.split(SEPARATOR))
                .filter(StringUtils::isNotBlank)
                .map(SortCriterion::parse)
                .toList());
    }

    /**
     * Reverses every criterion, used to walk a cursor backwards, the resulting page being reversed again before
     * being returned so that the consumer always sees the natural ordering.
     *
     * @return The reversed ordering
     */
    public Sort reversed() {
        return new Sort(criteria.stream().map(SortCriterion::reversed).toList());
    }

    /**
     * Checks whether no criterion is requested, the repository then applying its default ordering.
     *
     * @return {@code true} if this ordering holds no criterion, {@code false} otherwise
     */
    public boolean isEmpty() {
        return criteria.isEmpty();
    }

    @Override
    public String toString() {
        return criteria.stream()
                .map(SortCriterion::toString)
                .collect(joining(SEPARATOR));
    }

}



