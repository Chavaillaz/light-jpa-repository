package com.chavaillaz.jakarta.persistence.repository;

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
import static org.assertj.core.api.Assertions.assertThatNoException;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepository;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

@DisplayName("AbstractRepository, on the coffee repository")
class CoffeeRepositoryTest extends HibernateTest {

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @Nested
    @DisplayName("managing the life cycle of an entity")
    class LifeCycle {

        /**
         * The identifier of the coffee each test starts from, alone in its table with a single note.
         */
        private Long identifier;

        @BeforeEach
        void brewACoffee() {
            identifier = inTransaction(entityManager -> {
                CoffeeEntity coffee = coffee(YIRGACHEFFE, ETHIOPIA, Roast.LIGHT, "25.00", 4).addNote("Citrus");
                entityManager.persist(coffee);
                return coffee.getId();
            });
        }

        @Test
        @DisplayName("persists an entity having no identifier yet, along with its notes")
        void persistsANewEntity() {
            Long id = inTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
                CoffeeEntity coffee = coffee(KONA, "Hawaii", Roast.MEDIUM, "35.00", 6).addNote("Nutty");

                CoffeeEntity saved = repository.save(coffee);

                assertThat(saved).as("an entity with no identifier is persisted, not merged").isSameAs(coffee);
                assertThat(saved.getId()).isNotNull();
                return saved.getId();
            });

