package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BLUE_MOUNTAIN;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BOURBON_POINTU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.KONA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.coffee;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.roaster;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

@DisplayName("Keysets")
class KeysetsTest extends HibernateTest {

    private EntityManager entityManager;
    private CriteriaBuilder builder;
    private CriteriaQuery<CoffeeEntity> query;
    private Root<CoffeeEntity> root;

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void createQuery() {
        entityManager = sessionFactory.createEntityManager();
        builder = entityManager.getCriteriaBuilder();
        query = builder.createQuery(CoffeeEntity.class);
        root = query.from(CoffeeEntity.class);
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    @DisplayName("builds a criterion from the static metamodel, a rename of the attribute then failing the build")
    void buildsACriterionFromTheMetamodel() {
        assertThat(SortCriterion.asc(CoffeeEntity_.name)).isEqualTo(SortCriterion.asc("name"));
        assertThat(SortCriterion.desc(CoffeeEntity_.roaster, RoasterEntity_.name))
                .isEqualTo(SortCriterion.desc("roaster.name"));
    }

    @Test
    @DisplayName("builds the ordering in the direction of each criterion")
    void buildsTheOrdering() {
        List<Order> orders = Keysets.toOrders(builder, root, Sort.parse("-price,name,id"));

        assertThat(orders).hasSize(3);
        assertThat(orders.getFirst().isAscending()).isFalse();
        assertThat(orders.get(1).isAscending()).isTrue();
    }

    @Test
    @DisplayName("reads the ordering keys of an entity, following the nested paths")
    void readsTheOrderingKeys() {
        CoffeeEntity coffee = coffee(GEISHA, "Panama", Roast.LIGHT, "80.00", 3);
        coffee.setRoaster(roaster("Moka Brothers", "France"));

        assertThat(Keysets.valuesOf(coffee, Sort.parse("name,price,strength,roast,roaster.country"), CursorKeyCodec.DEFAULT))
                .containsExactly(GEISHA, "80.00", "3", "LIGHT", "France");
    }

    @Test
    @DisplayName("reads the ordering keys selected alongside the entity, following the nested paths")
    void readsTheSelectedKeys() {
        runInTransaction(Coffees::persistMenu);
        CriteriaQuery<Tuple> tuples = builder.createTupleQuery();
        Root<CoffeeEntity> coffees = tuples.from(CoffeeEntity.class);
        Sort sort = Sort.parse("roaster.country,name");

        Keysets.selectAlongside(builder, tuples, coffees, sort);
        Tuple row = entityManager.createQuery(tuples.where(builder.equal(coffees.get(CoffeeEntity_.name), GEISHA))).getSingleResult();

        assertThat(row.get(0, CoffeeEntity.class).getName()).isEqualTo(GEISHA);
        assertThat(Keysets.selectedValuesOf(row, sort, CursorKeyCodec.DEFAULT)).containsExactly("France", GEISHA);
    }

    @Test
    @DisplayName("rejects a null ordering key")
    void rejectsANullKey() {
        CoffeeEntity coffee = coffee(GEISHA);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Keysets.valuesOf(coffee, Sort.parse("decafLabel"), CursorKeyCodec.DEFAULT))
                .withMessageContaining("Cannot build a cursor on null property decafLabel");
    }

