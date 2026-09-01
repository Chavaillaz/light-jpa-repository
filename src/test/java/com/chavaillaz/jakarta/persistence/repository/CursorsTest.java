package com.chavaillaz.jakarta.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Cursors")
class CursorsTest {

    private static final CursorCodec CODEC = new Base64CursorCodec();
    private static final Sort SORT = Sort.parse("name");
    private static final List<Bean> BEANS = List.of(
            new Bean("Arabica"), new Bean("Liberica"), new Bean("Robusta"), new Bean("Excelsa"));

    private static CursorPosition position(List<String> values, boolean backward) {
        return new CursorPosition(values, backward, Cursors.fingerprint(SORT));
    }

    /**
     * A minimal bean, whose record accessor is read back by the reflective key reader.
     */
    private record Bean(String name) {
    }

    @Nested
    @DisplayName("decoding the requested position")
    class Position {

        @Test
        @DisplayName("returns no position for the first page")
        void returnsNoPositionForTheFirstPage() {
            assertThat(Cursors.position(CODEC, Cursor.first(10, SORT), SORT)).isNull();
            assertThat(Cursors.position(CODEC, Cursor.of("   ", 10, SORT), SORT)).isNull();
        }

        @Test
        @DisplayName("decodes a token issued for the very same ordering")
        void decodesAToken() {
            String token = CODEC.encode(position(List.of("Arabica"), false));

            CursorPosition decoded = Cursors.position(CODEC, Cursor.of(token, 10, SORT), SORT);

            assertThat(decoded.values()).containsExactly("Arabica");
            assertThat(decoded.backward()).isFalse();
        }

