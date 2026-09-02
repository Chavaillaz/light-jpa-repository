package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.coffee;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.roaster;
import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Root;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

@DisplayName("AttributePaths")
class AttributePathsTest extends HibernateTest {

    private EntityManager entityManager;
    private Root<CoffeeEntity> root;

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void createQuery() {
        entityManager = sessionFactory.createEntityManager();
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        root = builder.createQuery(CoffeeEntity.class).from(CoffeeEntity.class);
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    @DisplayName("splits a nested path literally, the dot being a regex metacharacter")
    void splitsANestedPath() {
        assertThat(AttributePaths.split("roaster.name")).containsExactly("roaster", "name");
        assertThat(AttributePaths.split("name")).containsExactly("name");
    }

    @Test
    @DisplayName("resolves a simple and a nested path")
    void resolvesAPath() {
        assertThat(EntityOrdering.nameOf(AttributePaths.path(root, "name"))).isEqualTo("name");
        assertThat(EntityOrdering.nameOf(AttributePaths.path(root, "roaster.country"))).isEqualTo("roaster.country");
    }

    @Test
    @DisplayName("navigates a nested association with a left join, keeping the entities not having one")
    void navigatesANestedAssociationWithALeftJoin() {
        AttributePaths.path(root, "roaster.name");

        assertThat(root.getJoins()).singleElement().satisfies(join -> {
            assertThat(join.getAttribute().getName()).isEqualTo(CoffeeEntity_.ROASTER);
            assertThat(join.getJoinType()).isEqualTo(LEFT);
        });
    }

    @Test
    @DisplayName("reuses the join of an association navigated by several keys, rather than joining it twice")
    void reusesTheJoinOfANestedAssociation() {
        AttributePaths.path(root, "roaster.name");
        AttributePaths.path(root, "roaster.country");

        assertThat(root.getJoins()).as("a single join is created and reused").hasSize(1);
    }

    @Test
    @DisplayName("reuses the join a restriction already made on the association, whatever its type")
    void reusesTheJoinOfTheQueryItself() {
        root.join(CoffeeEntity_.ROASTER);

        assertThat(EntityOrdering.nameOf(AttributePaths.path(root, "roaster.name"))).isEqualTo("roaster.name");
        assertThat(root.getJoins())
                .as("a left join next to the inner one cannot bring back the rows it dropped, it only joins twice")
                .singleElement()
                .satisfies(join -> assertThat(join.getJoinType()).isEqualTo(INNER));
    }

    @Test
    @DisplayName("reads a value through the getter, following the nested paths")
    void readsAValue() {
        CoffeeEntity coffee = coffee(GEISHA);
        coffee.setRoaster(roaster("Moka Brothers", "France"));

        assertThat(AttributePaths.read(coffee, "name")).isEqualTo(GEISHA);
        assertThat(AttributePaths.read(coffee, "roaster.country")).isEqualTo("France");
    }

    @Test
    @DisplayName("reads a value through a null owner as null, rather than failing on the way")
    void readsThroughANullOwner() {
        assertThat(AttributePaths.read(coffee(GEISHA), "roaster.country")).isNull();
    }

    @Test
    @DisplayName("rejects an attribute the entity has no accessor for")
    void rejectsAnUnreadableAttribute() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AttributePaths.read(coffee(GEISHA), "caffeine"))
                .withMessageContaining("Cannot read the attribute caffeine");
    }

}
