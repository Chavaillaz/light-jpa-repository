package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BLUE_MOUNTAIN;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BOURBON_POINTU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.KONA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.coffee;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

@DisplayName("Checking the existence and deleting or saving in bulk")
class CoffeeBulkTest extends HibernateTest {

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void brewTheMenu() {
        runInTransaction(Coffees::persistMenu);
    }

    private <T> T withRepository(Function<CoffeeRepositoryJpa, T> action) {
        return inTransaction(entityManager -> action.apply(new CoffeeRepositoryJpa(entityManager)));
    }

    private List<String> remainingNames() {
        return withRepository(repository -> namesOf(repository.findAll()));
    }

    private long remainingCount() {
        return withRepository(repository -> repository.count());
    }

    private int remainingNotes() {
        return withRepository(repository -> repository.search(TastingNoteEntity.class, null).size());
    }

    @Nested
    @DisplayName("checking the existence")
    class Exists {

        @Test
        @DisplayName("reports whether an entity matches the restriction")
        void reportsAMatchingRestriction() {
            boolean fromEthiopia = withRepository(repository -> repository.existsFromOrigin(ETHIOPIA));
            boolean fromYemen = withRepository(repository -> repository.existsFromOrigin("Yemen"));

            assertThat(fromEthiopia).isTrue();
            assertThat(fromYemen).isFalse();
        }

        @Test
        @DisplayName("reports whether an entity matches the criteria")
        void reportsAMatchingCriteria() {
            boolean citrus = withRepository(repository -> repository.existsTasting("citrus"));
            boolean burnt = withRepository(repository -> repository.existsTasting("burnt"));

            assertThat(citrus).isTrue();
            assertThat(burnt).isFalse();
        }

        @Test
        @DisplayName("stops at the first row and hydrates nothing")
        void hydratesNothing() {
            long loaded = inTransaction(entityManager -> {
                statistics().clear();
                new CoffeeRepositoryJpa(entityManager).existsFromOrigin(ETHIOPIA);
                return statistics().getEntityLoadCount();
            });

            assertThat(loaded).as("no entity is loaded to answer the question").isZero();
        }

    }

    @Nested
    @DisplayName("deleting in bulk")
    class BulkDelete {

        @Test
        @DisplayName("deletes every entity matching the restriction in a single statement")
        void deletesMatchingARestriction() {
            persist(coffee("Mocha", "Yemen", Roast.DARK, "30.00", 7), coffee("Haraz", "Yemen", Roast.MEDIUM, "32.00", 6));

            int deleted = withRepository(repository -> repository.deleteFromOrigin("Yemen"));

            assertThat(deleted).isEqualTo(2);
            assertThat(remainingNames()).containsExactlyElementsOf(Coffees.MENU);
        }

        @Test
        @DisplayName("deletes through a criteria expressed as a subquery, which a bulk deletion can carry")
        void deletesMatchingACriteria() {
            // A join has no place in a bulk deletion, but a subquery on the collection does
            persist(coffee("Mocha", "Yemen", Roast.DARK, "30.00", 7));

            int deleted = withRepository(repository -> repository.deleteWithoutNotes());

            assertThat(deleted).as("every coffee of the menu has at least one note").isEqualTo(1);
            assertThat(remainingNames()).containsExactlyElementsOf(Coffees.MENU);
        }

        @Test
        @DisplayName("does not cascade, the referencing rows being left behind")
        void doesNotCascade() {
            // Documented on purpose: a bulk deletion is performed by the database, which knows nothing of the
            // cascading rules of the mapping, so the tasting notes still reference the deleted coffees
            assertThatExceptionOfType(PersistenceException.class)
                    .isThrownBy(() -> withRepository(repository -> repository.deleteTasting("nutty")));

            assertThat(remainingNames()).containsExactlyElementsOf(Coffees.MENU);
        }

        @Test
        @DisplayName("deletes the entities of the given identifiers, cascading as a plain deletion does")
        void deletesByIdentifiers() {
            List<Long> ids = withRepository(repository -> repository.findAll().stream()
                    .filter(coffee -> List.of(KONA, SIDAMO).contains(coffee.getName()))
                    .map(CoffeeEntity::getId)
                    .toList());

            withRepository(repository -> {
                repository.deleteAllById(ids);
                return null;
            });

            assertThat(remainingNames()).containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU, GEISHA, HARRAR, YIRGACHEFFE);
            assertThat(remainingNotes()).as("the notes of the deleted coffees are cascaded away").isEqualTo(7);
        }

        @Test
        @DisplayName("silently skips an unknown identifier")
        void skipsAnUnknownIdentifier() {
            withRepository(repository -> {
                repository.deleteAllById(List.of(-1L));
                return null;
            });

            assertThat(remainingCount()).isEqualTo(7);
        }

    }

    @Nested
    @DisplayName("saving in batches")
    class BatchedSave {

        @Test
        @DisplayName("saves every entity, flushing and clearing along the way")
        void savesEveryEntity() {
            List<CoffeeEntity> batch = IntStream.range(0, 25)
                    .mapToObj(index -> coffee("Batch " + index))
                    .toList();

            int saved = inTransaction(entityManager -> new SmallBatchRepository(entityManager).saveAllInBatches(batch));

            assertThat(saved).isEqualTo(25);
            assertThat(remainingCount()).isEqualTo(32);
            assertThat(batch).as("the generated identifiers are populated on the given entities")
                    .allSatisfy(coffee -> assertThat(coffee.getId()).isNotNull());
        }

        @Test
        @DisplayName("flushes the last, partial batch as well")
        void flushesThePartialBatch() {
            List<CoffeeEntity> batch = IntStream.range(0, 7)
                    .mapToObj(index -> coffee("Partial " + index))
                    .toList();

            inTransaction(entityManager -> new SmallBatchRepository(entityManager).saveAllInBatches(batch));

            assertThat(remainingCount()).isEqualTo(14);
        }

    }

    /**
     * A repository flushing every ten entities, so that the batching is exercised without saving the fifty
     * entities the default batch size would need.
     */
    static class SmallBatchRepository extends CoffeeRepositoryJpa {

        SmallBatchRepository(EntityManager entityManager) {
            super(entityManager);
        }

        @Override
        protected int saveBatchSize() {
            return 10;
        }

    }

}
