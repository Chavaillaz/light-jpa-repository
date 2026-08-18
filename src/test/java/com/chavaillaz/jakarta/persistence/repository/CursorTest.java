package com.chavaillaz.jakarta.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Cursor")
class CursorTest {

    @ParameterizedTest(name = "a requested size of {0} becomes {1}")
    @CsvSource({
            "1,    1",
            "50,   50",
            "1000, 1000",
            "1001, 1000",
            "99999,1000",
            "0,    50",
            "-1,   50",
    })
    @DisplayName("normalises the requested size, so that a single call cannot drain the table")
    void normalisesTheSize(int requested, int expected) {
        assertThat(new Cursor(null, requested, Sort.NONE).size()).isEqualTo(expected);
        assertThat(Cursor.first(requested, Sort.NONE).size()).isEqualTo(expected);
    }

    @Test
    @DisplayName("applies the default size when none is requested")
    void appliesTheDefaultSize() {
        assertThat(Cursor.of("token", null, Sort.NONE).size()).isEqualTo(Cursor.DEFAULT_SIZE);
        assertThat(Cursor.first(null, Sort.NONE).size()).isEqualTo(Cursor.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("defaults the ordering to none rather than to null")
    void defaultsTheOrdering() {
        assertThat(new Cursor(null, 10, null).sort()).isEqualTo(Sort.NONE);
        assertThat(Cursor.of("token", 10, null).sort()).isEqualTo(Sort.NONE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t" })
    @DisplayName("requests the first page when no token is given")
    void requestsTheFirstPage(String token) {
        assertThat(Cursor.of(token, 10, Sort.NONE).isFirst()).isTrue();
    }

    @Test
    @DisplayName("is not the first page as soon as a token is given")
    void isNotTheFirstPage() {
        assertThat(Cursor.of("token", 10, Sort.NONE).isFirst()).isFalse();
        assertThat(Cursor.first(10, Sort.NONE).isFirst()).isTrue();
    }

    @Test
    @DisplayName("fetches one extra row, which is what reveals the following page")
    void fetchesOneExtraRow() {
        assertThat(Cursor.first(10, Sort.NONE).limit()).isEqualTo(11);
        assertThat(Cursor.first(Cursor.MAX_SIZE, Sort.NONE).limit()).isEqualTo(Cursor.MAX_SIZE + 1);
        assertThat(Cursor.first(0, Sort.NONE).limit()).isEqualTo(Cursor.DEFAULT_SIZE + 1);
    }

    @Test
    @DisplayName("drops the position when the ordering changes, the seek no longer applying")
    void dropsThePositionOnANewOrdering() {
        Cursor cursor = Cursor.of("token", 10, Sort.parse("name")).withSort(Sort.parse("-price"));

        assertThat(cursor.token()).isNull();
        assertThat(cursor.isFirst()).isTrue();
        assertThat(cursor.size()).isEqualTo(10);
        assertThat(cursor.sort()).hasToString("-price");
    }

}