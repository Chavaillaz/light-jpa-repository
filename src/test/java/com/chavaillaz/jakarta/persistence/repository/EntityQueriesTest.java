package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa.tasting;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BLUE_MOUNTAIN;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.PANAMA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.hibernate.query.restriction.Restriction.equal;
import static org.hibernate.query.restriction.Restriction.unrestricted;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import org.hibernate.query.restriction.Restriction;
import org.jspecify.annotations.Nullable;
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

@DisplayName("EntityQueries")
class EntityQueriesTest extends HibernateTest {

    private static final BiFunction<CriteriaBuilder, Root<CoffeeEntity>, List<Order>> BY_NAME =
            (builder, root) -> List.of(builder.asc(root.get("name")));

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void brewTheMenu() {
        runInTransaction(Coffees::persistMenu);
    }

    private <T> T withQueries(BiFunction<EntityQueries<CoffeeEntity>, RepositoryContext<CoffeeEntity>, T> action) {
        return inTransaction(entityManager ->
                action.apply(EntityQueries.of(CoffeeEntity.class), new TestContext<>(entityManager, BY_NAME, Map.of())));
    }

    /**
     * Runs the queries on the entity manager a container injects, such as the transaction scoped one of WildFly or
     * the client proxy of a CDI producer: a proxy implementing nothing but the JPA interface, delegating every call,
     * {@code unwrap} included, to the entity manager of the transaction.
     */
    private <T> T withContainerProxy(BiFunction<EntityQueries<CoffeeEntity>, RepositoryContext<CoffeeEntity>, T> action) {
        return inTransaction(entityManager -> {
            EntityManager proxy = (EntityManager) Proxy.newProxyInstance(
                    EntityManager.class.getClassLoader(),
                    new Class<?>[]{EntityManager.class},
                    (instance, method, arguments) -> {
                        try {
                            return method.invoke(entityManager, arguments);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            return action.apply(EntityQueries.of(CoffeeEntity.class), new TestContext<>(proxy, BY_NAME, Map.of()));
        });
    }

    @Test
    @DisplayName("runs on the entity manager proxy a container injects, which is no Hibernate session")
    void runsOnAContainerProxy() {
        PaginationResult<CoffeeEntity> page = withContainerProxy((queries, context) -> queries.search(context, equal(CoffeeEntity_.origin, ETHIOPIA), null, Pageable.of(0, 2)));
        assertThat(namesOf(page)).containsExactly(HARRAR, SIDAMO);
        assertThat(page.totalItems()).isEqualTo(3);

        assertThat((long) withContainerProxy((queries, context) -> queries.count(context, null, tasting("Citrus")))).isEqualTo(3);
        assertThat((Optional<CoffeeEntity>) withContainerProxy((queries, context) -> queries.first(context, null, null, Sort.parse("-price"))))
                .hasValueSatisfying(coffee -> assertThat(coffee.getName()).isEqualTo(GEISHA));

        List<RoasterEntity> roasters = withContainerProxy((queries, context) -> queries.search(context, RoasterEntity.class, equal(RoasterEntity_.country, ETHIOPIA)));
        assertThat(roasters).extracting(RoasterEntity::getName).containsExactly("Kaldi Roasting");

        CursorResult<CoffeeEntity> scrolled = withContainerProxy((queries, context) -> queries.scroll(context, null, tasting("Citrus"), Cursor.first(2, Sort.NONE)));
        assertThat(namesOf(scrolled)).containsExactly(GEISHA, SIDAMO);
        assertThat((boolean) withContainerProxy((queries, context) -> queries.exists(context, equal(CoffeeEntity_.origin, PANAMA), null))).isTrue();
    }

    @Test
    @DisplayName("treats a null restriction as unrestricted")
    void treatsNullAsUnrestricted() {
        assertThat((long) withQueries((queries, context) -> queries.count(context, null, null))).isEqualTo(7);
        assertThat((long) withQueries((queries, context) -> queries.count(context, unrestricted(), null))).isEqualTo(7);
    }

    @Test
    @DisplayName("combines the restriction and the criteria")
    void combinesTheRestrictionAndTheCriteria() {
        List<CoffeeEntity> coffees = withQueries((queries, context) -> queries
                .search(context, equal(CoffeeEntity_.origin, ETHIOPIA), tasting("Citrus"), Pageable.UNPAGED)
                .items());

        assertThat(namesOf(coffees)).containsExactly(SIDAMO, YIRGACHEFFE);
        assertThat((long) withQueries((queries, context) -> queries.count(context, equal(CoffeeEntity_.origin, ETHIOPIA), tasting("Citrus")))).isEqualTo(2);
    }

    @Test
    @DisplayName("counts from the very same query as the results")
    void countsFromTheSameQuery() {
        PaginationResult<CoffeeEntity> result = withQueries((queries, context) -> queries.search(context, equal(CoffeeEntity_.roast, Roast.LIGHT), null, Pageable.of(0, 1)));

        assertThat(result.items()).hasSize(1);
        assertThat(result.totalItems()).isEqualTo(3);
    }

    @Test
    @DisplayName("returns everything as a single page when unpaged")
    void returnsASinglePage() {
        PaginationResult<CoffeeEntity> result = withQueries((queries, context) -> queries.search(context, null, null, Pageable.UNPAGED));

        assertThat(result.items()).hasSize(7);
        assertThat(result.totalItems()).isEqualTo(7);
    }

    @Test
    @DisplayName("fetches only the first row of the first method")
    void fetchesOnlyTheFirstRow() {
        assertThat((Optional<CoffeeEntity>) withQueries((queries, context) -> queries.first(context, null, null, Sort.parse("-price"))))
                .hasValueSatisfying(coffee -> assertThat(coffee.getName()).isEqualTo(GEISHA));
        assertThat((Optional<CoffeeEntity>) withQueries((queries, context) -> queries.first(context, null, null, Sort.NONE)))
                .as("the default ordering applies when none is requested")
                .hasValueSatisfying(coffee -> assertThat(coffee.getName()).isEqualTo(BLUE_MOUNTAIN));
        assertThat((Optional<CoffeeEntity>) withQueries((queries, context) -> queries.first(context, equal(CoffeeEntity_.origin, "Mars"), null, Sort.NONE)))
                .isEmpty();
    }

    @Test
    @DisplayName("searches a related type")
    void searchesARelatedType() {
        List<TastingNoteEntity> notes = withQueries((queries, context) -> queries.search(context, TastingNoteEntity.class, null));
        assertThat(notes).hasSize(9);

        List<RoasterEntity> roasters = withQueries((queries, context) -> queries.search(context, RoasterEntity.class, equal(RoasterEntity_.country, ETHIOPIA)));
        assertThat(roasters).extracting(RoasterEntity::getName).containsExactly("Kaldi Roasting");
    }

    @Test
    @DisplayName("orders a related search on the identifier of the related type, and not on the ordering of this one")
    void ordersARelatedSearchOnItsOwnIdentifier() {
        recordStatements();

        List<TastingNoteEntity> notes = withQueries((queries, context) -> queries.search(context, TastingNoteEntity.class, null));

        assertThat(notes)
                .as("a related search must not hand back the rows in whatever order the database produced")
                .extracting(TastingNoteEntity::getId)
                .isSorted();
        assertThat(statements())
                .filteredOn(statement -> statement.contains("from tasting_note"))
                .singleElement(as(STRING))
                .as("the ordering of the repository is that of the coffees, which says nothing about a note")
                .containsIgnoringCase("order by")
                .doesNotContainIgnoringCase("name");
    }

    @Test
    @DisplayName("scrolls with the restriction, the criteria and the seek predicate combined")
    void scrollsWithEverythingCombined() {
        Restriction<CoffeeEntity> ethiopian = equal(CoffeeEntity_.origin, ETHIOPIA);
        Criteria<CoffeeEntity> citrus = tasting("Citrus");

        CursorResult<CoffeeEntity> first = withQueries((queries, context) -> queries.scroll(context, ethiopian, citrus, Cursor.first(1, Sort.NONE)));
        assertThat(Coffees.namesOf(first)).containsExactly(SIDAMO);
        assertThat(first.hasNext()).isTrue();

        CursorResult<CoffeeEntity> second = withQueries((queries, context) -> queries.scroll(context, ethiopian, citrus, Cursor.of(first.next(), 1, Sort.NONE)));
        assertThat(Coffees.namesOf(second)).containsExactly(YIRGACHEFFE);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("rejects scrolling on a key no cursor can carry, on the very first page")
    void rejectsAnUnusableCursorKey() {
        assertThatIllegalArgumentException()
                .as("ordering on the association itself makes a perfectly valid SQL ordering, but no cursor key")
                .isThrownBy(() -> withQueries((queries, context) -> queries.scroll(context, null, null, Cursor.first(2, Sort.parse("roaster")))))
                .withMessageContaining("Cannot build a cursor on nullable property roaster");

        assertThat(withQueries((queries, context) -> queries.search(context, null, null, Pageable.of(0, 2, Sort.parse("roaster")))).items())
                .as("the offset pagination keeps ordering on it, only a cursor needs to read the key back")
                .hasSize(2);
    }

    @Test
    @DisplayName("refuses to walk on keys that do not read back as written, rather than walking the same page forever")
    void refusesKeysThatDoNotReadBack() {
        // Writes every strength of the menu as zero, which reads back as a position before each of them
        CursorKeyCodec lossy = new CursorKeyCodec() {

            @Override
            public String format(String property, @Nullable Object value) {
                return value instanceof Integer strength ? String.valueOf(strength - strength % 10) : CursorKeyCodec.DEFAULT.format(property, value);
            }

            @Override
            public <Y> Y parse(String value, Class<Y> type) {
                return CursorKeyCodec.DEFAULT.parse(value, type);
            }

        };

        // Bounded, so that the walk fails the test rather than hangs it should it ever loop again
        assertThatIllegalStateException()
                .isThrownBy(() -> runInTransaction(entityManager -> {
                    RepositoryContext<CoffeeEntity> context = new TestContext<>(entityManager, BY_NAME, Map.of(), CursorCodec.DEFAULT, lossy);
                    Cursors.stream(cursor -> EntityQueries.of(CoffeeEntity.class).scroll(context, null, null, cursor), Sort.parse("strength"), 2)
                            .limit(100)
                            .toList();
                }))
                .withMessageContaining("cannot advance past its boundary row");
    }

    @Test
    @DisplayName("orders through the join the criteria already made, rather than joining the association twice")
    void ordersThroughTheJoinOfTheCriteria() {
        recordStatements();
        PaginationResult<CoffeeEntity> result = withQueries((queries, context) -> queries.search(
                context,
                null,
                (criteriaBuilder, query, root) -> criteriaBuilder.equal(root.join(CoffeeEntity_.roaster).get(RoasterEntity_.COUNTRY), ETHIOPIA),
                Pageable.of(0, 10, Sort.parse("roaster.name"))));

        assertThat(namesOf(result.items())).containsExactly(HARRAR, SIDAMO, YIRGACHEFFE);
        assertThat(statements())
                .filteredOn(sql -> sql.startsWith("select ce1_0"))
                .singleElement(as(STRING))
                .as("the ordering reaches the roaster through the join the criteria already made")
                .containsOnlyOnce("join roaster");
    }

    @Test
    @DisplayName("rejects an ordering on an unknown property")
    void rejectsAnUnknownOrdering() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withQueries((queries, context) -> queries.search(context, null, null, Pageable.of(0, 3, Sort.parse("caffeine")))))
                .withMessageContaining("unknown property caffeine");
    }

}