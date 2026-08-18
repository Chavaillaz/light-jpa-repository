package com.chavaillaz.jakarta.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.UUID;

import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("CursorValues")
class CursorValuesTest {

    static Arguments[] keys() {
        return new Arguments[] {
                Arguments.of(String.class, "Yirgacheffe"),
                Arguments.of(Boolean.class, true),
                Arguments.of(Character.class, 'C'),
                Arguments.of(Byte.class, (byte) 7),
                Arguments.of(Short.class, (short) 250),
                Arguments.of(Integer.class, 42),
                Arguments.of(Long.class, 9_000_000_000L),
                Arguments.of(Float.class, 1.5F),
                Arguments.of(Double.class, 2.25D),
                Arguments.of(BigDecimal.class, new BigDecimal("25.50")),
                Arguments.of(BigInteger.class, new BigInteger("123456789012345678901234567890")),
                Arguments.of(UUID.class, UUID.fromString("0b1d4f1e-1111-2222-3333-444455556666")),
                Arguments.of(Instant.class, Instant.parse("2024-05-05T06:30:00Z")),
                Arguments.of(LocalDate.class, LocalDate.of(2024, 5, 5)),
                Arguments.of(LocalTime.class, LocalTime.of(6, 30)),
                Arguments.of(LocalDateTime.class, LocalDateTime.of(2024, 5, 5, 6, 30)),
                Arguments.of(OffsetDateTime.class, OffsetDateTime.parse("2024-05-05T06:30:00+02:00")),
                Arguments.of(ZonedDateTime.class, ZonedDateTime.parse("2024-05-05T06:30:00+02:00")),
                Arguments.of(Duration.class, Duration.ofMinutes(4)),
        };
    }

    @ParameterizedTest(name = "a {0} key round trips")
    @MethodSource("keys")
    @DisplayName("formats and parses back every supported type")
    void roundTripsEveryType(Class<?> type, Object value) {
        assertThat(CursorValues.parse(CursorValues.format("brew", value), type)).isEqualTo(value);
    }

    @Test
    @DisplayName("formats a date as its epoch millis, so that the textual ordering matches the temporal one")
    void formatsADate() {
        Date date = new Date(1_715_000_000_000L);

        assertThat(CursorValues.format("roastedAt", date)).isEqualTo("1715000000000");
        assertThat(CursorValues.parse("1715000000000", Date.class)).isEqualTo(date);
    }

    @Test
    @DisplayName("formats an enum as its name and parses it back")
    void roundTripsAnEnum() {
        assertThat(CursorValues.format("roast", Roast.MEDIUM)).isEqualTo("MEDIUM");
        assertThat(CursorValues.parse("MEDIUM", Roast.class)).isEqualTo(Roast.MEDIUM);
    }

    @Test
    @DisplayName("rejects an unknown enum constant")
    void rejectsAnUnknownConstant() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CursorValues.parse("BURNT", Roast.class))
                .withMessageContaining("Unknown constant BURNT");
    }

    @Test
    @DisplayName("parses a primitive type through its wrapper")
    void parsesAPrimitive() {
        assertThat(CursorValues.parse("8", int.class)).isEqualTo(8);
        assertThat(CursorValues.parse("true", boolean.class)).isEqualTo(true);
    }

    @Test
    @DisplayName("rejects a null key, the databases not agreeing on where the nulls sort")
    void rejectsANullKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CursorValues.format("decafLabel", null))
                .withMessage("Cannot build a cursor on the null property decafLabel: a cursor key must be non nullable");
    }

    @Test
    @DisplayName("rejects an unsupported key type")
    void rejectsAnUnsupportedType() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CursorValues.parse("whatever", Object.class))
                .withMessageContaining("Unsupported cursor key type java.lang.Object");
    }

    @Test
    @DisplayName("fails loudly when a value cannot be parsed for its type")
    void failsOnAnInvalidValue() {
        assertThatThrownBy(() -> CursorValues.parse("not-a-number", Integer.class))
                .isInstanceOf(NumberFormatException.class);
    }

}