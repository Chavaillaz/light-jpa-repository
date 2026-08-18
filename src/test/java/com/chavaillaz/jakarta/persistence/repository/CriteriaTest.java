package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BLUE_MOUNTAIN;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BOURBON_POINTU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.JAMAICA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.PANAMA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.query.restriction.Restriction.equal;
import static org.hibernate.query.restriction.Restriction.greaterThan;

import java.util.List;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity_;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Criteria")
class CriteriaTest extends HibernateTest {

    private EntityManager entityManager;

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void brewTheMenu() {
        runInTransaction(Coffees::persistMenu);
        entityManager = sessionFactory.createEntityManager();
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    /**
     * Executes the given criteria against the menu, ordered by name.
     *
     * @param criteria The criteria to apply, {@code null} to match everything
     * @return The names of the matching coffees
     */
    private List<String> namesMatching(Criteria<CoffeeEntity> criteria) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<CoffeeEntity> query = criteriaBuilder.createQuery(CoffeeEntity.class);
        Root<CoffeeEntity> root = query.from(CoffeeEntity.class);
        if (criteria != null) {
            query.where(criteria.toPredicate(criteriaBuilder, query, root));
        }
        query.orderBy(criteriaBuilder.asc(root.get(CoffeeEntity_.name)));
        return namesOf(entityManager.createQuery(query).getResultList());
    }

    @Test
    @DisplayName("adapts a restriction, so that both can be combined")
    void adaptsARestriction() {
        assertThat(namesMatching(Criteria.of(equal(CoffeeEntity_.origin, ETHIOPIA))))
                .containsExactly(HARRAR, SIDAMO, YIRGACHEFFE);
    }

    @Test
    @DisplayName("combines the criteria with a logical and")
    void combinesWithAnAnd() {
        Criteria<CoffeeEntity> criteria = Criteria.allOf(
                Criteria.of(equal(CoffeeEntity_.origin, ETHIOPIA)),
                Criteria.of(greaterThan(CoffeeEntity_.strength, 5)));

        assertThat(namesMatching(criteria)).containsExactly(HARRAR, SIDAMO);
    }

    @Test
    @DisplayName("combines the criteria with a logical or")
    void combinesWithAnOr() {
        Criteria<CoffeeEntity> criteria = Criteria.anyOf(
                Criteria.of(equal(CoffeeEntity_.origin, JAMAICA)),
                Criteria.of(equal(CoffeeEntity_.origin, PANAMA)));

        assertThat(namesMatching(criteria)).containsExactly(BLUE_MOUNTAIN, GEISHA);
    }

    @Test
    @DisplayName("ignores the null criteria when combining")
    void ignoresTheNullCriteria() {
        Criteria<CoffeeEntity> only = Criteria.of(equal(CoffeeEntity_.origin, PANAMA));

        assertThat(namesMatching(Criteria.allOf(null, only, null))).containsExactly(GEISHA);
        assertThat(namesMatching(Criteria.anyOf(null, only))).containsExactly(GEISHA);
    }

    @Test
    @DisplayName("yields no criteria at all when nothing is left to combine")
    void yieldsNoCriteria() {
        assertThat(Criteria.<CoffeeEntity>allOf()).isNull();
        assertThat(Criteria.<CoffeeEntity>anyOf()).isNull();
        assertThat(Criteria.allOf((Criteria<CoffeeEntity>) null, null)).isNull();
        assertThat(Criteria.anyOf((Criteria<CoffeeEntity>) null)).isNull();
    }

    @Test
    @DisplayName("nests the combinations")
    void nestsTheCombinations() {
        Criteria<CoffeeEntity> criteria = Criteria.allOf(
                Criteria.anyOf(
                        Criteria.of(equal(CoffeeEntity_.origin, ETHIOPIA)),
                        Criteria.of(equal(CoffeeEntity_.origin, JAMAICA))),
                Criteria.of(equal(CoffeeEntity_.roast, Roast.MEDIUM)));

        assertThat(namesMatching(criteria)).containsExactly(BLUE_MOUNTAIN, SIDAMO);
    }

    @Test
    @DisplayName("matches through an existing related entity, without duplicating the rows")
    void matchesThroughARelatedEntity() {
        Criteria<CoffeeEntity> criteria = Criteria.exists(TastingNoteEntity.class, TastingNoteEntity_.COFFEE,
                (criteriaBuilder, note) -> criteriaBuilder.equal(note.get(TastingNoteEntity_.flavour), "Citrus"));

        assertThat(namesMatching(criteria))
                .as("Yirgacheffe has two notes and must appear once")
                .containsExactly(GEISHA, SIDAMO, YIRGACHEFFE);
    }

    @Test
    @DisplayName("matches nothing when no related entity satisfies the predicate")
    void matchesNothing() {
        Criteria<CoffeeEntity> criteria = Criteria.exists(TastingNoteEntity.class, TastingNoteEntity_.COFFEE,
                (criteriaBuilder, note) -> criteriaBuilder.equal(note.get(TastingNoteEntity_.flavour), "Burnt"));

        assertThat(namesMatching(criteria)).isEmpty();
    }

    @Test
    @DisplayName("combines an exists criteria with a restriction")
    void combinesAnExistsWithARestriction() {
        Criteria<CoffeeEntity> criteria = Criteria.allOf(
                Criteria.of(equal(CoffeeEntity_.origin, ETHIOPIA)),
                Criteria.exists(TastingNoteEntity.class, TastingNoteEntity_.COFFEE,
                        (criteriaBuilder, note) -> criteriaBuilder.equal(note.get(TastingNoteEntity_.flavour), "Citrus")));

        assertThat(namesMatching(criteria)).containsExactly(SIDAMO, YIRGACHEFFE);
    }

    @Test
    @DisplayName("is a functional interface, usable as a lambda on the raw criteria API")
    void isAFunctionalInterface() {
        Criteria<CoffeeEntity> criteria = (criteriaBuilder, query, root) -> {
            Predicate expensive = criteriaBuilder.greaterThan(root.get(CoffeeEntity_.price), new java.math.BigDecimal("40"));
            return criteriaBuilder.and(expensive, criteriaBuilder.isNotNull(root.get(CoffeeEntity_.roaster)));
        };

        assertThat(namesMatching(criteria)).containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU, GEISHA);
    }

}