            runInTransaction(entityManager -> assertThat(new CoffeeRepositoryJpa(entityManager).findById(id))
                    .hasValueSatisfying(coffee -> assertThat(coffee.getNotes()).extracting(TastingNoteEntity::getFlavour).containsExactly("Nutty")));
        }

        @Test
        @DisplayName("merges an entity carrying an identifier")
        void mergesAnEntityCarryingAnIdentifier() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
                CoffeeEntity detached = new CoffeeEntity();
                detached.setId(identifier);
                detached.setName(YIRGACHEFFE);
                detached.setOrigin(ETHIOPIA);
                detached.setRoast(Roast.MEDIUM);
                detached.setPrice(new BigDecimal("27.00"));
                detached.setStrength(5);
                // The notes must be carried over: an empty collection would orphan-remove the existing ones on merge
                detached.addNote("Citrus");

                CoffeeEntity merged = repository.save(detached);

                assertThat(merged).as("an entity carrying an identifier is merged, not persisted").isNotSameAs(detached);
                assertThat(entityManager.contains(merged)).isTrue();
            });

            runInTransaction(entityManager -> assertThat(new CoffeeRepositoryJpa(entityManager).findById(identifier))
                    .hasValueSatisfying(coffee -> {
                        assertThat(coffee.getRoast()).isEqualTo(Roast.MEDIUM);
                        assertThat(coffee.getPrice()).isEqualByComparingTo("27.00");
                    }));
        }

        @Test
        @DisplayName("finds an entity by its identifier")
        void findsById() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

                Optional<CoffeeEntity> found = repository.findById(identifier);

                assertThat(found).hasValueSatisfying(coffee -> {
                    assertThat(coffee.getName()).isEqualTo(YIRGACHEFFE);
                    assertThat(coffee.getNotes()).extracting(TastingNoteEntity::getFlavour).containsExactly("Citrus");
                });
                assertThat(repository.getById(identifier).getName()).isEqualTo(YIRGACHEFFE);
            });
        }

        @Test
        @DisplayName("finds nothing for an unknown identifier, and throws when getting it")
        void findsNothingForAnUnknownIdentifier() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

                assertThat(repository.findById(-1L)).isEmpty();
                assertThatExceptionOfType(NoSuchElementException.class)
                        .isThrownBy(() -> repository.getById(-1L))
                        .withMessageContaining("No entity found with identifier -1")
                        .withMessageContaining(CoffeeRepositoryJpa.class.getSimpleName());
            });
        }

        @Test
        @DisplayName("finds nothing for a null identifier")
        void findsNothingForANullIdentifier() {
            runInTransaction(entityManager -> assertThat(new CoffeeRepositoryJpa(entityManager).findById(null))
                    .as("a null identifier must not reach the entity manager")
                    .isEmpty());
        }

        @Test
        @DisplayName("checks whether an entity exists for an identifier")
        void checksTheExistenceById() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

                assertThat(repository.existsById(identifier)).isTrue();
                assertThat(repository.existsById(-1L)).isFalse();
                assertThat(repository.existsById(null)).as("a null identifier must not reach the entity manager").isFalse();
            });
        }

        @Test
        @DisplayName("finds the entities of several identifiers, skipping the unknown and the duplicated ones")
        void findsAllById() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

                assertThat(namesOf(repository.findAllById(List.of(identifier, -1L, identifier))))
                        .as("an unknown or duplicated identifier is silently skipped")
                        .containsExactly(YIRGACHEFFE);
                assertThat(repository.findAllById(List.of())).isEmpty();
            });
        }

        @Test
        @DisplayName("gets a reference to an entity, and none for a null identifier")
        void getsAReference() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

                CoffeeEntity reference = repository.getReference(identifier);
                assertThat(reference).isNotNull();
                assertThat(reference.getId()).isEqualTo(identifier);

                assertThat(repository.getReference(null)).as("a null identifier yields no reference").isNull();
                assertThat(repository.getReference(RoasterEntity.class, null)).isNull();
            });
        }

        @Test
        @DisplayName("refreshes an entity, discarding its local changes")
        void refreshesAnEntity() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
                CoffeeEntity coffee = repository.findById(identifier).orElseThrow();
                coffee.setName("Tampered");

                repository.refresh(coffee);

                assertThat(coffee.getName()).as("the in memory change is discarded").isEqualTo(YIRGACHEFFE);
            });
        }

        @Test
        @DisplayName("locks a managed entity, refreshing its state first")
        void locksAManagedEntity() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
                CoffeeEntity coffee = repository.findById(identifier).orElseThrow();
                coffee.setName("Tampered");

                repository.lock(coffee);

                assertThat(entityManager.getLockMode(coffee)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
                assertThat(coffee.getName()).as("locking refreshes the state first").isEqualTo(YIRGACHEFFE);
            });
        }

        @Test
        @DisplayName("locks the managed copy of a detached entity, without writing the changes the detached one carries")
        void locksADetachedEntity() {
            CoffeeEntity detached = inTransaction(entityManager ->
                    new CoffeeRepositoryJpa(entityManager).findById(identifier).orElseThrow());
            detached.setName("Tampered");

            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

                repository.lock(detached);

                assertThat(entityManager.contains(detached)).as("the detached entity is not attached itself").isFalse();
                assertThat(entityManager.getLockMode(entityManager.find(CoffeeEntity.class, identifier)))
                        .as("the managed copy is the one being locked")
                        .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
            });

            runInTransaction(entityManager -> assertThat(new CoffeeRepositoryJpa(entityManager).findById(identifier))
                    .as("locking must not persist the local changes carried by the stale detached copy")
                    .hasValueSatisfying(coffee -> assertThat(coffee.getName()).isEqualTo(YIRGACHEFFE)));
        }

        @Test
        @DisplayName("rejects locking an entity whose row does not exist")
        void rejectsLockingAnUnknownEntity() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
                CoffeeEntity ghost = coffee("Ghost", "Nowhere", Roast.DARK, "10.00", 5);
                ghost.setId(-1L);

                assertThatExceptionOfType(NoSuchElementException.class)
                        .isThrownBy(() -> repository.lock(ghost))
                        .withMessageContaining("No entity found with identifier -1")
                        .withMessageContaining(CoffeeRepositoryJpa.class.getSimpleName());
            });
        }

        @Test
        @DisplayName("rejects locking a transient entity")
        void rejectsLockingATransientEntity() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
                CoffeeEntity transientCoffee = coffee("Unsaved", "Nowhere", Roast.LIGHT, "1.00", 1);

                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("a transient entity has no identifier to look it up with")
                        .isThrownBy(() -> repository.lock(transientCoffee))
                        .withMessageContaining("transient")
                        .withMessageContaining(CoffeeRepositoryJpa.class.getSimpleName());
            });
        }

        @Test
        @DisplayName("deletes an entity by its identifier, doing nothing for an unknown one")
        void deletesById() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

                repository.deleteById(identifier);
                assertThatNoException()
                        .as("deleting an unknown identifier is a no operation")
                        .isThrownBy(() -> repository.deleteById(-1L));
            });

            runInTransaction(entityManager -> assertThat(new CoffeeRepositoryJpa(entityManager).count()).isZero());
        }

        @Test
        @DisplayName("deletes a detached entity, re-attaching it first")
        void deletesADetachedEntity() {
            CoffeeEntity detached = inTransaction(entityManager -> new CoffeeRepositoryJpa(entityManager).getById(identifier));

            runInTransaction(entityManager -> new CoffeeRepositoryJpa(entityManager).delete(detached));

            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
                assertThat(repository.findById(identifier))
                        .as("a detached entity is re-attached before being removed")
                        .isEmpty();
                assertThat(repository.count()).isZero();
            });
        }

        @Test
        @DisplayName("rejects deleting a transient entity")
        void rejectsDeletingATransientEntity() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
                CoffeeEntity transientCoffee = coffee("Unsaved", "Nowhere", Roast.LIGHT, "1.00", 1);

                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("a transient entity has nothing to delete, and must not be silently inserted then removed")
                        .isThrownBy(() -> repository.delete(transientCoffee))
                        .withMessageContaining("transient");
            });
        }

        @Test
        @DisplayName("rejects deleting an entity whose row does not exist")
        void rejectsDeletingAnUnknownEntity() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
                CoffeeEntity ghost = coffee("Ghost", "Nowhere", Roast.DARK, "10.00", 5);
                ghost.setId(-1L);

                assertThatExceptionOfType(NoSuchElementException.class)
                        .as("a detached entity whose row no longer exists must not be silently re-inserted then removed")
                        .isThrownBy(() -> repository.delete(ghost))
                        .withMessageContaining("No entity found with identifier -1");
            });
        }

        @Test
        @DisplayName("saves then deletes several entities at once")
        void savesAndDeletesSeveral() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

                List<CoffeeEntity> saved = repository.saveAll(List.of(
                        coffee("Batch One", "Peru", Roast.LIGHT, "12.00", 3),
                        coffee("Batch Two", "Peru", Roast.LIGHT, "13.00", 3)));
                List<Long> savedIds = saved.stream().map(CoffeeEntity::getId).toList();

                assertThat(savedIds).as("every saved entity is assigned an identifier").doesNotContainNull();
                assertThat(repository.findAllById(savedIds)).hasSize(2);

                repository.deleteAll(saved);

                assertThat(repository.findAllById(savedIds)).isEmpty();
            });
        }

    }

    @Nested
    @DisplayName("running the custom methods written with its helpers")
    class CustomMethods {

        private Map<String, CoffeeEntity> menu;

        @BeforeEach
        void brewTheMenu() {
            menu = inTransaction(Coffees::persistMenu);
        }

        @Test
        @DisplayName("gets the first entity of an ordering")
        void getsTheFirstOfAnOrdering() {
            runInTransaction(entityManager -> assertThat(new CoffeeRepositoryJpa(entityManager).findStrongest())
                    .hasValueSatisfying(coffee -> assertThat(coffee.getName()).isEqualTo(HARRAR)));
        }

        @Test
        @DisplayName("searches the entities matching a restriction")
        void searchesWithARestriction() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

                assertThat(namesOf(repository.findByOrigin(ETHIOPIA))).containsExactly(HARRAR, SIDAMO, YIRGACHEFFE);
                assertThat(repository.findByOrigin("Brazil")).isEmpty();
            });
        }

        @Test
        @DisplayName("searches and counts the entities matching a criteria")
        void searchesWithACriteria() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

                assertThat(namesOf(repository.findTasting("citrus")))
                        .as("the criteria is a correlated subquery, case insensitive")
                        .containsExactly(GEISHA, SIDAMO, YIRGACHEFFE);
                assertThat(repository.countTasting("Citrus")).isEqualTo(3);
                assertThat(repository.findTasting("Burnt")).isEmpty();
            });
        }

        @Test
        @DisplayName("searches the entities matching either of two criteria")
        void searchesWithCombinedCriteria() {
            runInTransaction(entityManager -> assertThat(namesOf(new CoffeeRepositoryJpa(entityManager).findRoastedOrStrong(Roast.LIGHT, 6)))
                    .as("the light roasts and the coffees stronger than six, neither criteria matching what the other does")
                    .containsExactly(BOURBON_POINTU, GEISHA, HARRAR, SIDAMO, YIRGACHEFFE));
        }

        @Test
        @DisplayName("searches the entities of a related type matching a restriction")
        void searchesARelatedType() {
            runInTransaction(entityManager -> {
                CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
                CoffeeEntity geisha = repository.getById(menu.get(GEISHA).getId());

                assertThat(repository.findNotesOf(geisha))
                        .as("the notes of this coffee alone, ordered by their identifier")
                        .extracting(TastingNoteEntity::getFlavour)
                        .containsExactly("Jasmine", "Citrus");
            });
        }

    }

}