package com.chavaillaz.jakarta.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("PaginationResult")
class PaginationResultTest {

    private static final List<String> BEANS = List.of("Arabica", "Robusta");

    @ParameterizedTest(name = "{2} items of {1} per page make {3} pages")
    @CsvSource({
            "0, 3, 7, 3",
            "0, 3, 6, 2",
            "0, 3, 0, 0",
            "0, 3, 1, 1",
            "0, 1, 7, 7",
            "0, 0, 7, 0",
            "0, -1, 7, 0",
    })
    @DisplayName("derives the total number of pages, rounding up")
    void derivesTheTotalPages(int page, int size, long total, int expected) {
        assertThat(PaginationResult.of(List.of(), page, size, total).totalPages()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "page {0} of {2} items by {1}: next={3}, previous={4}")
    @CsvSource({
            "0, 3, 7, true,  false",
            "1, 3, 7, true,  true",
            "2, 3, 7, false, true",
            "0, 3, 3, false, false",
            "0, 3, 0, false, false",
    })
    @DisplayName("derives the navigation flags")
    void derivesTheNavigationFlags(int page, int size, long total, boolean next, boolean previous) {
        PaginationResult<String> result = PaginationResult.of(List.of(), page, size, total);

        assertThat(result.hasNext()).isEqualTo(next);
        assertThat(result.hasPrevious()).isEqualTo(previous);
    }

    @Test
    @DisplayName("builds a single page holding all the items")
    void buildsASinglePage() {
        PaginationResult<String> result = PaginationResult.single(BEANS);

        assertThat(result.items()).isEqualTo(BEANS);
        assertThat(result.currentPage()).isZero();
        assertThat(result.pageSize()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.totalItems()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.hasPrevious()).isFalse();
    }

    @Test
    @DisplayName("keeps a page size of at least one when the single page is empty")
    void buildsAnEmptySinglePage() {
        PaginationResult<String> result = PaginationResult.single(List.of());

        assertThat(result.items()).isEmpty();
        assertThat(result.pageSize()).as("a zero size would make the total number of pages meaningless").isEqualTo(1);
        assertThat(result.totalItems()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    @DisplayName("builds an empty page at the requested coordinates")
    void buildsAnEmptyPage() {
        PaginationResult<String> result = PaginationResult.empty(2, 10);

        assertThat(result.items()).isEmpty();
        assertThat(result.currentPage()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.totalItems()).isZero();
        assertThat(result.hasNext()).isFalse();
        assertThat(result.hasPrevious())
                .as("an empty page beyond the first one still has preceding ones to go back to")
                .isTrue();
    }

    @Test
    @DisplayName("converts the items, keeping the coordinates")
    void convertsTheItems() {
        PaginationResult<Integer> result = PaginationResult.of(BEANS, 1, 2, 7).map(String::length);

        assertThat(result.items()).containsExactly(7, 7);
        assertThat(result.currentPage()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(4);
        assertThat(result.totalItems()).isEqualTo(7);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("defends its items against a null list and against a later modification")
    void defendsItsItems() {
        assertThat(PaginationResult.of(null, 0, 10, 0).items()).isEmpty();

        List<String> mutable = new ArrayList<>(BEANS);
        PaginationResult<String> result = PaginationResult.of(mutable, 0, 10, 2);
        mutable.clear();

        assertThat(result.items()).containsExactly("Arabica", "Robusta");
        assertThatThrownBy(() -> result.items().add("Liberica"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

}