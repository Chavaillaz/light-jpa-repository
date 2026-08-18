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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import com.chavaillaz.jakarta.persistence.repository.example.RoastedCoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Scrolling through the coffee menu")
class CoffeeCursorTest extends HibernateTest {

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

    private CursorResult<CoffeeEntity> page(String token, int size, Sort sort) {
        return withRepository(repository -> repository.findAll(Cursor.of(token, size, sort)));
    }

    private CursorResult<CoffeeEntity> page(String token, Integer size, Sort sort) {
        return withRepository(repository -> repository.findAll(token, size, sort));
    }

    private CursorResult<CoffeeEntity> searchPage(String rsql, String token, int size, Sort sort) {
        return withRepository(repository -> repository.search(rsql, token, size, sort));
    }

    @Test
    @DisplayName("walks the whole menu forward, page after page")
    void walksForward() {
        List<String> visited = new ArrayList<>();
        String token = null;
        int pages = 0;

        while (true) {
            CursorResult<CoffeeEntity> result = page(token, 3, Sort.NONE);
            visited.addAll(namesOf(result));
            pages++;
            assertThat(result.size()).isEqualTo(3);
            if (!result.hasNext()) {
                assertThat(result.next()).isNull();
                break;
            }
            token = result.next();
        }

        assertThat(visited).containsExactlyElementsOf(MENU);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    @DisplayName("streams every entity lazily, one page at a time")
    void streamsEverything() {
        List<String> names = withRepository(repository ->
                repository.streamAll(Sort.NONE, 3).map(CoffeeEntity::getName).toList());

        assertThat(names).containsExactlyElementsOf(MENU);
    }

    @Test
    @DisplayName("fetches only the pages a short circuiting operation actually needs")
    void streamsLazily() {
        statistics().clear();

        List<String> names = withRepository(repository ->
                repository.streamAll(Sort.NONE, 2).limit(3).map(CoffeeEntity::getName).toList());

        assertThat(names).containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU, GEISHA);
        assertThat(statistics().getPrepareStatementCount())
                .as("the third item is on the second page of two, the third page was never fetched")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("reports the surrounding pages on the first page")
    void reportsTheSurroundingPages() {
        CursorResult<CoffeeEntity> first = page(null, 3, Sort.NONE);

        assertThat(namesOf(first)).containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU, GEISHA);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.next()).isNotBlank();
        assertThat(first.hasPrevious()).as("the first page has no preceding one").isFalse();
        assertThat(first.previous()).isNull();
    }

    @Test
    @DisplayName("walks backward and gives back the very same pages")
    void walksBackward() {
        CursorResult<CoffeeEntity> first = page(null, 3, Sort.NONE);
        CursorResult<CoffeeEntity> second = page(first.next(), 3, Sort.NONE);
        CursorResult<CoffeeEntity> back = page(second.previous(), 3, Sort.NONE);

        assertThat(namesOf(second)).containsExactly(HARRAR, KONA, SIDAMO);
        assertThat(second.hasPrevious()).isTrue();
        assertThat(namesOf(back))
                .as("a backward page is returned in the natural ordering")
                .containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU, GEISHA);
        assertThat(back.hasNext()).as("we come from the following page, so it necessarily exists").isTrue();
        assertThat(back.hasPrevious()).isFalse();
    }

    @Test
    @DisplayName("walks backward from the last page")
    void walksBackwardFromTheLastPage() {
        CursorResult<CoffeeEntity> first = page(null, 3, Sort.NONE);
        CursorResult<CoffeeEntity> second = page(first.next(), 3, Sort.NONE);
        CursorResult<CoffeeEntity> last = page(second.next(), 3, Sort.NONE);

        assertThat(namesOf(last)).containsExactly(YIRGACHEFFE);
        assertThat(last.hasNext()).isFalse();
        assertThat(last.hasPrevious()).isTrue();

        assertThat(namesOf(page(last.previous(), 3, Sort.NONE))).containsExactly(HARRAR, KONA, SIDAMO);
    }

    @Test
    @DisplayName("scrolls on a multi key ordering, including an enum")
    void scrollsOnAnEnumKey() {
        Sort sort = Sort.parse("roast,name");

        CursorResult<CoffeeEntity> first = page(null, 3, sort);
        assertThat(namesOf(first)).containsExactly(HARRAR, BOURBON_POINTU, GEISHA);

        CursorResult<CoffeeEntity> second = page(first.next(), 3, sort);
        assertThat(namesOf(second))
                .as("the seek crosses the boundary between two roasts")
                .containsExactly(YIRGACHEFFE, BLUE_MOUNTAIN, KONA);
    }

    @Test
    @DisplayName("keeps the restriction of the scope across the pages")
    void keepsTheRestriction() {
        CursorResult<CoffeeEntity> first = withRepository(repository -> repository.scrollByRoast(Roast.LIGHT, Cursor.first(2, Sort.NONE)));
        assertThat(namesOf(first)).containsExactly(BOURBON_POINTU, GEISHA);

        CursorResult<CoffeeEntity> second = withRepository(repository ->
                repository.scrollByRoast(Roast.LIGHT, Cursor.of(first.next(), 2, Sort.NONE)));
        assertThat(namesOf(second))
                .as("a page cannot escape the scope its token was issued within")
                .containsExactly(YIRGACHEFFE);
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    @DisplayName("is not shifted by a row inserted before the current position")
    void isNotShiftedByAnInsert() {
        CursorResult<CoffeeEntity> first = page(null, 3, Sort.NONE);

        // "Antigua" sorts before every visited row: an offset pagination would repeat "Geisha" on the next page
        persist(coffee("Antigua", "Guatemala", Roast.MEDIUM, "20.00", 5));

        assertThat(namesOf(page(first.next(), 3, Sort.NONE))).containsExactly(HARRAR, KONA, SIDAMO);
    }

    @Test
    @DisplayName("applies the default size when none is requested, and caps an excessive one")
    void normalisesTheRequestedSize() {
        CursorResult<CoffeeEntity> defaulted = page(null, null, Sort.NONE);
        assertThat(defaulted.size()).isEqualTo(Cursor.DEFAULT_SIZE);
        assertThat(namesOf(defaulted)).containsExactlyElementsOf(MENU);
        assertThat(defaulted.hasNext()).isFalse();

        assertThat(page(null, 100_000, Sort.NONE).size())
                .isEqualTo(Cursor.MAX_SIZE);
    }

    @Test
    @DisplayName("returns an empty result when nothing matches")
    void returnsAnEmptyResult() {
        deleteAll();

        CursorResult<CoffeeEntity> result = page(null, 3, Sort.NONE);

        assertThat(result.items()).isEmpty();
        assertThat(result.size()).isEqualTo(3);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.hasPrevious()).isFalse();
        assertThat(result.next()).isNull();
        assertThat(result.previous()).isNull();
    }

    @Test
    @DisplayName("scrolls through an RSQL query")
    void scrollsThroughAnRsqlQuery() {
        CursorResult<CoffeeEntity> first = searchPage("origin==" + ETHIOPIA, null, 2, Sort.NONE);
        assertThat(namesOf(first)).containsExactly(HARRAR, SIDAMO);

        CursorResult<CoffeeEntity> second = searchPage("origin==" + ETHIOPIA, first.next(), 2, Sort.NONE);
        assertThat(namesOf(second)).containsExactly(YIRGACHEFFE);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("scrolls through an RSQL query joining a collection, without duplicating the rows")
    void scrollsThroughACollectionJoin() {
        String rsql = "notes==Citrus,notes==Floral";

        CursorResult<CoffeeEntity> first = searchPage(rsql, null, 3, Sort.NONE);
        assertThat(namesOf(first)).containsExactly(BOURBON_POINTU, GEISHA, SIDAMO);

        CursorResult<CoffeeEntity> second = searchPage(rsql, first.next(), 3, Sort.NONE);
        assertThat(namesOf(second)).containsExactly(YIRGACHEFFE);
    }

    @Test
    @DisplayName("falls back on findAll when the RSQL query is blank")
    void fallsBackOnFindAll() {
        assertThat(namesOf(searchPage("  ", null, 2, Sort.NONE)))
                .containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU);
    }

    @Test
    @DisplayName("rejects a token issued for another ordering")
    void rejectsAReplayedToken() {
        CursorResult<CoffeeEntity> first = page(null, 3, Sort.parse("price"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> page(first.next(), 3, Sort.parse("-price")))
                .withMessageContaining("issued for another ordering");
    }

    @Test
    @DisplayName("rejects a malformed token")
    void rejectsAMalformedToken() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> page("not a valid token!", 3, Sort.NONE))
                .withMessage("Malformed cursor");
    }

    @Test
    @DisplayName("rejects a nullable attribute as a cursor key")
    void rejectsANullableKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> page(null, 3, Sort.parse("decaf")))
                .withMessageContaining("Cannot build a cursor on the null property decafLabel");
    }

    @Test
    @DisplayName("rejects a computed default ordering as a cursor key")
    void rejectsAComputedOrdering() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> inTransaction(entityManager -> new RoastedCoffeeRepositoryJpa(entityManager)
                        .findAll(Cursor.first(3, Sort.NONE))))
                .withMessageContaining("Cursor pagination requires an ordering on plain attributes");
    }

    @Test
    @DisplayName("issues the tokens with the codec of the repository")
    void usesTheCodecOfTheRepository() {
        Sort sort = Sort.parse("name");
        CursorResult<CoffeeEntity> first = inTransaction(entityManager -> new RoastedCoffeeRepositoryJpa(entityManager).findAll(Cursor.first(3, sort)));

        assertThat(first.next()).startsWith(RoastedCoffeeRepositoryJpa.PREFIX);

        CursorResult<CoffeeEntity> second = inTransaction(entityManager -> new RoastedCoffeeRepositoryJpa(entityManager).findAll(Cursor.of(first.next(), 3, sort)));
        assertThat(namesOf(second)).containsExactly(HARRAR, KONA, SIDAMO);
    }

}