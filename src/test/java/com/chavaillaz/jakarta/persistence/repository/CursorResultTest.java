package com.chavaillaz.jakarta.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CursorResult")
class CursorResultTest {

    private static final List<String> BEANS = List.of("Arabica", "Robusta");

    @Test
    @DisplayName("builds an empty page, no navigation being possible")
    void buildsAnEmptyPage() {
        CursorResult<String> result = CursorResult.empty(25);

        assertThat(result.items()).isEmpty();
        assertThat(result.size()).isEqualTo(25);
        assertThat(result.next()).isNull();
        assertThat(result.previous()).isNull();
        assertThat(result.hasNext()).isFalse();
        assertThat(result.hasPrevious()).isFalse();
    }

    @Test
    @DisplayName("converts the items, keeping the tokens and the flags")
    void convertsTheItems() {
        CursorResult<Integer> result =
                new CursorResult<>(BEANS, 2, "next", "previous", true, true).map(String::length);

        assertThat(result.items()).containsExactly(7, 7);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.next()).isEqualTo("next");
        assertThat(result.previous()).isEqualTo("previous");
        assertThat(result.hasNext()).isTrue();
        assertThat(result.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("defends its items against a null list and against a later modification")
    void defendsItsItems() {
        assertThat(new CursorResult<String>(null, 10, null, null, false, false).items()).isEmpty();

        List<String> mutable = new ArrayList<>(BEANS);
        CursorResult<String> result = new CursorResult<>(mutable, 10, null, null, false, false);
        mutable.clear();

        assertThat(result.items()).containsExactly("Arabica", "Robusta");
        assertThatThrownBy(() -> result.items().add("Liberica"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

}