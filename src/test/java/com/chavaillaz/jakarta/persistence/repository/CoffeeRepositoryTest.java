package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.KONA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.coffee;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepository;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;
import jakarta.persistence.LockModeType;
import org.apache.commons.lang3.mutable.MutableLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AbstractRepository, on the coffee repository")
class CoffeeRepositoryTest extends HibernateTest {

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @Test
    @DisplayName("supports the whole life cycle of an entity")
    void testRepository() {
        MutableLong identifier = new MutableLong();
        testCreate(identifier);
        testFindAll();
        testGetById(identifier);
        testGetByUnknownId();
        testGetByNullId();
        testUpdate(identifier);
        testGetReference(identifier);
        testCount();
        testFirst();
        testRefresh(identifier);
        testLockAttached(identifier);
        testLockDetached(identifier);
        testLockUnknownId();
        testCustomFind();
        testCustomFindWithCriteria();
        testCombinedCriteria();
        testRelatedSearch(identifier);
        testDeleteById(identifier);
        testDelete();
    }

    private void testCreate(MutableLong identifier) {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
            CoffeeEntity coffee = coffee(YIRGACHEFFE, ETHIOPIA, Roast.LIGHT, "25.00", 4);
            coffee.addNote("Citrus");

            CoffeeEntity saved = repository.save(coffee);

            assertThat(saved).as("an entity with no identifier is persisted, not merged").isSameAs(coffee);
            assertThat(saved.getId()).isNotNull();
            identifier.setValue(saved.getId());
        });
    }

    private void testFindAll() {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

            assertThat(namesOf(repository.findAll())).containsExactly(YIRGACHEFFE);
            assertThat(namesOf(repository.findAll(Sort.parse("-name")))).containsExactly(YIRGACHEFFE);

            PaginationResult<CoffeeEntity> result = repository.findAll(Pageable.UNPAGED);
            assertThat(namesOf(result)).containsExactly(YIRGACHEFFE);
            assertThat(result.totalItems()).isEqualTo(1);
            assertThat(result.currentPage()).isZero();
            assertThat(result.hasNext()).isFalse();
            assertThat(result.hasPrevious()).isFalse();
        });
    }

    private void testGetById(MutableLong identifier) {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

            Optional<CoffeeEntity> found = repository.findById(identifier.longValue());

            assertThat(found).hasValueSatisfying(coffee -> {
                assertThat(coffee.getName()).isEqualTo(YIRGACHEFFE);
                assertThat(coffee.getNotes()).extracting(TastingNoteEntity::getFlavour).containsExactly("Citrus");
            });
            assertThat(repository.getById(identifier.longValue()).getName()).isEqualTo(YIRGACHEFFE);
        });
    }

    private void testGetByUnknownId() {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

            assertThat(repository.findById(-1L)).isEmpty();
            assertThatExceptionOfType(NoSuchElementException.class)
                    .isThrownBy(() -> repository.getById(-1L))
                    .withMessageContaining("No entity found with the identifier -1")
                    .withMessageContaining(CoffeeRepositoryJpa.class.getSimpleName());
        });
    }

    private void testGetByNullId() {
        runInTransaction(entityManager -> assertThat(new CoffeeRepositoryJpa(entityManager).findById(null))
                .as("a null identifier must not reach the entity manager")
                .isEmpty());
    }

    private void testUpdate(MutableLong identifier) {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
            CoffeeEntity detached = new CoffeeEntity();
            detached.setId(identifier.longValue());
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

        runInTransaction(entityManager -> assertThat(new CoffeeRepositoryJpa(entityManager)
                .findById(identifier.longValue()))
                .hasValueSatisfying(coffee -> {
                    assertThat(coffee.getRoast()).isEqualTo(Roast.MEDIUM);
                    assertThat(coffee.getPrice()).isEqualByComparingTo("27.00");
                }));
    }

    private void testGetReference(MutableLong identifier) {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

            CoffeeEntity reference = repository.getReference(identifier.longValue());
            assertThat(reference).isNotNull();
            assertThat(reference.getId()).isEqualTo(identifier.longValue());

            assertThat(repository.getReference(null)).as("a null identifier yields no reference").isNull();
            assertThat(repository.getReference(RoasterEntity.class, null)).isNull();
        });
    }

    private void testCount() {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.count(null)).as("a null query counts everything").isEqualTo(1);
            assertThat(repository.count("")).isEqualTo(1);
            assertThat(repository.count("   ")).isEqualTo(1);
            assertThat(repository.count("origin==" + ETHIOPIA)).isEqualTo(1);
            assertThat(repository.count("origin==Brazil")).isZero();
        });
    }

    private void testFirst() {
        runInTransaction(entityManager -> assertThat(new CoffeeRepositoryJpa(entityManager).findStrongest())
                .hasValueSatisfying(coffee -> assertThat(coffee.getName()).isEqualTo(YIRGACHEFFE)));
    }

    private void testRefresh(MutableLong identifier) {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
            CoffeeEntity coffee = repository.findById(identifier.longValue()).orElseThrow();
            coffee.setName("Tampered");

            repository.refresh(coffee);

            assertThat(coffee.getName()).as("the in memory change is discarded").isEqualTo(YIRGACHEFFE);
        });
    }

    private void testLockAttached(MutableLong identifier) {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
            CoffeeEntity coffee = repository.findById(identifier.longValue()).orElseThrow();
            coffee.setName("Tampered");

            repository.lock(coffee);

            assertThat(entityManager.getLockMode(coffee)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
            assertThat(coffee.getName()).as("locking refreshes the state first").isEqualTo(YIRGACHEFFE);
        });
    }

    private void testLockDetached(MutableLong identifier) {
        CoffeeEntity detached = inTransaction(entityManager ->
                new CoffeeRepositoryJpa(entityManager).findById(identifier.longValue()).orElseThrow());
        detached.setName("Tampered");

        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

            assertThatNoException()
                    .as("a detached entity is re-attached before being locked")
                    .isThrownBy(() -> repository.lock(detached));
            assertThat(entityManager.contains(detached))
                    .as("the managed copy is the one being locked")
                    .isFalse();
        });

        runInTransaction(entityManager -> assertThat(new CoffeeRepositoryJpa(entityManager)
                .findById(identifier.longValue()))
                .as("locking must not persist the local changes carried by the stale detached copy")
                .hasValueSatisfying(coffee -> assertThat(coffee.getName()).isEqualTo(YIRGACHEFFE)));
    }

    private void testLockUnknownId() {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
            CoffeeEntity ghost = coffee("Ghost", "Nowhere", Roast.DARK, "10.00", 5);
            ghost.setId(-1L);

            assertThatExceptionOfType(NoSuchElementException.class)
                    .isThrownBy(() -> repository.lock(ghost))
                    .withMessageContaining("No entity found with the identifier -1")
                    .withMessageContaining(CoffeeRepositoryJpa.class.getSimpleName());
        });
    }

    private void testCustomFind() {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
            assertThat(namesOf(repository.findByOrigin(ETHIOPIA))).containsExactly(YIRGACHEFFE);
            assertThat(repository.findByOrigin("Brazil")).isEmpty();
        });
    }

    private void testCustomFindWithCriteria() {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
            assertThat(namesOf(repository.findTasting("citrus")))
                    .as("the criteria is a correlated subquery, case insensitive")
                    .containsExactly(YIRGACHEFFE);
            assertThat(repository.countTasting("Citrus")).isEqualTo(1);
            assertThat(repository.findTasting("Blueberry")).isEmpty();
        });
    }

    private void testCombinedCriteria() {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
            assertThat(namesOf(repository.findRoastedOrStrong(Roast.MEDIUM, 9))).containsExactly(YIRGACHEFFE);
            assertThat(repository.findRoastedOrStrong(Roast.DARK, 9)).isEmpty();
        });
    }

    private void testRelatedSearch(MutableLong identifier) {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
            CoffeeEntity coffee = repository.findById(identifier.longValue()).orElseThrow();

            List<TastingNoteEntity> notes = repository.findNotesOf(coffee);

            assertThat(notes).extracting(TastingNoteEntity::getFlavour).containsExactly("Citrus");
        });
    }

    private void testDeleteById(MutableLong identifier) {
        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);

            repository.deleteById(identifier.longValue());
            assertThatNoException()
                    .as("deleting an unknown identifier is a no operation")
                    .isThrownBy(() -> repository.deleteById(-1L));
        });

        runInTransaction(entityManager -> assertThat(new CoffeeRepositoryJpa(entityManager).count()).isZero());
    }

    private void testDelete() {
        Long identifier = inTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
            return repository.save(coffee(KONA, "Hawaii", Roast.MEDIUM, "35.00", 6)).getId();
        });
        CoffeeEntity detached = inTransaction(entityManager ->
                new CoffeeRepositoryJpa(entityManager).getById(identifier));

        runInTransaction(entityManager -> new CoffeeRepositoryJpa(entityManager).delete(detached));

        runInTransaction(entityManager -> {
            CoffeeRepository repository = new CoffeeRepositoryJpa(entityManager);
            assertThat(repository.findById(identifier))
                    .as("a detached entity is re-attached before being removed")
                    .isEmpty();
            assertThat(repository.count()).isZero();
        });
    }

}