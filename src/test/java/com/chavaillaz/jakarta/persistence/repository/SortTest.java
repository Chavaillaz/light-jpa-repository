package com.chavaillaz.jakarta.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Sort")
class SortTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t" })
    @DisplayName("parses a blank ordering as none")
    void parsesABlankOrdering(String value) {
        assertThat(Sort.parse(value)).isEqualTo(Sort.NONE);
        assertThat(Sort.parse(value).isEmpty()).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" is parsed as \"{1}\"")
    @CsvSource(delimiter = ';', value = {
            "name              ; name",
            "-price            ; -price",
            "+price            ; price",
            "-price,name       ; -price,name",
            "roaster.name      ; roaster.name",
            "name,,price       ; name,price",
            "' name , -price ' ; name,-price",
    })
    @DisplayName("parses a comma separated ordering")
    void parsesAnOrdering(String value, String expected) {
        assertThat(Sort.parse(value)).hasToString(expected);
    }

    @Test
    @DisplayName("is immutable, whatever the given list")
    void isImmutable() {
        List<SortCriterion> criteria = new ArrayList<>(List.of(SortCriterion.asc("name")));
        Sort sort = new Sort(criteria);

        criteria.clear();

        assertThat(sort.criteria()).hasSize(1);
        assertThatThrownBy(() -> sort.criteria().add(SortCriterion.asc("price")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("accepts a null list of criteria")
    void acceptsANullList() {
        assertThat(new Sort(null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("reverses every criterion")
    void reversesEveryCriterion() {
        assertThat(Sort.parse("-price,name,id").reversed()).hasToString("price,-name,-id");
        assertThat(Sort.NONE.reversed()).isEqualTo(Sort.NONE);
        assertThat(Sort.parse("name").reversed().reversed()).hasToString("name");
    }

    @Test
    @DisplayName("is built from criteria")
    void isBuiltFromCriteria() {
        assertThat(Sort.of(SortCriterion.desc("price"), SortCriterion.asc("name"))).hasToString("-price,name");
        assertThat(Sort.of()).isEqualTo(Sort.NONE);
    }

    @Test
    @DisplayName("round trips through its textual representation")
    void roundTrips() {
        Sort sort = Sort.parse("-roaster.name,price,id");

        assertThat(Sort.parse(sort.toString())).isEqualTo(sort);
    }

}