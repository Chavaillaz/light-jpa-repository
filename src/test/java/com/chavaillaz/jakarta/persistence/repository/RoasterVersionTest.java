package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.roaster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import jakarta.persistence.OptimisticLockException;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

@DisplayName("Deleting a detached, versioned entity")
class RoasterVersionTest extends HibernateTest {

    private Long identifier;

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(RoasterEntity.class, CoffeeEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void openTheRoastery() {
        identifier = inTransaction(entityManager -> {
            RoasterEntity roaster = roaster("Moka Brothers", "France");
            entityManager.persist(roaster);
            return roaster.getId();
        });
    }

    private RoasterEntity detached() {
        return inTransaction(entityManager -> new RoasterRepositoryJpa(entityManager).getById(identifier));
    }

    private void withRepository(Consumer<RoasterRepositoryJpa> action) {
        runInTransaction(entityManager -> action.accept(new RoasterRepositoryJpa(entityManager)));
    }

    private boolean stillExists() {
        return inTransaction(entityManager -> new RoasterRepositoryJpa(entityManager).existsById(identifier));
    }

    @Test
    @DisplayName("refuses a copy read before another transaction changed the row, rather than deleting that change")
    void refusesAStaleCopy() {
        RoasterEntity stale = detached();
        withRepository(repository -> repository.getById(identifier).setCountry("Italy"));

        assertThatExceptionOfType(OptimisticLockException.class)
                .isThrownBy(() -> withRepository(repository -> repository.delete(stale)))
                .withMessageContaining("modified since its detached copy was read");
        assertThat(stillExists()).as("the change the other transaction committed is not deleted with the row").isTrue();
    }

    @Test
    @DisplayName("deletes a copy still carrying the version of the row")
    void deletesACurrentCopy() {
        RoasterEntity current = detached();

        withRepository(repository -> repository.delete(current));

        assertThat(stillExists()).isFalse();
    }

    @Test
    @DisplayName("deletes a reference that was never initialised, which carries no version to compare")
    void deletesAnUninitialisedReference() {
        RoasterEntity reference = inTransaction(entityManager -> new RoasterRepositoryJpa(entityManager).getReference(identifier));

        withRepository(repository -> repository.delete(reference));

        assertThat(stillExists()).isFalse();
    }

}
