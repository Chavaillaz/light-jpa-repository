package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BLUE_MOUNTAIN;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

/**
 * The query collaborators are shared per entity type, which is only correct because they hold nothing of any
 * repository: whatever differs from one to the next travels with the {@link RepositoryContext} of each call.
 */
@DisplayName("Sharing the collaborators per entity type")
class RepositoryContextTest extends HibernateTest {

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void brewTheMenu() {
        runInTransaction(Coffees::persistMenu);
    }

    @Test
    @DisplayName("hands the very same collaborators to every repository over an entity")
    void sharesTheCollaborators() {
        inTransaction(entityManager -> {
            CoffeeRepositoryJpa first = new CoffeeRepositoryJpa(entityManager);
            CoffeeRepositoryJpa second = new StrongestFirstRepository(entityManager);

            assertThat(first.queries()).isSameAs(second.queries()).isSameAs(EntityQueries.of(CoffeeEntity.class));
            assertThat(first.ordering()).isSameAs(second.ordering()).isSameAs(EntityOrdering.of(CoffeeEntity.class));
            return null;
        });
    }

    @Test
    @DisplayName("still applies the ordering of each repository, sharing the collaborators notwithstanding")
    void keepsTheRulesOfEachRepository() {
        inTransaction(entityManager -> {
            List<String> byName = namesOf(new CoffeeRepositoryJpa(entityManager).findAll());
            List<String> byStrength = namesOf(new StrongestFirstRepository(entityManager).findAll());

            assertThat(byName).startsWith(BLUE_MOUNTAIN);
            assertThat(byStrength).as("the shared collaborator asked this repository for its own ordering")
                    .startsWith(HARRAR)
                    .endsWith(GEISHA);
            return null;
        });
    }

    @Test
    @DisplayName("exposes the entity manager of the repository asking, and no other")
    void carriesTheEntityManagerOfTheCaller() {
        inTransaction(first -> inTransaction(second -> {
            assertThat(new CoffeeRepositoryJpa(first).context().entityManager()).isSameAs(first);
            assertThat(new CoffeeRepositoryJpa(second).context().entityManager()).isSameAs(second);
            return null;
        }));
    }

    /**
     * The very same entity, with another default ordering: what the shared collaborators must not mix up.
     */
    static class StrongestFirstRepository extends CoffeeRepositoryJpa {

        StrongestFirstRepository(EntityManager entityManager) {
            super(entityManager);
        }

        @Override
        protected List<Order> getDefaultOrders(CriteriaBuilder criteriaBuilder, Root<CoffeeEntity> root) {
            return List.of(criteriaBuilder.desc(root.get(CoffeeEntity_.strength)));
        }

    }

}
