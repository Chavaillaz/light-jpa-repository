package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BLUE_MOUNTAIN;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BOURBON_POINTU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.KONA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.MENU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.coffee;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Function;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;
import cz.jirutka.rsql.parser.ast.Node;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Searching the coffee menu")
class CoffeeSearchTest extends HibernateTest {

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

    @Nested
    @DisplayName("with an offset pagination")
    class Pagination {

        @Test
        @DisplayName("returns the requested page, ordered by the default ordering of the repository")
        void findsFirstPage() {
            PaginationResult<CoffeeEntity> result = withRepository(repository -> repository.findAll(0, 3));

            assertThat(namesOf(result)).containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU, GEISHA);
            assertThat(result.currentPage()).isZero();
            assertThat(result.pageSize()).isEqualTo(3);
            assertThat(result.totalItems()).isEqualTo(7);
            assertThat(result.totalPages()).isEqualTo(3);
            assertThat(result.hasNext()).isTrue();
            assertThat(result.hasPrevious()).isFalse();
        }

        @Test
        @DisplayName("returns the last, partial page")
        void findsLastPage() {
            PaginationResult<CoffeeEntity> result = withRepository(repository -> repository.findAll(2, 3));

            assertThat(namesOf(result)).containsExactly(YIRGACHEFFE);
            assertThat(result.totalItems()).isEqualTo(7);
            assertThat(result.totalPages()).isEqualTo(3);
            assertThat(result.hasNext()).isFalse();
            assertThat(result.hasPrevious()).isTrue();
        }

        @Test
        @DisplayName("returns an empty page beyond the last one, keeping the total")
        void findsPageBeyondTheEnd() {
            PaginationResult<CoffeeEntity> result = withRepository(repository -> repository.findAll(9, 3));

            assertThat(result.items()).isEmpty();
            assertThat(result.totalItems()).isEqualTo(7);
            assertThat(result.hasNext()).isFalse();
            assertThat(result.hasPrevious()).isTrue();
        }

        @Test
        @DisplayName("returns everything as a single page when unpaged")
        void findsEverythingWhenUnpaged() {
            PaginationResult<CoffeeEntity> result = withRepository(repository -> repository.findAll(Pageable.UNPAGED));

            assertThat(namesOf(result)).containsExactlyElementsOf(MENU);
            assertThat(result.currentPage()).isZero();
            assertThat(result.pageSize()).as("the single page is as large as the result set").isEqualTo(7);
            assertThat(result.totalPages()).isEqualTo(1);
            assertThat(result.totalItems()).isEqualTo(7);
            assertThat(result.hasNext()).isFalse();
        }

        @ParameterizedTest(name = "page {0} of size {1} is not paginated")
        @CsvSource(nullValues = "null", value = { "null,3", "0,null", "-1,3", "0,0", "1,-5" })
        @DisplayName("returns everything when the page coordinates are incomplete or invalid")
        void ignoresInvalidCoordinates(Integer page, Integer size) {
            PaginationResult<CoffeeEntity> result =
                    withRepository(repository -> repository.findAll(Pageable.of(page, size)));

            assertThat(namesOf(result)).containsExactlyElementsOf(MENU);
        }

