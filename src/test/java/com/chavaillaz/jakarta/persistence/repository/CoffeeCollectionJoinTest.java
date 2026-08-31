package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa.joiningNotes;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BOURBON_POINTU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
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

    private <T> T withQueries(Function<EntityQueries<CoffeeEntity>, T> action) {
        return inTransaction(entityManager -> {
            EntityOrdering<CoffeeEntity> ordering = new EntityOrdering<>(entityManager, CoffeeEntity.class,
                    (builder, root) -> List.of(builder.asc(root.get("name"))), Map::of);
            recordStatements();
            return action.apply(new EntityQueries<>(entityManager, CoffeeEntity.class, ordering, CursorCodec.DEFAULT, CursorKeyCodec.DEFAULT));
        });
    }

    private static List<String> namesOf(List<CoffeeEntity> coffees) {
        return coffees.stream().map(CoffeeEntity::getName).toList();
    }

    @Test
    @DisplayName("returns each entity once, whatever the number of matching children")
    void returnsEachEntityOnce() {
        PaginationResult<CoffeeEntity> result =
                withQueries(queries -> queries.search(null, joiningNotes(FLAVOURS), Pageable.UNPAGED));

        assertThat(namesOf(result.items())).containsExactlyElementsOf(MATCHING);
    }

    @Test
    @DisplayName("counts each entity once as well")
    void countsEachEntityOnce() {
        long count = withQueries(queries -> queries.count(null, joiningNotes(FLAVOURS)));

        assertThat(count).isEqualTo(MATCHING.size());
    }

    @Test
    @DisplayName("paginates without losing a row to a duplicated one")
    void paginatesWithoutDuplicates() {
        PaginationResult<CoffeeEntity> first = withQueries(queries -> queries.search(null, joiningNotes(FLAVOURS), Pageable.of(0, 2)));
        PaginationResult<CoffeeEntity> second = withQueries(queries -> queries.search(null, joiningNotes(FLAVOURS), Pageable.of(1, 2)));

        assertThat(namesOf(first.items())).containsExactly(BOURBON_POINTU, GEISHA);
        assertThat(namesOf(second.items())).containsExactly(SIDAMO, YIRGACHEFFE);
        assertThat(first.totalItems()).isEqualTo(4);
        assertThat(first.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("scrolls without losing a row to a duplicated one")
    void scrollsWithoutDuplicates() {
        CursorResult<CoffeeEntity> first = withQueries(queries -> queries.scroll(null, joiningNotes(FLAVOURS), Cursor.first(2, Sort.NONE)));
        CursorResult<CoffeeEntity> second = withQueries(queries ->
                queries.scroll(null, joiningNotes(FLAVOURS), Cursor.of(first.next(), 2, Sort.NONE)));

        assertThat(namesOf(first.items())).containsExactly(BOURBON_POINTU, GEISHA);
        assertThat(namesOf(second.items())).containsExactly(SIDAMO, YIRGACHEFFE);
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    @DisplayName("issues no distinct, which no database agrees on once the ordering reaches a join")
    void issuesNoDistinct() {
        withQueries(queries -> queries.search(null, joiningNotes(FLAVOURS), Pageable.of(0, 2)));

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
        PaginationResult<CoffeeEntity> result = withQueries(queries ->
                queries.search(null, joiningNotes(FLAVOURS), Pageable.of(0, 4, Sort.parse("roaster.name,name"))));

        assertThat(namesOf(result.items()))
                .as("Kaldi Roasting first, then Moka Brothers, each by name")
                .containsExactly(SIDAMO, YIRGACHEFFE, BOURBON_POINTU, GEISHA);
        assertThat(result.totalItems()).isEqualTo(4);
    }

}
