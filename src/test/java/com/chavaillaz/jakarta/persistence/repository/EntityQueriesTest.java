package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa.tasting;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BLUE_MOUNTAIN;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.hibernate.query.restriction.Restriction.equal;
import static org.hibernate.query.restriction.Restriction.unrestricted;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import org.hibernate.query.restriction.Restriction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

@DisplayName("EntityQueries")
class EntityQueriesTest extends HibernateTest {

    private static final BiFunction<CriteriaBuilder, Root<CoffeeEntity>, List<Order>> BY_NAME =
            (builder, root) -> List.of(builder.asc(root.get("name")));

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void brewTheMenu() {
        runInTransaction(Coffees::persistMenu);
    }

    private <T> T withQueries(BiFunction<EntityQueries<CoffeeEntity>, RepositoryContext<CoffeeEntity>, T> action) {
        return inTransaction(entityManager ->
                action.apply(EntityQueries.of(CoffeeEntity.class), new TestContext<>(entityManager, BY_NAME, Map.of())));
    }

    @Test
    @DisplayName("treats a null restriction as unrestricted")
    void treatsNullAsUnrestricted() {
        assertThat((long) withQueries((queries, context) -> queries.count(context, null, null))).isEqualTo(7);
        assertThat((long) withQueries((queries, context) -> queries.count(context, unrestricted(), null))).isEqualTo(7);
    }

    @Test
    @DisplayName("combines the restriction and the criteria")
    void combinesTheRestrictionAndTheCriteria() {
        List<CoffeeEntity> coffees = withQueries((queries, context) -> queries
                .search(context, equal(CoffeeEntity_.origin, ETHIOPIA), tasting("Citrus"), Pageable.UNPAGED)
                .items());

        assertThat(namesOf(coffees)).containsExactly(SIDAMO, YIRGACHEFFE);
        assertThat((long) withQueries((queries, context) -> queries.count(context, equal(CoffeeEntity_.origin, ETHIOPIA), tasting("Citrus")))).isEqualTo(2);
    }

    @Test
    @DisplayName("counts from the very same query as the results")
    void countsFromTheSameQuery() {
        PaginationResult<CoffeeEntity> result = withQueries((queries, context) -> queries.search(context, equal(CoffeeEntity_.roast, Roast.LIGHT), null, Pageable.of(0, 1)));

        assertThat(result.items()).hasSize(1);
        assertThat(result.totalItems()).isEqualTo(3);
    }

    @Test
    @DisplayName("returns everything as a single page when unpaged")
    void returnsASinglePage() {
        PaginationResult<CoffeeEntity> result = withQueries((queries, context) -> queries.search(context, null, null, Pageable.UNPAGED));

        assertThat(result.items()).hasSize(7);
        assertThat(result.totalItems()).isEqualTo(7);
    }

    @Test
    @DisplayName("fetches only the first row of the first method")
    void fetchesOnlyTheFirstRow() {
        assertThat((Optional<CoffeeEntity>) withQueries((queries, context) -> queries.first(context, null, null, Sort.parse("-price"))))
                .hasValueSatisfying(coffee -> assertThat(coffee.getName()).isEqualTo(GEISHA));
        assertThat((Optional<CoffeeEntity>) withQueries((queries, context) -> queries.first(context, null, null, Sort.NONE)))
                .as("the default ordering applies when none is requested")
                .hasValueSatisfying(coffee -> assertThat(coffee.getName()).isEqualTo(BLUE_MOUNTAIN));
        assertThat((Optional<CoffeeEntity>) withQueries((queries, context) -> queries.first(context, equal(CoffeeEntity_.origin, "Mars"), null, Sort.NONE)))
                .isEmpty();
    }

    @Test
    @DisplayName("searches a related type")
    void searchesARelatedType() {
        List<TastingNoteEntity> notes = withQueries((queries, context) -> queries.search(context, TastingNoteEntity.class, null));
        assertThat(notes).hasSize(9);

        List<RoasterEntity> roasters = withQueries((queries, context) -> queries.search(context, RoasterEntity.class, equal(RoasterEntity_.country, ETHIOPIA)));
        assertThat(roasters).extracting(RoasterEntity::getName).containsExactly("Kaldi Roasting");
    }

    @Test
    @DisplayName("scrolls with the restriction, the criteria and the seek predicate combined")
    void scrollsWithEverythingCombined() {
        Restriction<CoffeeEntity> ethiopian = equal(CoffeeEntity_.origin, ETHIOPIA);
        Criteria<CoffeeEntity> citrus = tasting("Citrus");

        CursorResult<CoffeeEntity> first = withQueries((queries, context) -> queries.scroll(context, ethiopian, citrus, Cursor.first(1, Sort.NONE)));
        assertThat(Coffees.namesOf(first)).containsExactly(SIDAMO);
        assertThat(first.hasNext()).isTrue();

        CursorResult<CoffeeEntity> second = withQueries((queries, context) -> queries.scroll(context, ethiopian, citrus, Cursor.of(first.next(), 1, Sort.NONE)));
        assertThat(Coffees.namesOf(second)).containsExactly(YIRGACHEFFE);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("rejects scrolling on a key type no cursor can carry, on the very first page")
    void rejectsAnUnusableCursorKey() {
        assertThatIllegalArgumentException()
                .as("ordering on the association itself makes a perfectly valid SQL ordering, but no cursor key")
                .isThrownBy(() -> withQueries((queries, context) -> queries.scroll(context, null, null, Cursor.first(2, Sort.parse("roaster")))))
                .withMessageContaining("Cannot build a cursor on the property roaster");

        assertThat(withQueries((queries, context) -> queries.search(context, null, null, Pageable.of(0, 2, Sort.parse("roaster")))).items())
                .as("the offset pagination keeps ordering on it, only a cursor needs to read the key back")
                .hasSize(2);
    }

    @Test
    @DisplayName("orders through the join the criteria already made, rather than joining the association twice")
    void ordersThroughTheJoinOfTheCriteria() {
        recordStatements();
        PaginationResult<CoffeeEntity> result = withQueries((queries, context) -> queries.search(
                context,
                null,
                (criteriaBuilder, query, root) -> criteriaBuilder.equal(root.join(CoffeeEntity_.roaster).get(RoasterEntity_.COUNTRY), ETHIOPIA),
                Pageable.of(0, 10, Sort.parse("roaster.name"))));

        assertThat(namesOf(result.items())).containsExactly(HARRAR, SIDAMO, YIRGACHEFFE);
        assertThat(statements())
                .filteredOn(sql -> sql.startsWith("select ce1_0"))
                .singleElement(as(STRING))
                .as("the ordering reaches the roaster through the join the criteria already made")
                .containsOnlyOnce("join roaster");
    }

    @Test
    @DisplayName("rejects an ordering on an unknown property")
    void rejectsAnUnknownOrdering() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withQueries((queries, context) -> queries.search(context, null, null, Pageable.of(0, 3, Sort.parse("caffeine")))))
                .withMessageContaining("unknown property caffeine");
    }

}