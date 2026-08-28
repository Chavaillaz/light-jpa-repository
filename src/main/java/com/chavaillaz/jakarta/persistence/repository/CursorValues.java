package com.chavaillaz.jakarta.persistence.repository;

import static java.lang.Long.parseLong;
import static org.apache.commons.lang3.ClassUtils.primitiveToWrapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Conversion of the cursor keys between their textual representation, which travels within the token, and the
 * Java type of the corresponding attribute, taken from the metamodel when the seek predicate is built.
 * <p>
 * The type is deliberately not stored in the token: relying on the metamodel keeps the tokens short and makes a
 * type change fail loudly instead of binding a stale value.
 */
public final class CursorValues {

    private static final Map<Class<?>, Function<String, Object>> PARSERS = Map.ofEntries(
            Map.entry(String.class, value -> value),
            Map.entry(Boolean.class, Boolean::valueOf),
            Map.entry(Character.class, value -> value.charAt(0)),
            Map.entry(Byte.class, Byte::valueOf),
            Map.entry(Short.class, Short::valueOf),
            Map.entry(Integer.class, Integer::valueOf),
            Map.entry(Long.class, Long::valueOf),
            Map.entry(Float.class, Float::valueOf),
            Map.entry(Double.class, Double::valueOf),
            Map.entry(BigDecimal.class, BigDecimal::new),
            Map.entry(BigInteger.class, BigInteger::new),
            Map.entry(UUID.class, UUID::fromString),
            Map.entry(Instant.class, Instant::parse),
            Map.entry(LocalDate.class, LocalDate::parse),
            Map.entry(LocalTime.class, LocalTime::parse),
            Map.entry(LocalDateTime.class, LocalDateTime::parse),
            Map.entry(OffsetDateTime.class, OffsetDateTime::parse),
            Map.entry(ZonedDateTime.class, ZonedDateTime::parse),
            Map.entry(Duration.class, Duration::parse),
            Map.entry(Date.class, value -> new Date(parseLong(value))));

    private CursorValues() {
        // This utility class should not be instantiated
    }

    /**
     * Formats a cursor key value into its textual representation, as it travels within the token.
     *
     * @param property The property the value belongs to, used to name it in the error message
     * @param value    The value to format, {@code null} rejected since a nullable attribute is unusable as a
     *                 cursor key
     * @return The corresponding textual representation
     * @throws IllegalArgumentException if the value is {@code null}, a nullable attribute being unusable as a
     *                                  cursor key
     */
    public static String format(String property, @Nullable Object value) {
        return switch (value) {
            case null -> throw new IllegalArgumentException("Cannot build a cursor on the null property %s: a cursor key must be non nullable".formatted(property));
            case Enum<?> constant -> constant.name();
            case Date date -> Long.toString(date.getTime());
            default -> value.toString();
        };
    }

    /**
     * Parses a textual cursor key back into the Java type the metamodel reports for the attribute.
     * <p>
     * This is the single place where the type safety of the cursor keys cannot be proven by the compiler: the
     * type is only known at runtime, from the path being sought on. It is guaranteed instead by construction,
     * every supported type being parsed by the parser registered for it, and the returned instance being
     * therefore always an instance of the requested type.
     *
     * @param <Y>   The type of the attribute, inferred from the requested class
     * @param value The textual representation of the key, as it travels within the token
     * @param type  The Java type of the attribute, as reported by the metamodel
     * @return The corresponding value
     * @throws IllegalArgumentException if the type is not a supported cursor key type, or if the value is not a
     *                                  constant of the requested enum type
     */
    @SuppressWarnings("unchecked")
    public static <Y> Y parse(String value, Class<Y> type) {
        if (type.isEnum()) {
            return Arrays.stream(type.getEnumConstants())
                    .filter(constant -> ((Enum<?>) constant).name().equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown constant %s of the cursor key type %s".formatted(value, type.getName())));
        }

        Function<String, Object> parser = PARSERS.get(primitiveToWrapper(type));
        if (parser == null) {
            throw new IllegalArgumentException("Unsupported cursor key type " + type.getName());
        }
        return (Y) parser.apply(value);
    }

}