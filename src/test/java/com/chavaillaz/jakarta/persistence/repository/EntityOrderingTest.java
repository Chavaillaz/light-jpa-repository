package com.chavaillaz.jakarta.persistence.repository;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import com.chavaillaz.jakarta.persistence.repository.example.BeanBatchEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EntityOrdering")
class EntityOrderingTest extends HibernateTest {

    private static final BiFunction<CriteriaBuilder, Root<CoffeeEntity>, List<Order>> BY_NAME =
            (builder, root) -> List.of(builder.asc(root.get("name")));

    private static final BiFunction<CriteriaBuilder, Root<CoffeeEntity>, List<Order>> NO_DEFAULT =
            (builder, root) -> List.of();

    private EntityManager entityManager;

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class, BeanBatchEntity.class);
    }

    @BeforeEach
    void openEntityManager() {
        entityManager = sessionFactory.createEntityManager();
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    private EntityOrdering<CoffeeEntity> ordering(Map<String, String> properties,
            BiFunction<CriteriaBuilder, Root<CoffeeEntity>, List<Order>> defaults) {
        return new EntityOrdering<>(entityManager, CoffeeEntity.class, defaults, () -> properties);
    }

    private EntityOrdering<CoffeeEntity> openOrdering() {
        return ordering(emptyMap(), BY_NAME);
    }

    @Nested
    @DisplayName("resolving a property")
    class ResolveProperty {

        @Test
        @DisplayName("accepts any attribute when no searchable property is declared")
        void acceptsEverythingWhenOpen() {
            assertThat(openOrdering().resolveProperty("whatever")).isEqualTo("whatever");
        }

        @Test
        @DisplayName("maps the public name onto the entity path")
        void mapsThePublicName() {
            EntityOrdering<CoffeeEntity> ordering = ordering(Map.of("roaster", "roaster.name"), BY_NAME);
            assertThat(ordering.resolveProperty("roaster")).isEqualTo("roaster.name");
        }

        @Test
        @DisplayName("rejects a property that is not declared")
        void rejectsUndeclaredProperty() {
            EntityOrdering<CoffeeEntity> ordering = ordering(Map.of("name", "name"), BY_NAME);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ordering.resolveProperty("price"))
                    .withMessage("Cannot sort or filter on the unknown property price");
        }

        @Test
        @DisplayName("accepts an already resolved path, such as one built from the static metamodel")
        void acceptsAnAlreadyResolvedPath() {
            EntityOrdering<CoffeeEntity> ordering = ordering(Map.of("roaster", "roaster.name"), BY_NAME);
            assertThat(ordering.resolveProperty("roaster.name")).isEqualTo("roaster.name");
        }

        @Test
        @DisplayName("still rejects a path that is the target of no declared property")
        void rejectsAPathTargetOfNoDeclaredProperty() {
            EntityOrdering<CoffeeEntity> ordering = ordering(Map.of("roaster", "roaster.name"), BY_NAME);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ordering.resolveProperty("notes.flavour"))
                    .withMessage("Cannot sort or filter on the unknown property notes.flavour");
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
            assertThat(EntityOrdering.nameOf(openOrdering().resolvePath(root, "price"))).isEqualTo("price");
        }

        @Test
        @DisplayName("resolves a nested attribute")
        void resolvesANestedAttribute() {
            assertThat(EntityOrdering.nameOf(openOrdering().resolvePath(root, "roaster.country")))
                    .isEqualTo("roaster.country");
        }

        @Test
        @DisplayName("rejects an unknown attribute")
        void rejectsAnUnknownAttribute() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> openOrdering().resolvePath(root, "caffeine"))
                    .withMessageContaining("Cannot sort on the unknown property caffeine");
        }

        @Test
        @DisplayName("rejects a collection, which the distinct queries cannot order on")
        void rejectsACollection() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> openOrdering().resolvePath(root, "notes"))
                    .withMessage("Cannot sort on the collection property notes");
        }

    }

    @Nested
    @DisplayName("resolving the complete ordering")
    class ResolveSort {

        @Test
        @DisplayName("falls back on the default ordering and appends the identifier")
        void fallsBackOnTheDefaultOrdering() {
            assertThat(openOrdering().resolveSort(Sort.NONE).toString()).isEqualTo("name,id");
            assertThat(openOrdering().resolveSort(null).toString()).isEqualTo("name,id");
        }

        @Test
        @DisplayName("only orders on the identifier when the repository declares no default ordering")
        void onlyOrdersOnTheIdentifier() {
            assertThat(ordering(emptyMap(), NO_DEFAULT).resolveSort(Sort.NONE).toString()).isEqualTo("id");
        }

        @Test
        @DisplayName("keeps the requested ordering and appends the identifier")
        void keepsTheRequestedOrdering() {
            assertThat(openOrdering().resolveSort(Sort.parse("-price,name")).toString())
                    .isEqualTo("-price,name,id");
        }

        @Test
        @DisplayName("resolves the requested ordering against the searchable properties")
        void resolvesAgainstTheSearchableProperties() {
            EntityOrdering<CoffeeEntity> ordering = ordering(Map.of("brewer", "roaster.name"), BY_NAME);
            assertThat(ordering.resolveSort(Sort.parse("-brewer")).toString()).isEqualTo("-roaster.name,id");
        }

        @Test
        @DisplayName("does not append the identifier twice when it is already ordered on")
        void doesNotDuplicateTheIdentifier() {
            EntityOrdering<CoffeeEntity> ordering =
                    ordering(emptyMap(), (builder, root) -> List.of(builder.desc(root.get("id"))));
            assertThat(ordering.resolveSort(Sort.NONE).toString()).isEqualTo("-id");
            assertThat(openOrdering().resolveSort(Sort.parse("-id")).toString()).isEqualTo("-id");
        }

        @Test
        @DisplayName("spreads an embedded identifier over its components, ordered by name")
        void spreadsAnEmbeddedIdentifier() {
            EntityOrdering<BeanBatchEntity> ordering = new EntityOrdering<>(
                    entityManager, BeanBatchEntity.class, (builder, root) -> List.of(), Map::of);

            assertThat(ordering.resolveSort(Sort.NONE).toString()).isEqualTo("id.batchNumber,id.roasterCode");
        }

        @Test
        @DisplayName("rejects a computed default ordering, unusable as a cursor key")
        void rejectsAComputedDefaultOrdering() {
            EntityOrdering<CoffeeEntity> ordering =
                    ordering(emptyMap(), (builder, root) -> List.of(builder.asc(builder.lower(root.get("name")))));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ordering.resolveSort(Sort.NONE))
                    .withMessageContaining("Cursor pagination requires an ordering on plain attributes");
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
            openOrdering().applyOrder(query, root, Pageable.UNPAGED);
            assertThat(query.getOrderList()).hasSize(2);
        }

        @Test
        @DisplayName("orders a paginated query, so that the pages are stable")
        void ordersAPaginatedQuery() {
            openOrdering().applyOrder(query, root, Pageable.of(0, 10));
            assertThat(query.getOrderList()).hasSize(2);
        }

        @Test
        @DisplayName("orders on the requested criteria even when unpaged")
        void ordersOnTheRequestedCriteria() {
            openOrdering().applyOrder(query, root, Pageable.sortedBy(Sort.parse("-price")));

            assertThat(query.getOrderList()).hasSize(2);
            assertThat(query.getOrderList().getFirst().isAscending()).isFalse();
        }

        @Test
        @DisplayName("does not override an ordering already built by the query visitor")
        void doesNotOverrideAnExistingOrdering() {
            query.orderBy(entityManager.getCriteriaBuilder().desc(root.get("price")));

            openOrdering().applyOrder(query, root, Pageable.of(0, 10));

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

    }

}