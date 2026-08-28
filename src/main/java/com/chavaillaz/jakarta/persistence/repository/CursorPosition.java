package com.chavaillaz.jakarta.persistence.repository;

import java.util.List;

/**
 * Decoded content of a cursor: the ordering keys of the row the next page starts after, the direction of the
 * navigation and the fingerprint of the ordering the token was issued for.
 * <p>
 * The values are kept as strings, their binding to the actual attribute types being deferred to the moment the
 * seek predicate is built, when the Java type of each key is known from the metamodel.
 *
 * @param values      The textual representation of the ordering keys of the boundary row, in the ordering order
 * @param backward    The indicator of the direction, {@code true} when the previous page is requested
 * @param fingerprint The fingerprint of the resolved ordering the token was issued for
 */
public record CursorPosition(
        List<String> values,
        boolean backward,
        String fingerprint) {

    /**
     * Defaults a {@code null} list of values to an empty one; {@link List#copyOf} otherwise rejects a {@code null}
     * element on purpose, a nullable key being unusable as a cursor key.
     */
    public CursorPosition {
        values = values == null ? List.of() : List.copyOf(values);
    }

}