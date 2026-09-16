package com.chavaillaz.jakarta.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Pageable")
class PageableTest {

    @Test
    @DisplayName("defaults the ordering to none rather than to null")
    void defaultsTheOrdering() {
        assertThat(new Pageable(0, 10, null).sort()).isEqualTo(Sort.NONE);
        assertThat(Pageable.of(0, 10).sort()).isEqualTo(Sort.NONE);
        assertThat(Pageable.unpaged().sort()).isEqualTo(Sort.NONE);
    }

    @Test
    @DisplayName("exposes an unpaged singleton")
    void exposesAnUnpagedSingleton() {
        assertThat(Pageable.unpaged()).isSameAs(Pageable.UNPAGED);
        assertThat(Pageable.UNPAGED.isPaginated()).isFalse();
        assertThat(Pageable.UNPAGED.page()).isEqualTo(Pageable.NO_PAGINATION);
        assertThat(Pageable.UNPAGED.size()).isEqualTo(Pageable.NO_PAGINATION);
    }

    @Test
    @DisplayName("normalizes a missing and an invalid coordinate to the very same value")
    void normalizesTheCoordinates() {
        assertThat(Pageable.of(null, null)).isEqualTo(Pageable.of(-1, 0));
        assertThat(Pageable.of(-3, 10).page()).isEqualTo(Pageable.NO_PAGINATION);
        assertThat(Pageable.of(0, -5).size()).isEqualTo(Pageable.NO_PAGINATION);
    }

    @Test
    @DisplayName("caps the requested size, so that a single call cannot drain the table")
    void capsTheRequestedSize() {
        assertThat(Pageable.of(0, Pageable.MAX_SIZE + 1).size()).isEqualTo(Pageable.MAX_SIZE);
    }

    @ParameterizedTest(name = "page {0} of size {1} is paginated: {2}")
    @CsvSource(nullValues = "null", value = {
            "0,    10,   true",
            "3,    1,    true",
            "null, 10,   false",
            "0,    null, false",
            "null, null, false",
            "-1,   10,   false",
            "0,    0,    false",
            "0,    -5,   false",
    })
    @DisplayName("is only paginated when both coordinates are set and valid")
    void detectsThePagination(Integer page, Integer size, boolean paginated) {
        assertThat(Pageable.of(page, size).isPaginated()).isEqualTo(paginated);
    }

    @Test
    @DisplayName("keeps the coordinates when the ordering is replaced")
    void replacesTheOrdering() {
        Pageable pageable = Pageable.of(2, 10, Sort.parse("name")).withSort(Sort.parse("-price"));

        assertThat(pageable.page()).isEqualTo(2);
        assertThat(pageable.size()).isEqualTo(10);
        assertThat(pageable.sort()).hasToString("-price");
    }

    @Test
    @DisplayName("keeps the ordering of an unpaged request")
    void sortsWithoutPaginating() {
        Pageable pageable = Pageable.sortedBy(Sort.parse("-price"));

        assertThat(pageable.isPaginated()).isFalse();
        assertThat(pageable.sort()).hasToString("-price");
    }

    @Test
    @DisplayName("only falls back on the default coordinates when they are missing")
    void fallsBackOnTheDefaultCoordinates() {
        assertThat(Pageable.of(null, null, Sort.parse("name")).orDefault(0, 20))
                .isEqualTo(Pageable.of(0, 20, Sort.parse("name")));
        assertThat(Pageable.of(3, null).orDefault(0, 20)).isEqualTo(Pageable.of(3, 20));
        assertThat(Pageable.of(null, 5).orDefault(0, 20)).isEqualTo(Pageable.of(0, 5));
        assertThat(Pageable.of(3, 5).orDefault(0, 20)).isEqualTo(Pageable.of(3, 5));
    }

    @Test
    @DisplayName("replaces an invalid coordinate as it replaces a missing one, rather than returning everything")
    void repairsAnInvalidCoordinate() {
        assertThat(Pageable.of(-1, 10).orDefault(0, 20)).isEqualTo(Pageable.of(0, 10));
        assertThat(Pageable.of(3, 0).orDefault(0, 20)).isEqualTo(Pageable.of(3, 20));
        assertThat(Pageable.of(-1, 10).orDefault(0, 20).isPaginated()).isTrue();
    }

    @ParameterizedTest(name = "page {0} of size {1} is refused")
    @CsvSource({"-1, 20", "0, 0", "0, -5"})
    @DisplayName("refuses a default that addresses no page, rather than returning everything")
    void refusesADefaultAddressingNoPage(int defaultPage, int defaultSize) {
        assertThatIllegalArgumentException()
                .as("even when the requested coordinates need no default")
                .isThrownBy(() -> Pageable.of(3, 5).orDefault(defaultPage, defaultSize))
                .withMessageContaining("must address a page");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Pageable.of(null, null).orDefault(defaultPage, defaultSize));
    }

    @Test
    @DisplayName("stays unpaged when no default is asked for, whatever the coordinates")
    void staysUnpagedWithoutADefault() {
        assertThat(Pageable.of(-1, 10).isPaginated()).isFalse();
        assertThat(Pageable.of(3, null).isPaginated()).isFalse();
    }

}
