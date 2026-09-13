package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa.joiningNotes;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BOURBON_POINTU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.roaster;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.criteria.Join;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

/**
 * A criteria joining a to-many association duplicates the root entity as many times as it has matching children.
 * Compensating that with a {@code distinct} is not portable, so the predicates are moved into a correlated
 * {@code exists} subquery instead, which never duplicates the row in the first place.
 */
@DisplayName("Filtering through a joined collection")
class CoffeeCollectionJoinTest extends HibernateTest {

    /**
     * Yirgacheffe has both a citrus and a floral note, so a plain join returns it twice.
     */
    private static final List<String> FLAVOURS = List.of("Citrus", "Floral");

    /**
     * The coffees having at least one of those notes, in the default ordering of the repository.
     */
    private static final List<String> MATCHING = List.of(BOURBON_POINTU, GEISHA, SIDAMO, YIRGACHEFFE);

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void brewTheMenu() {
        runInTransaction(Coffees::persistMenu);
    }

    private <T> T withQueries(BiFunction<EntityQueries<CoffeeEntity>, RepositoryContext<CoffeeEntity>, T> action) {
        return inTransaction(entityManager -> {
            recordStatements();
            return action.apply(EntityQueries.of(CoffeeEntity.class),
                    new TestContext<>(entityManager, (builder, root) -> List.of(builder.asc(root.get("name"))), Map.of()));
        });
    }

    private static List<String> namesOf(List<CoffeeEntity> coffees) {
        return coffees.stream().map(CoffeeEntity::getName).toList();
    }

    /**
     * An entity join, which follows no association: a coffee is returned once per roaster of its origin, exactly
     * as a collection join returns it once per matching child.
     */
    private static Criteria<CoffeeEntity> roastedAtOrigin() {
        return (criteriaBuilder, query, root) -> {
            Join<CoffeeEntity, RoasterEntity> roaster = root.join(RoasterEntity.class);
            roaster.on(criteriaBuilder.equal(roaster.get(RoasterEntity_.country), root.get(CoffeeEntity_.origin)));
            return criteriaBuilder.isNotNull(roaster.get(RoasterEntity_.name));
        };
    }

    @Test
    @DisplayName("returns each entity once, whatever the number of matching children")
    void returnsEachEntityOnce() {
        PaginationResult<CoffeeEntity> result =
                withQueries((queries, context) -> queries.search(context, null, joiningNotes(FLAVOURS), Pageable.UNPAGED));

        assertThat(namesOf(result.items())).containsExactlyElementsOf(MATCHING);
    }

    @Test
    @DisplayName("counts each entity once as well")
    void countsEachEntityOnce() {
        long count = withQueries((queries, context) -> queries.count(context, null, joiningNotes(FLAVOURS)));

        assertThat(count).isEqualTo(MATCHING.size());
    }

    @Test
    @DisplayName("paginates without losing a row to a duplicated one")
    void paginatesWithoutDuplicates() {
        PaginationResult<CoffeeEntity> first = withQueries((queries, context) -> queries.search(context, null, joiningNotes(FLAVOURS), Pageable.of(0, 2)));
        PaginationResult<CoffeeEntity> second = withQueries((queries, context) -> queries.search(context, null, joiningNotes(FLAVOURS), Pageable.of(1, 2)));

        assertThat(namesOf(first.items())).containsExactly(BOURBON_POINTU, GEISHA);
        assertThat(namesOf(second.items())).containsExactly(SIDAMO, YIRGACHEFFE);
        assertThat(first.totalItems()).isEqualTo(4);
        assertThat(first.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("scrolls without losing a row to a duplicated one")
    void scrollsWithoutDuplicates() {
        CursorResult<CoffeeEntity> first = withQueries((queries, context) -> queries.scroll(context, null, joiningNotes(FLAVOURS), Cursor.first(2, Sort.NONE)));
        CursorResult<CoffeeEntity> second = withQueries((queries, context) ->
                queries.scroll(context, null, joiningNotes(FLAVOURS), Cursor.of(first.next(), 2, Sort.NONE)));

        assertThat(namesOf(first.items())).containsExactly(BOURBON_POINTU, GEISHA);
        assertThat(namesOf(second.items())).containsExactly(SIDAMO, YIRGACHEFFE);
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    @DisplayName("issues no distinct, which no database agrees on once the ordering reaches a join")
    void issuesNoDistinct() {
        withQueries((queries, context) -> queries.search(context, null, joiningNotes(FLAVOURS), Pageable.of(0, 2)));

        assertThat(statements())
                .isNotEmpty()
                .as("the rows are never duplicated, so there is nothing to deduplicate")
                .noneMatch(sql -> sql.toLowerCase().contains("distinct"))
                .as("the join was moved into a correlated exists subquery")
                .allMatch(sql -> sql.toLowerCase().contains("exists"));
    }

    @Test
    @DisplayName("orders on a nested property while filtering through a collection, which a distinct would reject")
    void ordersOnANestedPropertyWhileJoining() {
        // select distinct ... order by roaster.name is rejected by PostgreSQL and Oracle, the ordering
        // expression not being part of the select list; the semi join has no such constraint
        PaginationResult<CoffeeEntity> result = withQueries((queries, context) ->
                queries.search(context, null, joiningNotes(FLAVOURS), Pageable.of(0, 4, Sort.parse("roaster.name,name"))));

        assertThat(namesOf(result.items()))
                .as("Kaldi Roasting first, then Moka Brothers, each by name")
                .containsExactly(SIDAMO, YIRGACHEFFE, BOURBON_POINTU, GEISHA);
        assertThat(result.totalItems()).isEqualTo(4);
    }

    @Test
    @DisplayName("returns, counts and scrolls each entity once through an entity join, which lists no attribute")
    void semiJoinsAnEntityJoin() {
        // A second Ethiopian roaster, so that each Ethiopian coffee is joined to two roasters
        persist(roaster("Addis Roasters", ETHIOPIA));

        PaginationResult<CoffeeEntity> page = withQueries((queries, context) -> queries.search(context, null, roastedAtOrigin(), Pageable.of(0, 2)));
        CursorResult<CoffeeEntity> scrolled = withQueries((queries, context) -> queries.scroll(context, null, roastedAtOrigin(), Cursor.first(2, Sort.NONE)));

        assertThat(namesOf(page.items())).containsExactly(HARRAR, SIDAMO);
        assertThat(page.totalItems()).isEqualTo(3);
        assertThat(namesOf(scrolled.items())).containsExactly(HARRAR, SIDAMO);
    }

}
