package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.HarvestEntity.harvest;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.HarvestEntity;
import com.chavaillaz.jakarta.persistence.repository.example.HarvestEntity.HarvestId;
import com.chavaillaz.jakarta.persistence.repository.example.HarvestRepositoryJpa;

/**
 * Exercises the lookups by identifier against an entity with an {@code @IdClass}, whose identifier no single
 * attribute holds, so that no plain {@code IN} predicate on one attribute can look it up.
 */
@DisplayName("Looking up an entity with an identifier class")
class HarvestLookupTest extends HibernateTest {

    private static final String FARM = "Finca Deborah";

    private static final int PLOT = 7;

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(HarvestEntity.class);
    }

    @BeforeEach
    void bringInTheHarvests() {
        runInTransaction(entityManager -> {
            for (int season = 2020; season <= 2024; season++) {
                entityManager.persist(harvest(id(season)));
            }
        });
    }

    private static HarvestId id(int season) {
        return new HarvestId(FARM, PLOT, season);
    }

    private static List<HarvestId> idsOf(List<HarvestEntity> harvests) {
        return harvests.stream().map(HarvestEntity::getId).toList();
    }

    @Test
    @DisplayName("looks the identifiers up by chunks, not with one query per identifier")
    void looksUpByChunks() {
        List<HarvestId> known = List.of(id(2020), id(2021), id(2022), id(2023), id(2024));
        List<HarvestId> requested = List.of(id(2020), id(2021), id(2022), id(2023), id(2024), id(2019), id(2020));

        List<HarvestEntity> found = inTransaction(entityManager -> {
            statistics().clear();
            return new SmallBatchHarvestRepository(entityManager).findAllById(requested);
        });

        assertThat(idsOf(found))
                .as("the unknown identifier is skipped, the duplicated one is looked up once")
                .containsExactlyInAnyOrderElementsOf(known);
        assertThat(statistics().getPrepareStatementCount())
                .as("six distinct identifiers looked up two by two")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("takes the entities the persistence context holds as they are, only querying the others")
    void reusesTheManagedEntities() {
        runInTransaction(entityManager -> {
            SmallBatchHarvestRepository repository = new SmallBatchHarvestRepository(entityManager);
            HarvestEntity first = repository.getById(id(2020));
            HarvestEntity second = repository.getById(id(2021));
            statistics().clear();

            List<HarvestEntity> found = repository.findAllById(List.of(id(2020), id(2021), id(2022)));

            assertThat(found).hasSize(3).contains(first, second);
            assertThat(statistics().getPrepareStatementCount())
                    .as("the one identifier not managed yet is the only one queried")
                    .isEqualTo(1);
        });
    }

    @Test
    @DisplayName("binds at most 2000 parameters per query, each identifier binding one per column")
    void boundsTheParametersPerQuery() {
        List<HarvestId> ids = IntStream.range(0, 700)
                .mapToObj(season -> new HarvestId("Finca Soledad", PLOT, season))
                .toList();
        runInTransaction(entityManager -> ids.forEach(id -> entityManager.persist(harvest(id))));
        recordStatements();

        List<HarvestEntity> found = inTransaction(entityManager -> new HarvestRepositoryJpa(entityManager).findAllById(ids));

        assertThat(found).hasSize(700);
        assertThat(statements())
                .as("seven hundred identifiers of three columns, which a single query binds 2100 parameters for")
                .hasSize(2)
                .allSatisfy(sql -> assertThat(sql.chars().filter(character -> character == '?').count()).isLessThanOrEqualTo(2000));
    }

    /**
     * A repository looking the identifiers up two by two, so that the chunking is exercised on a handful of rows.
     */
    static class SmallBatchHarvestRepository extends HarvestRepositoryJpa {

        SmallBatchHarvestRepository(EntityManager entityManager) {
            super(entityManager);
        }

        @Override
        protected int idBatchSize() {
            return 2;
        }

    }

}
