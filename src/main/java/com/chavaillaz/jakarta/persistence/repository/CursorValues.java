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
import java.time.OffsetTime;
import java.time.Year;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Conversion of the cursor keys between their textual representation, which travels within the token, and the
 * Java type of the corresponding attribute, which the metamodel reports when the seek predicate is built.
 * <p>
 * The type is not stored in the token, which keeps it short and makes a type change fail loudly. What
 * {@link #format} writes and what {@link #parse} reads must therefore agree on every type: a key nothing can read
 * back would issue a first page whose token the very next call refuses.
 */
public final class CursorValues {

    /**
     * Number of nanoseconds in a millisecond, the precision of the epoch millisecond count a date key travels as.
     */
    private static final int NANOS_PER_MILLISECOND = 1_000_000;

    /**
     * Largest scale a decimal key may carry either way, the 131 072 digits the widest database numeric type holds
     * before its decimal point: a token is untrusted input, and an exponent such as {@code 1E+999999999} would
     * otherwise reach a driver expanding it into as many digits, spending minutes and gigabytes on a single key.
     */
    private static final int MAX_DECIMAL_SCALE = 131_072;

    /**
     * Longest text a decimal or integer key may be parsed from, room enough for the 131 072 digits the widest
     * database numeric type holds before its decimal point, the 16 383 it holds after, a sign and an exponent:
     * parsing a number takes the square of its length, and a forged key of a million digits would otherwise spend
     * twenty seconds of processor time.
     */
    private static final int MAX_NUMBER_LENGTH = 150_000;

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
            Map.entry(BigDecimal.class, CursorValues::parseDecimal),
            Map.entry(BigInteger.class, CursorValues::parseInteger),
            Map.entry(UUID.class, UUID::fromString),
            Map.entry(Instant.class, Instant::parse),
            Map.entry(LocalDate.class, LocalDate::parse),
            Map.entry(LocalTime.class, LocalTime::parse),
            Map.entry(LocalDateTime.class, LocalDateTime::parse),
            Map.entry(OffsetTime.class, OffsetTime::parse),
            Map.entry(OffsetDateTime.class, OffsetDateTime::parse),
            Map.entry(ZonedDateTime.class, ZonedDateTime::parse),
            Map.entry(Year.class, Year::parse),
            Map.entry(Duration.class, Duration::parse),
            // Every date type is read back from the epoch millisecond count format writes, the declared type of the
            // attribute deciding which one the key is bound as
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
     * @throws IllegalArgumentException if the value is {@code null}, if its type cannot be {@link #parse parsed
     *                                  back}, if a date carries a precision finer than the millisecond, if a
     *                                  decimal carries a scale beyond the largest one a key may, or if a number
     *                                  is written longer than the longest one a key may
     */
    public static String format(String property, @Nullable Object value) {
        return switch (value) {
            case null -> throw new IllegalArgumentException("Cannot build a cursor on null property %s: a cursor key must be non nullable".formatted(property));
            case Enum<?> constant -> constant.name();
            case Date date -> Long.toString(epochMillis(property, date));
            case BigDecimal decimal -> boundedLength(property, boundedScale(property, decimal).toString());
            case BigInteger integer -> boundedLength(property, integer.toString());
            default -> parsable(property, value).toString();
        };
    }

    /**
     * Gets the epoch millisecond count of a date key, refusing the finer precision a {@link Timestamp} may carry.
     * <p>
     * Truncating it would issue a key strictly before the boundary row itself, which the seek predicate would
     * then return again, so that the walk would never advance. Order on an attribute mapped to {@link Instant} or
     * to {@link LocalDateTime}, whose keys carry their nanoseconds, or teach that precision to a
     * {@link CursorKeyCodec}.
     *
     * @param property The property the value belongs to, used to name it in the error message
     * @param date     The date to read the epoch millisecond count of
     * @return The corresponding epoch millisecond count
     * @throws IllegalArgumentException if the date carries a precision finer than the millisecond
     */
    private static long epochMillis(String property, Date date) {
        if (date instanceof Timestamp timestamp && timestamp.getNanos() % NANOS_PER_MILLISECOND != 0) {
            throw new IllegalArgumentException("Cannot build a cursor on property %s: timestamp %s is finer than the millisecond a date key travels as".formatted(property, timestamp));
        }
        return date.getTime();
    }

    /**
     * Checks that a decimal key carries a scale its parser reads back, {@link #MAX_DECIMAL_SCALE} at most either
     * way, so that such a key is refused while the token is still being built rather than once the consumer sends
     * it back.
     *
     * @param property The property the value belongs to, used to name it in the error message
     * @param decimal  The decimal to check
     * @return The very same decimal
     * @throws IllegalArgumentException if the scale of the decimal is beyond the largest one a key may carry
     */
    private static BigDecimal boundedScale(String property, BigDecimal decimal) {
        if (Math.abs((long) decimal.scale()) > MAX_DECIMAL_SCALE) {
            throw new IllegalArgumentException("Cannot build a cursor on property %s: decimal %s has a scale beyond %d".formatted(property, decimal, MAX_DECIMAL_SCALE));
        }
        return decimal;
    }

    /**
     * Checks that a decimal or integer key is written in a text its parser reads back, {@link #MAX_NUMBER_LENGTH}
     * characters at most, so that such a key is refused while the token is still being built rather than once the
     * consumer sends it back.
     *
     * @param property The property the value belongs to, used to name it in the error message
     * @param number   The textual representation of the number to check
     * @return The very same textual representation
     * @throws IllegalArgumentException if the text is longer than the longest one a key may carry
     */
    private static String boundedLength(String property, String number) {
        if (number.length() > MAX_NUMBER_LENGTH) {
            throw new IllegalArgumentException("Cannot build a cursor on property %s: number of %d characters is longer than %d".formatted(property, number.length(), MAX_NUMBER_LENGTH));
        }
        return number;
    }

    /**
     * Checks that a value belongs to a type the parsers can read back, so that an unusable key is refused while
     * the token is still being built rather than once the consumer sends it back, and so that the default
     * {@code toString} of an association, an embeddable or a converted value never leaks into a token.
     *
     * @param property The property the value belongs to, used to name it in the error message
     * @param value    The value to check
     * @return The very same value
     * @throws IllegalArgumentException if no parser can read the value back
     */
    private static Object parsable(String property, Object value) {
        if (PARSERS.keySet().stream().noneMatch(type -> type.isInstance(value))) {
            throw new IllegalArgumentException("Cannot build a cursor on property %s: %s is not a supported cursor key type".formatted(property, value.getClass().getName()));
        }
        return value;
    }

    /**
     * Parses a textual cursor key back into the Java type the metamodel reports for the attribute.
     * <p>
     * The type is only known at runtime, from the path being sought on, so the returned value cannot be checked
     * by the compiler: it holds by construction, each type being read by the parser registered for it.
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
            // The value comes from a token an API consumer sent back, so whatever the parser throws is a malformed
            // cursor, which the API layer answers with a 400 Bad Request
            throw new IllegalArgumentException("Invalid cursor key value %s for type %s".formatted(value, type.getName()), e);
        }
    }

    private static Boolean parseBoolean(String value) {
        return switch (value) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            // Boolean#valueOf would silently read any other text as false
            default -> throw new IllegalArgumentException("Expected true or false, got " + value);
        };
    }

    private static Character parseCharacter(String value) {
        if (value.length() != 1) {
            throw new IllegalArgumentException("Expected a single character, got " + value.length());
        }
        return value.charAt(0);
    }

    private static BigDecimal parseDecimal(String value) {
        requireParsableLength(value);
        BigDecimal decimal = new BigDecimal(value);
        if (Math.abs((long) decimal.scale()) > MAX_DECIMAL_SCALE) {
            // Refused before a driver expands such an exponent into as many digits to bind it
            throw new IllegalArgumentException("Expected a scale of at most %d either way, got %d".formatted(MAX_DECIMAL_SCALE, decimal.scale()));
        }
        return decimal;
    }

    private static BigInteger parseInteger(String value) {
        requireParsableLength(value);
        return new BigInteger(value);
    }

    private static void requireParsableLength(String number) {
        if (number.length() > MAX_NUMBER_LENGTH) {
            // Refused before the parser spends the square of the length on it
            throw new IllegalArgumentException("Expected a number of at most %d characters, got %d".formatted(MAX_NUMBER_LENGTH, number.length()));
        }
    }

}
