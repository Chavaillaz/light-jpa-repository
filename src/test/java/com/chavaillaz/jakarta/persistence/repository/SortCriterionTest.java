package com.chavaillaz.jakarta.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("SortCriterion")
class SortCriterionTest {

    @ParameterizedTest(name = "\"{0}\" is parsed as {1} {2}")
    @CsvSource({
            "name,name,true",
            "-name,name,false",
            "+name,name,true",
            "' -price ',price,false",
            "roaster.name,roaster.name,true",
            "_internal,_internal,true",
            "grade2,grade2,true",
    })
    @DisplayName("parses a criterion")
    void parsesACriterion(String value, String property, boolean ascending) {
        SortCriterion criterion = SortCriterion.parse(value);

        assertThat(criterion.property()).isEqualTo(property);
        assertThat(criterion.ascending()).isEqualTo(ascending);
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "1name",
            "na me",
            "name;drop",
            "roaster..name",
            "roaster.",
            ".name",
            "name)",
            "-",
    })
    @DisplayName("rejects a property that is not a plain attribute path")
    void rejectsAnInvalidProperty(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SortCriterion.parse(value))
                .withMessageStartingWith("Invalid sort property");
    }

    @Test
    @DisplayName("rejects a null property")
    void rejectsANullProperty() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SortCriterion(null, true));
    }

    @Test
    @DisplayName("builds an ascending and a descending criterion")
    void buildsACriterion() {
        assertThat(SortCriterion.asc("name")).hasToString("name");
        assertThat(SortCriterion.desc("name")).hasToString("-name");
    }

    @Test
    @DisplayName("reverses its direction, keeping its property")
    void reversesItsDirection() {
        assertThat(SortCriterion.asc("price").reversed()).isEqualTo(SortCriterion.desc("price"));
        assertThat(SortCriterion.desc("price").reversed().reversed()).isEqualTo(SortCriterion.desc("price"));
    }

    @Test
    @DisplayName("round trips through its textual representation")
    void roundTrips() {
        assertThat(SortCriterion.parse(SortCriterion.desc("roaster.name").toString()))
                .isEqualTo(SortCriterion.desc("roaster.name"));
    }

}