        @Test
        @DisplayName("derives the total from the restriction of the results")
        void countsTheRestrictedResults() {
            PaginationResult<CoffeeEntity> result =
                    withRepository(repository -> repository.findStrongerThan(4, Pageable.of(0, 2)));

            assertThat(namesOf(result)).containsExactly(BLUE_MOUNTAIN, HARRAR);
            assertThat(result.totalItems()).as("the count follows the very same restriction").isEqualTo(4);
            assertThat(result.totalPages()).isEqualTo(2);
        }

    }

    @Nested
    @DisplayName("with an ordering")
    class Ordering {

        @Test
        @DisplayName("sorts on a single descending property")
        void sortsDescending() {
            PaginationResult<CoffeeEntity> result =
                    withRepository(repository -> repository.findAll(0, 3, Sort.parse("-price")));

            assertThat(namesOf(result)).containsExactly(GEISHA, BOURBON_POINTU, BLUE_MOUNTAIN);
        }

        @Test
        @DisplayName("sorts on several properties, in order of precedence")
        void sortsOnSeveralProperties() {
            List<CoffeeEntity> coffees = withRepository(repository ->
                    repository.findByOrigin(ETHIOPIA, Sort.parse("-strength,name")));

            assertThat(namesOf(coffees)).containsExactly(HARRAR, SIDAMO, YIRGACHEFFE);
        }

        @Test
        @DisplayName("sorts on the nested path a public property is mapped to")
        void sortsOnAnAliasedNestedProperty() {
            List<CoffeeEntity> coffees = withRepository(repository -> repository.findAll(Sort.parse("roaster,-name")));

            assertThat(namesOf(coffees))
                    .as("Kaldi Roasting first, then Moka Brothers, each by descending name")
                    .containsExactly(YIRGACHEFFE, SIDAMO, HARRAR, KONA, GEISHA, BOURBON_POINTU, BLUE_MOUNTAIN);
        }

        @Test
        @DisplayName("sorts on a metamodel built criterion, even when searchable properties restrict the entity")
        void sortsOnAMetamodelCriterionWithRestrictedProperties() {
            List<CoffeeEntity> flat = withRepository(repository ->
                    repository.findAll(Sort.of(SortCriterion.desc(CoffeeEntity_.strength))));
            assertThat(namesOf(flat)).first().isEqualTo(HARRAR);

            List<CoffeeEntity> nested = withRepository(repository ->
                    repository.findAll(Sort.of(SortCriterion.asc(CoffeeEntity_.roaster, RoasterEntity_.name), SortCriterion.desc(CoffeeEntity_.name))));
            assertThat(namesOf(nested))
                    .as("Kaldi Roasting first, then Moka Brothers, each by descending name")
                    .containsExactly(YIRGACHEFFE, SIDAMO, HARRAR, KONA, GEISHA, BOURBON_POINTU, BLUE_MOUNTAIN);
        }

        @Test
        @DisplayName("silently drops an entity whose nullable association is not set when sorting on a nested property")
        void dropsANullAssociationWhenSortingNested() {
            // A ManyToOne navigated through Path#get() for the ORDER BY is an implicit inner join: a coffee
            // without a roaster has nothing to join to, and is therefore excluded rather than sorted first or last
            persist(coffee("Antigua", "Guatemala", Roast.MEDIUM, "20.00", 5));

            List<CoffeeEntity> coffees = withRepository(repository -> repository.findAll(Sort.parse("roaster,-name")));

            assertThat(namesOf(coffees)).as("Antigua has no roaster and is silently excluded").doesNotContain("Antigua");
            assertThat(coffees).hasSize(7);
        }

        @Test
        @DisplayName("keeps the pagination stable by appending the identifier")
        void appendsTheIdentifier() {
            // Several coffees share the same roast, so the ordering is only total because the identifier is appended
            List<String> paged = withRepository(repository -> namesOf(repository.findAll(0, 4, Sort.parse("roast"))));
            List<String> repaged = withRepository(repository -> namesOf(repository.findAll(0, 4, Sort.parse("roast"))));

            assertThat(paged).isEqualTo(repaged);
        }

        @ParameterizedTest(name = "sorting on \"{0}\" is rejected")
        @ValueSource(strings = { "unknown", "roastedAt" })
        @DisplayName("rejects a property that is not searchable")
        void rejectsUnknownProperty(String property) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> withRepository(repository -> repository.findAll(0, 3, Sort.parse(property))))
                    .withMessageContaining("unknown property " + property);
        }

        @Test
        @DisplayName("rejects sorting on a collection property, searchable for RSQL filtering but not sortable")
        void rejectsSortingOnACollectionProperty() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> withRepository(repository -> repository.findAll(0, 3, Sort.parse("notes"))))
                    .withMessageContaining("collection property notes");
        }

    }

    @Nested
    @DisplayName("with an RSQL query")
    class Rsql {

        private List<CoffeeEntity> searchAll(String rsql) {
            return withRepository(repository -> repository.search(rsql));
        }

        private long countAll(String rsql) {
            return withRepository(repository -> repository.count(rsql));
        }

        @ParameterizedTest(name = "{0} matches {1}")
        @CsvSource(delimiter = ':', value = {
                "origin==Ethiopia                : 3",
                "origin!=Ethiopia                : 4",
                "roast==LIGHT                    : 3",
                "strength=gt=5                   : 3",
                "strength=ge=5                   : 4",
                "price=lt=30                     : 3",
                "origin==Ethiopia;roast==LIGHT   : 1",
                "origin==Ethiopia,origin==Panama : 4",
                "name==Geisha                    : 1",
                "origin=in=(Ethiopia,Panama)     : 4",
        })
        @DisplayName("filters the menu")
        void filters(String rsql, long expected) {
            assertThat(countAll(rsql)).isEqualTo(expected);
            assertThat(searchAll(rsql)).hasSize((int) expected);
        }

        @Test
        @DisplayName("falls back on findAll when the query is blank")
        void fallsBackOnFindAll() {
            assertThat(namesOf(searchAll("   "))).containsExactlyElementsOf(MENU);
            assertThat(namesOf(searchAll(null))).containsExactlyElementsOf(MENU);
            assertThat(countAll(null)).isEqualTo(7);
        }

        @Test
        @DisplayName("applies the default ordering even when unpaged, regardless of the insertion order")
        void ordersAnUnpagedQueryWithTheDefault() {
            // Inserted in reverse alphabetical order, opposite of the default one, so that a query relying on the
            // insertion or identifier order instead of an actual ORDER BY would be caught red handed
            persist(
                    coffee("Zambia AA", ETHIOPIA, Roast.LIGHT, "10.00", 5),
                    coffee("Yirga Batch", ETHIOPIA, Roast.LIGHT, "10.00", 5),
                    coffee("Xigera", ETHIOPIA, Roast.LIGHT, "10.00", 5));

            assertThat(namesOf(searchAll("origin==" + ETHIOPIA)))
                    .containsExactly(HARRAR, SIDAMO, "Xigera", "Yirga Batch", YIRGACHEFFE, "Zambia AA");
        }

        @Test
        @DisplayName("paginates and orders the filtered results")
        void paginatesTheResults() {
            PaginationResult<CoffeeEntity> result = withRepository(repository ->
                    repository.search("origin==" + ETHIOPIA, 0, 2, Sort.parse("-price")));

            assertThat(namesOf(result)).containsExactly(YIRGACHEFFE, SIDAMO);
            assertThat(result.totalItems()).isEqualTo(3);
            assertThat(result.totalPages()).isEqualTo(2);
        }

        @Test
        @DisplayName("returns distinct entities and a consistent count when the query joins a collection")
        void deduplicatesTheCollectionJoins() {
            // Yirgacheffe has both notes, so a plain join would return it twice
            PaginationResult<CoffeeEntity> result = withRepository(repository ->
                    repository.search("notes==Citrus,notes==Floral", 0, 10));

            assertThat(namesOf(result)).containsExactly(BOURBON_POINTU, GEISHA, SIDAMO, YIRGACHEFFE);
            assertThat(result.totalItems())
                    .as("the count is distinct too, otherwise it would drift from the results")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("filters on an association")
        void filtersOnAnAssociation() {
            assertThat(namesOf(searchAll("roaster==\"Moka Brothers\"")))
                    .containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU, GEISHA, KONA);
        }

        @Test
        @DisplayName("rejects a malformed query")
        void rejectsAMalformedQuery() {
            assertThatThrownBy(() -> withRepository(repository -> repository.count("origin=!=")))
                    .isInstanceOf(cz.jirutka.rsql.parser.RSQLParserException.class);
        }

        @Test
        @DisplayName("rejects a query on a property that is not searchable")
        void rejectsANonSearchableProperty() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> withRepository(repository -> repository.count("roastedAt=gt=0")))
                    .withMessageContaining("Cannot sort or filter on the unknown property roastedAt");
        }

        @Test
        @DisplayName("resolves a searchable property to the entity attribute path it is aliased to")
        void resolvesAnAliasedPropertyForFiltering() {
            assertThat(countAll("roaster==\"Moka Brothers\"")).isEqualTo(4);
        }

        @Test
        @DisplayName("routes count(String) through the overridable count(Node) hook")
        void routesCountThroughTheNodeHook() {
            long count = withRepository(CountingRepositoryJpa.class,
                    repository -> repository.count("origin==" + ETHIOPIA));

            assertThat(count).as("the override adds 1000 to whatever the real count is").isEqualTo(1003);
        }

        /**
         * Overrides {@code count(Node)} to prove {@link AbstractRepository#count(String)} actually routes through
         * it, rather than bypassing it by calling {@link RsqlQueries#count(Node)} directly.
         */
        private static class CountingRepositoryJpa extends CoffeeRepositoryJpa {

            CountingRepositoryJpa(EntityManager entityManager) {
                super(entityManager);
            }

            @Override
            protected long count(Node rsqlNode) {
                return super.count(rsqlNode) + 1000;
            }

        }

    }

}