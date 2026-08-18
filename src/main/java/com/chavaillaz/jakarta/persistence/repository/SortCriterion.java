package com.chavaillaz.jakarta.persistence.repository;

import static java.util.stream.Collectors.joining;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

import jakarta.persistence.metamodel.Attribute;

/**
 * Single ordering criterion of a query, made of the property to sort on and of its direction.
 *
 * @param property  The property to sort on, a nested property being expressed with a dot, such as {@code team.name}
 * @param ascending The indicator of the direction, {@code true} for an ascending order
 */
public record SortCriterion(String property, boolean ascending) {

    /**
     * Prefix of a property to sort in descending order.
     */
    public static final String DESCENDING_PREFIX = "-";

    /**
     * Optional prefix of a property to sort in ascending order.
     */
    public static final String ASCENDING_PREFIX = "+";

    /**
     * Separator of the attributes within a nested property.
     */
    public static final String NESTING_SEPARATOR = ".";

    /**
     * Pattern of an accepted attribute name, restrictive on purpose as the value comes from the API consumers.
     */
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("[A-Za-z_]\\w*");

    /**
     * Validates the property against {@link #ATTRIBUTE_PATTERN}, restrictive on purpose since it usually comes
     * from the API consumers.
     *
     * @throws IllegalArgumentException if the property is {@code null} or not a valid property name
     */
    public SortCriterion {
        if (property == null || !isValid(property)) {
            throw new IllegalArgumentException("Invalid sort property: " + property);
        }
    }

    /**
     * Parses a criterion expressed as a property optionally prefixed by {@value #DESCENDING_PREFIX} for a descending
     * order, such as {@code -startDate}.
     *
     * @param criterion The criterion to parse
     * @return The corresponding criterion
     *
     * @throws IllegalArgumentException if the property is not a valid property name
     */
    public static SortCriterion parse(String criterion) {
        // A leading plus sign is decoded as a space by the query parameter decoding, hence the strip
        String value = Objects.toString(criterion, "").strip();
        boolean ascending = !value.startsWith(DESCENDING_PREFIX);
        if (value.startsWith(DESCENDING_PREFIX) || value.startsWith(ASCENDING_PREFIX)) {
            value = value.substring(DESCENDING_PREFIX.length());
        }
        return new SortCriterion(value, ascending);
    }

    /**
     * Creates an ascending criterion on the given property.
     *
     * @param property The property to sort on
     * @return The corresponding criterion
     */
    public static SortCriterion asc(String property) {
        return new SortCriterion(property, true);
    }

    /**
     * Creates a descending criterion on the given property.
     *
     * @param property The property to sort on
     * @return The corresponding criterion
     */
    public static SortCriterion desc(String property) {
        return new SortCriterion(property, false);
    }

    /**
     * Creates an ascending criterion on the given attribute path of the static metamodel, a nested property being
     * expressed as several attributes, such as {@code CoffeeEntity_.roaster, RoasterEntity_.name}.
     * <p>
     * Building the criterion from the metamodel instead of a plain string makes a rename of the underlying
     * attribute fail the build instead of silently misbehaving at runtime.
     * <p>
     * Unlike the plain string overload, this one requires the given attributes to already be initialized: a JPA
     * provider populates a generated static metamodel field the first time it bootstraps a persistence unit
     * mapping the owning entity, the field staying {@code null} beforehand. Call this from code that runs after
     * that bootstrap, such as a repository finder method, not from a static initializer.
     * <p>
     * When the repository declares searchable properties, the resolved path must already be the target of one of
     * them, under whatever public alias; see {@link EntityOrdering#resolveProperty(String)}.
     *
     * @param path The attribute path to sort on, from the static metamodel
     * @return The corresponding criterion
     */
    public static SortCriterion asc(Attribute<?, ?>... path) {
        return asc(pathOf(path));
    }

    /**
     * Creates a descending criterion on the given attribute path of the static metamodel, a nested property being
     * expressed as several attributes, such as {@code CoffeeEntity_.roaster, RoasterEntity_.name}.
     * <p>
     * Building the criterion from the metamodel instead of a plain string makes a rename of the underlying
     * attribute fail the build instead of silently misbehaving at runtime.
     *
     * @param path The attribute path to sort on, from the static metamodel
     * @return The corresponding criterion
     * @see #asc(Attribute[]) for the requirement on the attributes being already initialized
     */
    public static SortCriterion desc(Attribute<?, ?>... path) {
        return desc(pathOf(path));
    }

    /**
     * Joins an attribute path of the static metamodel into the dotted property it represents.
     *
     * @param path The attribute path, from the static metamodel
     * @return The corresponding property
     */
    private static String pathOf(Attribute<?, ?>... path) {
        return Arrays.stream(path).map(Attribute::getName).collect(joining(NESTING_SEPARATOR));
    }

    /**
     * Checks that the given property is made of valid attribute names, separated by {@value #NESTING_SEPARATOR}.
     *
     * @param property The property to validate
     * @return {@code true} if the property is valid, {@code false} otherwise
     */
    private static boolean isValid(String property) {
        String[] attributes = property.split(Pattern.quote(NESTING_SEPARATOR), -1);
        return Arrays.stream(attributes).allMatch(attribute -> ATTRIBUTE_PATTERN.matcher(attribute).matches());
    }

    /**
     * Reverses the direction of this criterion, used to walk a cursor backwards.
     *
     * @return The reversed criterion
     */
    public SortCriterion reversed() {
        return new SortCriterion(property, !ascending);
    }

    @Override
    public String toString() {
        return ascending ? property : DESCENDING_PREFIX + property;
    }

}
