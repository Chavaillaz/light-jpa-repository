package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.HarvestEntity.harvest;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.List;

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

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(HarvestEntity.class);
    }

    @BeforeEach
    void bringInTheHarvests() {
        runInTransaction(entityManager -> {
            for (int season = 2020; season <= 2024; season++) {
                entityManager.persist(harvest(FARM, season));
            }
        });
    }

    private static List<HarvestId> idsOf(List<HarvestEntity> harvests) {
        return harvests.stream().map(HarvestEntity::getId).toList();
    }

    @Test
    @DisplayName("looks the identifiers up by chunks, not with one query per identifier")
    void looksUpByChunks() {
        List<HarvestId> known = List.of(
                new HarvestId(FARM, 2020), new HarvestId(FARM, 2021), new HarvestId(FARM, 2022),
                new HarvestId(FARM, 2023), new HarvestId(FARM, 2024));
        List<HarvestId> requested = List.of(
                new HarvestId(FARM, 2020), new HarvestId(FARM, 2021), new HarvestId(FARM, 2022),
                new HarvestId(FARM, 2023), new HarvestId(FARM, 2024), new HarvestId(FARM, 2019),
                new HarvestId(FARM, 2020));

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
            HarvestEntity first = repository.getById(new HarvestId(FARM, 2020));
            HarvestEntity second = repository.getById(new HarvestId(FARM, 2021));
            statistics().clear();

            List<HarvestEntity> found = repository.findAllById(List.of(
                    new HarvestId(FARM, 2020), new HarvestId(FARM, 2021), new HarvestId(FARM, 2022)));

            assertThat(found).hasSize(3).contains(first, second);
            assertThat(statistics().getPrepareStatementCount())
                    .as("the one identifier not managed yet is the only one queried")
                    .isEqualTo(1);
        });
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
