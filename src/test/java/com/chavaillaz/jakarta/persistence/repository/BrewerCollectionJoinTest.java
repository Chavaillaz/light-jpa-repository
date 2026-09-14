package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.EspressoMachineEntity.machine;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.BrewerEntity;
import com.chavaillaz.jakarta.persistence.repository.example.DrinkEntity;
import com.chavaillaz.jakarta.persistence.repository.example.DrinkEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.EspressoMachineEntity;
import com.chavaillaz.jakarta.persistence.repository.example.EspressoMachineEntity_;

/**
 * A collection declared by a subtype is only reached through a treat, and Hibernate keeps the joins of a treat
 * apart from those of the root or join it downcasts: the semi join has to look for them there as well.
 */
@DisplayName("Filtering through a collection joined behind a treat")
class BrewerCollectionJoinTest extends HibernateTest {

    /**
     * The Gaggia brews both, so a plain join returns it twice.
     */
    private static final List<String> DRINKS = List.of("Ristretto", "Lungo");

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(BrewerEntity.class, EspressoMachineEntity.class, DrinkEntity.class);
    }

    @BeforeEach
    void installTheBrewers() {
        BrewerEntity mokaPot = new BrewerEntity();
        mokaPot.setName("Moka Pot");
        persist(mokaPot,
                machine("Gaggia Classic", "Ristretto", "Lungo"),
                machine("La Marzocco", "Ristretto"),
                machine("Rancilio Silvia", "Lungo", "Americano"));
    }

    private <T> T withQueries(BiFunction<EntityQueries<BrewerEntity>, RepositoryContext<BrewerEntity>, T> action) {
        return inTransaction(entityManager -> action.apply(EntityQueries.of(BrewerEntity.class),
                new TestContext<>(entityManager, (builder, root) -> List.of(builder.asc(root.get("name"))), Map.of())));
    }

    private static List<String> namesOf(List<BrewerEntity> brewers) {
        return brewers.stream().map(BrewerEntity::getName).toList();
    }

    /**
     * Joins the drinks of the espresso machines, which only a treat of the root reaches: a machine is returned once
     * per matching drink.
     */
    private static Criteria<BrewerEntity> brewing(List<String> drinks) {
        return (criteriaBuilder, query, root) -> criteriaBuilder.treat(root, EspressoMachineEntity.class)
                .join(EspressoMachineEntity_.drinks)
                .get(DrinkEntity_.name)
                .in(drinks);
    }

    @Test
    @DisplayName("returns, counts and scrolls each entity once through a collection joined behind a treat")
    void semiJoinsATreatedCollectionJoin() {
        PaginationResult<BrewerEntity> page = withQueries((queries, context) -> queries.search(context, null, brewing(DRINKS), Pageable.of(0, 2)));
        CursorResult<BrewerEntity> scrolled = withQueries((queries, context) -> queries.scroll(context, null, brewing(DRINKS), Cursor.first(2, Sort.NONE)));
        long count = withQueries((queries, context) -> queries.count(context, null, brewing(DRINKS)));

        assertThat(namesOf(page.items())).containsExactly("Gaggia Classic", "La Marzocco");
        assertThat(page.totalItems()).isEqualTo(3);
        assertThat(namesOf(scrolled.items())).containsExactly("Gaggia Classic", "La Marzocco");
        assertThat(count).isEqualTo(3);
    }

}
