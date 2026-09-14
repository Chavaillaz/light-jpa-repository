package com.chavaillaz.jakarta.persistence.repository;

import static java.util.Collections.emptyMap;
import static java.util.Collections.nCopies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.chavaillaz.jakarta.persistence.repository.example.BeanBatchEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TerroirEntity;

@DisplayName("EntityOrdering")
class EntityOrderingTest extends HibernateTest {

    private static final BiFunction<CriteriaBuilder, Root<CoffeeEntity>, List<Order>> BY_NAME =
            (builder, root) -> List.of(builder.asc(root.get("name")));

    private static final BiFunction<CriteriaBuilder, Root<CoffeeEntity>, List<Order>> NO_DEFAULT =
            (builder, root) -> List.of();

    private EntityManager entityManager;

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class, BeanBatchEntity.class, TerroirEntity.class);
    }

    @BeforeEach
    void openEntityManager() {
        entityManager = sessionFactory.createEntityManager();
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    private EntityOrdering<CoffeeEntity> ordering() {
        return EntityOrdering.of(CoffeeEntity.class);
    }

    private RepositoryContext<CoffeeEntity> context(Map<String, String> properties,
                                                    BiFunction<CriteriaBuilder, Root<CoffeeEntity>, List<Order>> defaults) {
        return new TestContext<>(entityManager, defaults, properties);
    }

    private RepositoryContext<CoffeeEntity> openContext() {
        return context(emptyMap(), BY_NAME);
    }

    @Nested
    @DisplayName("resolving a property")
    class ResolveProperty {

        @Test
        @DisplayName("accepts any attribute when no searchable property is declared")
        void acceptsEverythingWhenOpen() {
            assertThat(ordering().resolveProperty(openContext(), "whatever")).isEqualTo("whatever");
        }

        @Test
        @DisplayName("maps the public name onto the entity path")
        void mapsThePublicName() {
            RepositoryContext<CoffeeEntity> context = context(Map.of("roaster", "roaster.name"), BY_NAME);
            assertThat(ordering().resolveProperty(context, "roaster")).isEqualTo("roaster.name");
        }

        @Test
        @DisplayName("rejects a property that is not declared")
        void rejectsUndeclaredProperty() {
            RepositoryContext<CoffeeEntity> context = context(Map.of("name", "name"), BY_NAME);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ordering().resolveProperty(context, "price"))
                    .withMessage("Cannot sort or filter on unknown property price");
        }

        @Test
        @DisplayName("accepts an already resolved path, such as one built from the static metamodel")
        void acceptsAnAlreadyResolvedPath() {
            RepositoryContext<CoffeeEntity> context = context(Map.of("roaster", "roaster.name"), BY_NAME);
            assertThat(ordering().resolveProperty(context, "roaster.name")).isEqualTo("roaster.name");
        }

        @Test
        @DisplayName("still rejects a path that is the target of no declared property")
        void rejectsAPathTargetOfNoDeclaredProperty() {
            RepositoryContext<CoffeeEntity> context = context(Map.of("roaster", "roaster.name"), BY_NAME);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ordering().resolveProperty(context, "notes.flavour"))
                    .withMessage("Cannot sort or filter on unknown property notes.flavour");
        }

    }

    @Nested
    @DisplayName("resolving a path")
    class ResolvePath {

        private Root<CoffeeEntity> root;

        @BeforeEach
        void createRoot() {
            CriteriaBuilder builder = entityManager.getCriteriaBuilder();
            root = builder.createQuery(CoffeeEntity.class).from(CoffeeEntity.class);
        }

        @Test
        @DisplayName("resolves a simple attribute")
        void resolvesASimpleAttribute() {
            assertThat(EntityOrdering.nameOf(ordering().resolvePath(openContext(), root, "price"))).isEqualTo("price");
        }

        @Test
        @DisplayName("resolves a nested attribute")
        void resolvesANestedAttribute() {
            assertThat(EntityOrdering.nameOf(ordering().resolvePath(openContext(), root, "roaster.country")))
                    .isEqualTo("roaster.country");
        }

        @Test
        @DisplayName("rejects an unknown attribute")
        void rejectsAnUnknownAttribute() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ordering().resolvePath(openContext(), root, "caffeine"))
                    .withMessageContaining("Cannot sort on unknown property caffeine");
        }

        @Test
        @DisplayName("rejects a collection, whose join would duplicate the entity once per child")
        void rejectsACollection() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ordering().resolvePath(openContext(), root, "notes"))
                    .withMessage("Cannot sort on collection property notes");
        }

        @ParameterizedTest
        @ValueSource(strings = {"name.length", "price.scale", "roastedAt.nano"})
        @DisplayName("rejects a property dereferencing a basic attribute, which Path#get raises an IllegalStateException for")
        void rejectsAPropertyBehindABasicAttribute(String property) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ordering().resolvePath(openContext(), root, property))
                    .withMessageContaining("Cannot sort on unknown property " + property);
        }

    }

    @Nested
    @DisplayName("resolving the complete ordering")
    class ResolveSort {

        @Test
        @DisplayName("falls back on the default ordering and appends the identifier")
        void fallsBackOnTheDefaultOrdering() {
            assertThat(ordering().resolveSort(openContext(), Sort.NONE).toString()).isEqualTo("name,id");
            assertThat(ordering().resolveSort(openContext(), null).toString()).isEqualTo("name,id");
        }

        @Test
        @DisplayName("only orders on the identifier when the repository declares no default ordering")
        void onlyOrdersOnTheIdentifier() {
            assertThat(ordering().resolveSort(context(emptyMap(), NO_DEFAULT), Sort.NONE).toString()).isEqualTo("id");
        }

        @Test
        @DisplayName("keeps the requested ordering and appends the identifier")
        void keepsTheRequestedOrdering() {
            assertThat(ordering().resolveSort(openContext(), Sort.parse("-price,name")).toString())
                    .isEqualTo("-price,name,id");
        }

        @Test
        @DisplayName("resolves the requested ordering against the searchable properties")
        void resolvesAgainstTheSearchableProperties() {
            RepositoryContext<CoffeeEntity> context = context(Map.of("brewer", "roaster.name"), BY_NAME);
            assertThat(ordering().resolveSort(context, Sort.parse("-brewer")).toString()).isEqualTo("-roaster.name,id");
        }

        @Test
        @DisplayName("does not append the identifier twice when it is already ordered on")
        void doesNotDuplicateTheIdentifier() {
            RepositoryContext<CoffeeEntity> context =
                    context(emptyMap(), (builder, root) -> List.of(builder.desc(root.get("id"))));
            assertThat(ordering().resolveSort(context, Sort.NONE).toString()).isEqualTo("-id");
            assertThat(ordering().resolveSort(openContext(), Sort.parse("-id")).toString()).isEqualTo("-id");
        }

        @Test
        @DisplayName("keeps a property once, the first criterion naming it winning")
        void keepsAPropertyOnce() {
            assertThat(ordering().resolveSort(openContext(), Sort.parse("name,name")).toString()).isEqualTo("name,id");
            assertThat(ordering().resolveSort(openContext(), Sort.parse("-price,name,price")).toString())
                    .isEqualTo("-price,name,id");
        }

        @Test
        @DisplayName("collapses two public properties aliasing the very same attribute")
        void collapsesTwoAliasesOfTheSameAttribute() {
            RepositoryContext<CoffeeEntity> context = context(Map.of("brewer", "roaster.name", "roaster", "roaster.name"), BY_NAME);

            assertThat(ordering().resolveSort(context, Sort.parse("brewer,roaster")).toString())
                    .isEqualTo("roaster.name,id");
        }

        @Test
        @DisplayName("spreads an embedded identifier over its components, ordered by name")
        void spreadsAnEmbeddedIdentifier() {
            RepositoryContext<BeanBatchEntity> context =
                    new TestContext<>(entityManager, (builder, root) -> List.of(), emptyMap());

            assertThat(EntityOrdering.of(BeanBatchEntity.class).resolveSort(context, Sort.NONE).toString())
                    .isEqualTo("id.batchNumber,id.roasterCode");
        }

        @Test
        @DisplayName("resolves an attribute named with letters beyond ASCII, as a Java identifier may be")
        void resolvesAnAttributeNamedBeyondAscii() {
            RepositoryContext<TerroirEntity> context =
                    new TestContext<>(entityManager, (builder, root) -> List.of(builder.asc(root.get("région"))), Map.of("region", "région"));

            assertThat(EntityOrdering.of(TerroirEntity.class).resolveSort(context, Sort.NONE).toString()).isEqualTo("région,id");
            assertThat(EntityOrdering.of(TerroirEntity.class).resolveSort(context, Sort.parse("-region")).toString()).isEqualTo("-région,id");
        }

        @Test
        @DisplayName("rejects a computed default ordering, unusable as a cursor key")
        void rejectsAComputedDefaultOrdering() {
            RepositoryContext<CoffeeEntity> context =
                    context(emptyMap(), (builder, root) -> List.of(builder.asc(builder.lower(root.get("name")))));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ordering().resolveSort(context, Sort.NONE))
                    .withMessageContaining("Cursor pagination requires an ordering on plain attributes");
        }

    }

    @Nested
    @DisplayName("building the ordering of a query")
    class BuildOrders {

        private Root<CoffeeEntity> root;

        @BeforeEach
        void createRoot() {
            CriteriaBuilder builder = entityManager.getCriteriaBuilder();
            root = builder.createQuery(CoffeeEntity.class).from(CoffeeEntity.class);
        }

        private List<String> namesOf(List<Order> orders) {
            return orders.stream()
                    .map(order -> (order.isAscending() ? "" : "-") + EntityOrdering.nameOf(order.getExpression()))
                    .toList();
        }

        @Test
        @DisplayName("compares a property once, the first criterion naming it winning")
        void comparesAPropertyOnce() {
            assertThat(namesOf(ordering().buildOrders(openContext(), root, Sort.parse("name,name,-name"))))
                    .containsExactly("name", "id");
            assertThat(namesOf(ordering().buildOrders(openContext(), root, Sort.parse("-price,name,price"))))
                    .containsExactly("-price", "name", "id");
        }

        @Test
        @DisplayName("collapses two public properties aliasing the very same attribute")
        void collapsesTwoAliasesOfTheSameAttribute() {
            RepositoryContext<CoffeeEntity> context = context(Map.of("brewer", "roaster.name", "roaster", "roaster.name"), BY_NAME);

            assertThat(namesOf(ordering().buildOrders(context, root, Sort.parse("brewer,roaster"))))
                    .containsExactly("roaster.name", "id");
        }

        @Test
        @DisplayName("does not append the identifier twice when it is already ordered on")
        void doesNotDuplicateTheIdentifier() {
            assertThat(namesOf(ordering().buildOrders(openContext(), root, Sort.parse("-id"))))
                    .containsExactly("-id");
        }

        @Test
        @DisplayName("keeps the ordering a repeated property cannot grow, the criteria coming from the consumers")
        void staysBoundedWhateverTheConsumerRepeats() {
            Sort repeated = Sort.parse(String.join(",", nCopies(200, "name")));

            assertThat(ordering().buildOrders(openContext(), root, repeated)).hasSize(2);
        }

    }

    @Nested
    @DisplayName("applying the ordering to a query")
    class ApplyOrder {

        private CriteriaQuery<CoffeeEntity> query;
        private Root<CoffeeEntity> root;

        @BeforeEach
        void createQuery() {
            query = entityManager.getCriteriaBuilder().createQuery(CoffeeEntity.class);
            root = query.from(CoffeeEntity.class);
        }

        @Test
        @DisplayName("applies the default ordering even when unpaged and unsorted, for a deterministic result")
        void ordersAnUnpagedQueryWithTheDefault() {
            ordering().applyOrder(openContext(), query, root, Pageable.UNPAGED);
            assertThat(query.getOrderList()).hasSize(2);
        }

        @Test
        @DisplayName("orders a paginated query, so that the pages are stable")
        void ordersAPaginatedQuery() {
            ordering().applyOrder(openContext(), query, root, Pageable.of(0, 10));
            assertThat(query.getOrderList()).hasSize(2);
        }

        @Test
        @DisplayName("orders on the requested criteria even when unpaged")
        void ordersOnTheRequestedCriteria() {
            ordering().applyOrder(openContext(), query, root, Pageable.sortedBy(Sort.parse("-price")));

            assertThat(query.getOrderList()).hasSize(2);
            assertThat(query.getOrderList().getFirst().isAscending()).isFalse();
        }

        @Test
        @DisplayName("does not override an ordering already built by the query visitor")
        void doesNotOverrideAnExistingOrdering() {
            query.orderBy(entityManager.getCriteriaBuilder().desc(root.get("price")));

            ordering().applyOrder(openContext(), query, root, Pageable.of(0, 10));

            assertThat(query.getOrderList()).hasSize(1);
        }

    }

    @Nested
    @DisplayName("naming an expression")
    class NameOf {

        @Test
        @DisplayName("rejects an expression that is not a path")
        void rejectsANonPath() {
            CriteriaBuilder builder = entityManager.getCriteriaBuilder();
            Root<CoffeeEntity> root = builder.createQuery(CoffeeEntity.class).from(CoffeeEntity.class);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EntityOrdering.nameOf(builder.upper(root.get("name"))))
                    .withMessageContaining("requires an ordering on plain attributes");
        }

        @Test
        @DisplayName("rejects the root itself, which names no attribute to order on")
        void rejectsTheRoot() {
            CriteriaBuilder builder = entityManager.getCriteriaBuilder();
            Root<CoffeeEntity> root = builder.createQuery(CoffeeEntity.class).from(CoffeeEntity.class);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EntityOrdering.nameOf(root))
                    .withMessageContaining("an attribute is required");
        }

    }

}