    @Test
    @DisplayName("reads a key through a nested null owner as null, and therefore rejects it")
    void rejectsANullOwner() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Keysets.valuesOf(coffee(GEISHA), Sort.parse("roaster.name"), CursorKeyCodec.DEFAULT))
                .withMessageContaining("roaster.name");
    }

    @Test
    @DisplayName("rejects a key that cannot be read on the entity")
    void rejectsAnUnreadableKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Keysets.valuesOf(coffee(GEISHA), Sort.parse("caffeine"), CursorKeyCodec.DEFAULT))
                .withMessageContaining("Cannot read attribute caffeine");
    }

    @Test
    @DisplayName("rejects a position that does not match the ordering")
    void rejectsAMismatchingPosition() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Keysets.seek(builder, root, Sort.parse("name,id"), List.of("Kona"), CursorKeyCodec.DEFAULT))
                .withMessage("The cursor does not match the requested ordering");
    }

    @Test
    @DisplayName("rejects an empty ordering, which would silently keep no row at all")
    void rejectsAnEmptyOrdering() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Keysets.seek(builder, root, Sort.NONE, List.of(), CursorKeyCodec.DEFAULT))
                .withMessageContaining("without an ordering");
    }

    @Test
    @DisplayName("builds a lexicographic seek predicate honouring each direction")
    void buildsTheSeekPredicate() {
        Map<String, CoffeeEntity> menu = inTransaction(Coffees::persistMenu);

        Predicate seek = Keysets.seek(builder, root, Sort.parse("roast,-strength,id"),
                List.of("MEDIUM", "6", String.valueOf(menu.get(KONA).getId())), CursorKeyCodec.DEFAULT);
        query.where(seek).orderBy(Keysets.toOrders(builder, root, Sort.parse("roast,-strength,id")));

        List<CoffeeEntity> found = entityManager.createQuery(query).getResultList();

        assertThat(namesOf(found))
                .as("everything strictly after (MEDIUM, 6) in that ordering")
                .containsExactly(BLUE_MOUNTAIN);
    }

    @Test
    @DisplayName("bounds the leading key on its own, which an index scan can start from where a disjunction cannot")
    void boundsTheLeadingKey() {
        Map<String, CoffeeEntity> menu = inTransaction(Coffees::persistMenu);
        recordStatements();

        Predicate seek = Keysets.seek(builder, root, Sort.parse("-price,name,id"),
                List.of("42.00", BLUE_MOUNTAIN, String.valueOf(menu.get(BLUE_MOUNTAIN).getId())), CursorKeyCodec.DEFAULT);
        query.where(seek).orderBy(Keysets.toOrders(builder, root, Sort.parse("-price,name,id")));

        assertThat(namesOf(entityManager.createQuery(query).getResultList()))
                .as("the bound is implied by the lexicographic comparison, so it keeps the very same rows")
                .containsExactly(KONA, YIRGACHEFFE, SIDAMO, HARRAR);
        assertThat(statements())
                .singleElement(as(STRING))
                .as("the price is descending, so the rows after the boundary one cost at most its price")
                .contains("price<=?");
    }

    @Test
    @DisplayName("parses each key of the boundary row once, however many conjunctions compare it")
    void parsesEachKeyOnce() {
        List<String> parsed = new ArrayList<>();
        CursorKeyCodec counting = new CursorKeyCodec() {

            @Override
            public String format(String property, @Nullable Object value) {
                return CursorKeyCodec.DEFAULT.format(property, value);
            }

            @Override
            public <Y> Y parse(String value, Class<Y> type) {
                parsed.add(value);
                return CursorKeyCodec.DEFAULT.parse(value, type);
            }

        };

        Keysets.seek(builder, root, Sort.parse("price,strength,name,id"), List.of("42.00", "5", KONA, "1"), counting);

        assertThat(parsed)
                .as("the price is compared in all four conjunctions, and a number costs the square of its length to parse")
                .containsExactly("42.00", "5", KONA, "1");
    }

    @Test
    @DisplayName("seeks on an enum stored as a string, consistently with the ordering")
    void seeksOnAnEnum() {
        runInTransaction(Coffees::persistMenu);

        query.where(Keysets.seek(builder, root, Sort.parse("roast,name"), List.of("DARK", HARRAR), CursorKeyCodec.DEFAULT))
                .orderBy(Keysets.toOrders(builder, root, Sort.parse("roast,name")));

        assertThat(namesOf(entityManager.createQuery(query).getResultList()))
                .containsExactly(BOURBON_POINTU, GEISHA, YIRGACHEFFE, BLUE_MOUNTAIN, KONA, SIDAMO);
    }

}