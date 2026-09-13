package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.coffee;
import static jakarta.persistence.LockModeType.PESSIMISTIC_READ;
import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

@DisplayName("Reading under a lock")
class CoffeeLockTest extends HibernateTest {

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

    private Long anyIdentifier() {
        return withRepository(repository -> repository.findAll().getFirst().getId());
    }

    @Test
    @DisplayName("reads an entity holding a pessimistic write lock on its row")
    void findsByIdUnderAWriteLock() {
        Long id = anyIdentifier();

        Optional<CoffeeEntity> found = withRepository(repository -> repository.findById(id, PESSIMISTIC_WRITE));

        assertThat(found).isPresent();
    }

    @Test
    @DisplayName("reads an entity holding a pessimistic read lock on its row")
    void findsByIdUnderAReadLock() {
        Long id = anyIdentifier();

        Optional<CoffeeEntity> found = withRepository(repository -> repository.findById(id, PESSIMISTIC_READ));

        assertThat(found).isPresent();
    }

    @Test
    @DisplayName("returns an empty result for an unknown or null identifier, whatever the lock")
    void findsNothingUnderALock() {
        Optional<CoffeeEntity> unknown = withRepository(repository -> repository.findById(-1L, PESSIMISTIC_WRITE));
        Optional<CoffeeEntity> none = withRepository(repository -> repository.findById(null, PESSIMISTIC_WRITE));

        assertThat(unknown).isEmpty();
        assertThat(none).as("a null identifier must not reach the entity manager").isEmpty();
    }

    @Test
    @DisplayName("throws instead of returning an empty result when getting an unknown identifier under a lock")
    void getsByIdUnderALock() {
        Long id = anyIdentifier();

        CoffeeEntity found = withRepository(repository -> repository.getById(id, PESSIMISTIC_WRITE));

        assertThat(found).isNotNull();
        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> withRepository(repository -> repository.getById(-1L, PESSIMISTIC_WRITE)))
                .withMessageContaining("No entity found with identifier -1");
    }

    @Test
    @DisplayName("locks an entity with the requested mode, refreshing its state beforehand")
    void locksWithTheRequestedMode() {
        CoffeeEntity detached = withRepository(repository -> repository.findAll().getFirst());
        detached.setPrice(new BigDecimal("999.00"));

        String name = withRepository(repository -> {
            repository.lock(detached, PESSIMISTIC_READ);
            return detached.getName();
        });

        assertThat(name).isNotNull();
        BigDecimal stored = withRepository(repository -> repository.getById(detached.getId()).getPrice());

        assertThat(stored).as("the local change is discarded by the refresh, not persisted")
                .isNotEqualByComparingTo(new BigDecimal("999.00"));
    }

    @Test
    @DisplayName("keeps locking with a pessimistic write by default")
    void locksWithAWriteByDefault() {
        CoffeeEntity detached = withRepository(repository -> repository.findAll().getFirst());

        Optional<CoffeeEntity> locked = withRepository(repository -> {
            repository.lock(detached);
            return repository.findById(detached.getId());
        });

        assertThat(locked).isPresent();
    }

    @Test
    @DisplayName("still rejects a transient entity, whatever the lock mode")
    void rejectsATransientEntity() {
        CoffeeEntity transientCoffee = coffee("Unsaved", "Nowhere", Roast.LIGHT, "1.00", 1);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> withRepository(repository -> {
                    repository.lock(transientCoffee, PESSIMISTIC_READ);
                    return null;
                }))
                .withMessageContaining("transient");
    }

    @Test
    @DisplayName("claims the first matching entity under a lock, which is what a queue is polled with")
    void claimsTheFirstMatchingEntity() {
        Optional<CoffeeEntity> claimed = withRepository(repository -> repository.claimStrongest(6));

        assertThat(claimed).hasValueSatisfying(coffee -> assertThat(coffee.getName()).isEqualTo(HARRAR));
    }

}
