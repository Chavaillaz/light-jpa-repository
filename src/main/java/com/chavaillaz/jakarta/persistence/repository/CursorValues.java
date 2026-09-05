package com.chavaillaz.jakarta.persistence.repository;

import static java.lang.Long.parseLong;
import static org.apache.commons.lang3.ClassUtils.primitiveToWrapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
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
 * type change fail loudly instead of binding a stale value. What {@link #format} writes and what {@link #parse}
 * reads must therefore agree on every type a provider may hand over: a value formatted as a key of a type nothing
 * can read back would issue a first page whose token the very next call rejects, stranding the consumer on a page
 * it cannot leave.
 */
public final class CursorValues {

    /**
     * Number of nanoseconds in a millisecond, which is the finest precision the epoch millisecond count a date key
     * travels as can express.
     */
    private static final int NANOS_PER_MILLISECOND = 1_000_000;

    private static final Map<Class<?>, Function<String, Object>> PARSERS = Map.ofEntries(
            Map.entry(String.class, value -> value),
            Map.entry(Boolean.class, CursorValues::parseBoolean),
            Map.entry(Character.class, CursorValues::parseCharacter),
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
            // Every date subtype a provider hands back is read from the very same epoch millisecond count format
            // writes, the declared type of the attribute being what decides which one the key is bound as
            Map.entry(Date.class, value -> new Date(parseLong(value))),
            Map.entry(Timestamp.class, value -> new Timestamp(parseLong(value))),
            Map.entry(java.sql.Date.class, value -> new java.sql.Date(parseLong(value))),
            Map.entry(java.sql.Time.class, value -> new java.sql.Time(parseLong(value))));

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
     *                                  cursor key, if its type cannot be {@link #parse parsed back}, or if a date
     *                                  carries a precision finer than the millisecond
     */
    public static String format(String property, @Nullable Object value) {
        return switch (value) {
            case null -> throw new IllegalArgumentException("Cannot build a cursor on the null property %s: a cursor key must be non nullable".formatted(property));
            case Enum<?> constant -> constant.name();
            case Date date -> Long.toString(epochMillis(property, date));
            default -> parsable(property, value).toString();
        };
    }

    /**
     * Gets the epoch millisecond count of a date key, refusing the finer precision the token cannot carry.
     * <p>
     * A date travels as its epoch millisecond count, which is all a {@link Date} holds, but a column read into a
     * {@link Timestamp} may hold microseconds or nanoseconds, which the databases keeping a timestamp at that
     * precision do return. Truncating them would issue a token whose key is strictly before the boundary row
     * itself, so the seek predicate would return that very row again: the next page starts where the previous one
     * did, and a walk over such a column never advances. Rejecting the key surfaces that as a plain error while
     * the token is still being built, rather than as a page repeating forever.
     * <p>
     * Order on an attribute mapped to {@link Instant} or to {@link LocalDateTime}, whose keys carry
     * their nanoseconds, or teach a {@link CursorKeyCodec} how to represent the precision of that column.
     *
     * @param property The property the value belongs to, used to name it in the error message
     * @param date     The date to read the epoch millisecond count of
     * @return The corresponding epoch millisecond count
     * @throws IllegalArgumentException if the date carries a precision finer than the millisecond
     */
    private static long epochMillis(String property, Date date) {
        if (date instanceof Timestamp timestamp && timestamp.getNanos() % NANOS_PER_MILLISECOND != 0) {
            throw new IllegalArgumentException("Cannot build a cursor on the property %s: the timestamp %s is finer than the millisecond a date key travels as".formatted(property, timestamp));
        }
        return date.getTime();
    }

    /**
     * Checks that a value belongs to a type the parsers can read back, so that a key which is only unusable once
     * the consumer sends it back is refused while the token is still being built.
     * <p>
     * Without it, {@code toString()} formats anything: ordering on an association, on an embeddable or on a
     * converted attribute yields a first page carrying a perfectly valid looking token, which the very next call
     * rejects as an unsupported cursor key type. The consumer is then stranded on a page it cannot leave, and the
     * default representation of the value, which names its class and its identity hash, has meanwhile travelled
     * out as part of an opaque token.
     *
     * @param property The property the value belongs to, used to name it in the error message
     * @param value    The value to check
     * @return The very same value
     * @throws IllegalArgumentException if no parser can read the value back
     */
    private static Object parsable(String property, Object value) {
        if (PARSERS.keySet().stream().noneMatch(type -> type.isInstance(value))) {
            throw new IllegalArgumentException("Cannot build a cursor on the property %s: %s is not a supported cursor key type".formatted(property, value.getClass().getName()));
        }
        return value;
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
     *                                  valid representation of it, a token being untrusted consumer input
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

        try {
            return (Y) parser.apply(value);
        } catch (RuntimeException e) {
            // The value comes from a token an API consumer sent back, so it may be anything: whatever the
            // underlying parser throws is surfaced as a malformed cursor, which the API layer answers with a
            // 400 Bad Request, instead of leaking as an arbitrary runtime failure
            throw new IllegalArgumentException("Invalid cursor key value %s for the type %s".formatted(value, type.getName()), e);
        }
    }

    private static Boolean parseBoolean(String value) {
        return switch (value) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            // Boolean#valueOf would silently read any other text as false, seeking on a value nothing issued
            default -> throw new IllegalArgumentException("Expected true or false, got " + value);
        };
    }

    private static Character parseCharacter(String value) {
        if (value.length() != 1) {
            throw new IllegalArgumentException("Expected a single character, got " + value.length());
        }
        return value.charAt(0);
    }

}