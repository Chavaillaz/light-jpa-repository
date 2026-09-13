package com.chavaillaz.jakarta.persistence.repository;

import org.jspecify.annotations.Nullable;

/**
 * Codec of the cursor key values, turning the value of an ordering attribute into the textual representation
 * carried within a cursor token, and back into the Java type the metamodel reports for that attribute.
 * <p>
 * It handles a single key of a position, independently of the {@link CursorCodec} encoding the whole position into
 * the token. Override it to support an attribute type {@link CursorValues} does not, such as a custom identifier
 * type or one behind an attribute converter, or to carry a precision the default representation drops, typically
 * delegating to {@link #DEFAULT} for every other type.
 */
public interface CursorKeyCodec {

    /**
     * The default codec, covering the attribute types {@link CursorValues} supports out of the box.
     */
    CursorKeyCodec DEFAULT = new CursorKeyCodec() {

        @Override
        public String format(String property, @Nullable Object value) {
            return CursorValues.format(property, value);
        }

        @Override
        public <Y> Y parse(String value, Class<Y> type) {
            return CursorValues.parse(value, type);
        }

    };

    /**
     * Formats a cursor key value into its textual representation, as it travels within the token.
     *
     * @param property The property the value belongs to, used to name it in the error message
     * @param value    The value to format, {@code null} rejected since a nullable attribute is unusable as a
     *                 cursor key
     * @return The corresponding textual representation
     * @throws IllegalArgumentException if the value is {@code null} or of a type this codec does not support
     */
    String format(String property, @Nullable Object value);

    /**
     * Parses a textual cursor key back into the Java type the metamodel reports for the attribute.
     *
     * @param <Y>   The type of the attribute, inferred from the requested class
     * @param value The textual representation of the key, as it travels within the token
     * @param type  The Java type of the attribute, as reported by the metamodel
     * @return The corresponding value
     * @throws IllegalArgumentException if the type is not supported by this codec
     */
    <Y> Y parse(String value, Class<Y> type);

}