        @Test
        @DisplayName("rejects a token issued for another ordering")
        void rejectsAReplayedToken() {
            String token = CODEC.encode(position(List.of("Arabica"), false));
            Sort other = Sort.parse("-name");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Cursors.position(CODEC, Cursor.of(token, 10, other), other))
                    .withMessageContaining("issued for another ordering");
        }

    }

    @Nested
    @DisplayName("choosing the direction")
    class Direction {

        @Test
        @DisplayName("keeps the ordering for the first page and for a forward walk")
        void keepsTheOrdering() {
            assertThat(Cursors.direction(SORT, null)).isEqualTo(SORT);
            assertThat(Cursors.direction(SORT, position(List.of("a"), false))).isEqualTo(SORT);
        }

        @Test
        @DisplayName("reverses the ordering for a backward walk")
        void reversesTheOrdering() {
            assertThat(Cursors.direction(SORT, position(List.of("a"), true))).hasToString("-name");
        }

        @Test
        @DisplayName("detects a backward walk")
        void detectsABackwardWalk() {
            assertThat(Cursors.isBackward(null)).isFalse();
            assertThat(Cursors.isBackward(position(List.of("a"), true))).isTrue();
        }

    }

    @Nested
    @DisplayName("assembling the result")
    class Result {

        @Test
        @DisplayName("drops the extra row and reports the following page")
        void dropsTheExtraRow() {
            CursorResult<Bean> result = Cursors.toResult(CODEC, BEANS, Cursor.first(3, SORT), SORT, null, CursorKeyCodec.DEFAULT);

            assertThat(result.items()).extracting(Bean::name).containsExactly("Arabica", "Liberica", "Robusta");
            assertThat(result.size()).isEqualTo(3);
            assertThat(result.hasNext()).isTrue();
            assertThat(result.next()).isNotBlank();
            assertThat(result.hasPrevious()).isFalse();
            assertThat(result.previous()).isNull();
        }

        @Test
        @DisplayName("reports no following page when no extra row was fetched")
        void reportsNoFollowingPage() {
            CursorResult<Bean> result = Cursors.toResult(CODEC, BEANS.subList(0, 2), Cursor.first(3, SORT), SORT, null, CursorKeyCodec.DEFAULT);

            assertThat(result.items()).hasSize(2);
            assertThat(result.hasNext()).isFalse();
            assertThat(result.next()).isNull();
        }

        @Test
        @DisplayName("reports no following page when exactly the requested size was fetched, no extra row beyond it")
        void reportsNoFollowingPageOnAnExactBoundary() {
            CursorResult<Bean> result = Cursors.toResult(CODEC, BEANS.subList(0, 3), Cursor.first(3, SORT), SORT, null, CursorKeyCodec.DEFAULT);

            assertThat(result.items()).hasSize(3);
            assertThat(result.hasNext()).isFalse();
            assertThat(result.next()).isNull();
        }

        @Test
        @DisplayName("reports a preceding page as soon as a position was requested")
        void reportsAPrecedingPage() {
            CursorResult<Bean> result = Cursors.toResult(
                    CODEC, BEANS.subList(0, 2), Cursor.first(3, SORT), SORT, position(List.of("Arabica"), false), CursorKeyCodec.DEFAULT);

            assertThat(result.hasPrevious()).isTrue();
            assertThat(result.previous()).isNotBlank();
        }

        @Test
        @DisplayName("puts a backward page back in the natural ordering")
        void reversesABackwardPage() {
            CursorResult<Bean> result =
                    Cursors.toResult(CODEC, BEANS, Cursor.first(3, SORT), SORT, position(List.of("Zambia"), true), CursorKeyCodec.DEFAULT);

            assertThat(result.items()).extracting(Bean::name).containsExactly("Robusta", "Liberica", "Arabica");
            assertThat(result.hasNext()).as("we come from the following page").isTrue();
            assertThat(result.hasPrevious()).as("an extra row was fetched backwards").isTrue();
        }

        @Test
        @DisplayName("reports no preceding page when the backward walk reaches the beginning")
        void reachesTheBeginning() {
            CursorResult<Bean> result = Cursors.toResult(
                    CODEC, BEANS.subList(0, 2), Cursor.first(3, SORT), SORT, position(List.of("Zambia"), true), CursorKeyCodec.DEFAULT);

            assertThat(result.hasPrevious()).isFalse();
            assertThat(result.hasNext()).isTrue();
        }

        @Test
        @DisplayName("returns an empty result, with no token, when nothing was fetched")
        void returnsAnEmptyResult() {
            CursorResult<Bean> result = Cursors.toResult(CODEC, List.of(), Cursor.first(3, SORT), SORT, null, CursorKeyCodec.DEFAULT);

            assertThat(result).isEqualTo(CursorResult.empty(3));
        }

        @Test
        @DisplayName("keeps a way forward when a backward walk lands on an emptied page")
        void keepsAWayForwardFromAnEmptyBackwardPage() {
            // The rows preceding the position were deleted in between: without a token, the consumer would be
            // stranded on an empty page, unable to walk back to the one it came from
            CursorResult<Bean> result = Cursors.toResult(
                    CODEC, List.of(), Cursor.first(3, SORT), SORT, position(List.of("Robusta"), true), CursorKeyCodec.DEFAULT);

            assertThat(result.items()).isEmpty();
            assertThat(result.hasPrevious()).isFalse();
            assertThat(result.hasNext()).isTrue();
            assertThat(CODEC.decode(result.next())).isEqualTo(position(List.of("Robusta"), false));
        }

        @Test
        @DisplayName("keeps a way back when a forward walk lands on an emptied page")
        void keepsAWayBackFromAnEmptyForwardPage() {
            // The rows following the position were deleted in between: the consumer is on an empty page it can
            // only leave backwards, which the mirror token makes possible
            CursorResult<Bean> result = Cursors.toResult(
                    CODEC, List.of(), Cursor.first(3, SORT), SORT, position(List.of("Robusta"), false), CursorKeyCodec.DEFAULT);

            assertThat(result.items()).isEmpty();
            assertThat(result.hasNext()).isFalse();
            assertThat(result.hasPrevious()).isTrue();
            assertThat(CODEC.decode(result.previous())).isEqualTo(position(List.of("Robusta"), true));
        }

        @Test
        @DisplayName("issues tokens bound to the ordering, on the boundary rows")
        void issuesBoundTokens() {
            CursorResult<Bean> result =
                    Cursors.toResult(CODEC, BEANS, Cursor.first(3, SORT), SORT, position(List.of("A"), false), CursorKeyCodec.DEFAULT);

            assertThat(CODEC.decode(result.next())).isEqualTo(position(List.of("Robusta"), false));
            assertThat(CODEC.decode(result.previous())).isEqualTo(position(List.of("Arabica"), true));
        }

    }

    @Nested
    @DisplayName("walking the pages as a stream")
    class Walk {

        private CursorResult<Bean> page(List<Bean> items, @Nullable String next) {
            return new CursorResult<>(items, 2, next, null, next != null, false);
        }

        @Test
        @DisplayName("fetches nothing until the stream is consumed")
        void fetchesNothingUntilConsumed() {
            AtomicInteger fetched = new AtomicInteger();

            Stream<Bean> stream = Cursors.stream(cursor -> {
                fetched.incrementAndGet();
                return page(BEANS.subList(0, 2), null);
            }, SORT, 2);

            assertThat(fetched).as("not even the first page is fetched on the call").hasValue(0);
            assertThat(stream.toList()).hasSize(2);
            assertThat(fetched).hasValue(1);
        }

        @Test
        @DisplayName("fetches only the pages a short circuiting operation needs")
        void fetchesOnlyTheNeededPages() {
            AtomicInteger fetched = new AtomicInteger();

            List<Bean> walked = Cursors.stream(cursor -> {
                fetched.incrementAndGet();
                return page(BEANS.subList(0, 2), "more");
            }, SORT, 2).limit(3).toList();

            assertThat(walked).hasSize(3);
            assertThat(fetched).as("the third item comes from the second page, the third one is never asked for").hasValue(2);
        }

        @Test
        @DisplayName("stops on a page announcing a successor it hands no token for, instead of walking forever")
        void stopsOnAMissingToken() {
            AtomicInteger fetched = new AtomicInteger();

            List<Bean> walked = Cursors.stream(cursor -> {
                fetched.incrementAndGet();
                return new CursorResult<>(BEANS.subList(0, 2), 2, null, null, true, false);
            }, SORT, 2).toList();

            assertThat(walked).hasSize(2);
            assertThat(fetched).as("requesting the same page again would restart the walk from the first one").hasValue(1);
        }

    }

    @Nested
    @DisplayName("fingerprinting an ordering")
    class Fingerprint {

        @Test
        @DisplayName("is stable and discriminates the direction and the order of the criteria")
        void discriminatesTheOrderings() {
            assertThat(Cursors.fingerprint(Sort.parse("name,id")))
                    .isEqualTo(Cursors.fingerprint(Sort.parse("name,id")))
                    .isNotEqualTo(Cursors.fingerprint(Sort.parse("-name,id")))
                    .isNotEqualTo(Cursors.fingerprint(Sort.parse("id,name")));
        }

    }

}