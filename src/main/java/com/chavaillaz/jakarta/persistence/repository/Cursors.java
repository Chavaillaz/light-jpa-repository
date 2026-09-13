package com.chavaillaz.jakarta.persistence.repository;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.jspecify.annotations.Nullable;

/**
 * Cursor helpers shared by the query collaborators of the repositories.
 * <p>
 * The {@link Cursor} itself stays free of any persistence dependency, this class holding the decoding of the
 * requested position, the direction of the walk and the assembly of the resulting page.
 */
public final class Cursors {

    /**
     * Number of bytes of the digest kept as the fingerprint of an ordering, wide enough for two orderings never
     * to collide by accident while keeping the tokens short.
     */
    private static final int FINGERPRINT_LENGTH = 8;

    private Cursors() {
        // This utility class should not be instantiated
    }

    /**
     * Decodes the requested position and checks that it was issued for the very same ordering, replaying a token
     * on another ordering being meaningless: the seek predicate would then keep rows the ordering does not place
     * after the boundary one.
     *
     * @param codec        The codec to decode the token with
     * @param cursor       The requested position, size and ordering
     * @param resolvedSort The resolved ordering the token is checked against
     * @return The requested position, or {@code null} when the first page is requested
     * @throws IllegalArgumentException if the token is malformed or was issued for another ordering
     */
    public static @Nullable CursorPosition position(CursorCodec codec, Cursor cursor, Sort resolvedSort) {
        if (cursor.isFirst()) {
            return null;
        }

        CursorPosition position = codec.decode(cursor.token());
        if (!fingerprint(resolvedSort).equals(position.fingerprint())) {
            throw new IllegalArgumentException("The cursor was issued for another ordering and cannot be replayed");
        }
        return position;
    }

    /**
     * Gets the ordering to apply to the query, which is the resolved one reversed when the previous page is
     * requested, walking backwards being the same seek in the opposite direction.
     *
     * @param resolvedSort The resolved ordering of the query
     * @param position     The requested position, or {@code null} for the first page
     * @return The ordering to apply to the query
     */
    public static Sort direction(Sort resolvedSort, @Nullable CursorPosition position) {
        return isBackward(position) ? resolvedSort.reversed() : resolvedSort;
    }

    /**
     * Checks if the previous page is requested.
     *
     * @param position The requested position, or {@code null} for the first page
     * @return {@code true} when walking backwards, {@code false} otherwise
     */
    public static boolean isBackward(@Nullable CursorPosition position) {
        return position != null && position.backward();
    }

    /**
     * Builds the resulting page from the fetched rows, which contain one extra row when another page exists.
     * <p>
     * The extra row is dropped, the page is put back in the natural ordering when walking backwards, and the
     * tokens of the surrounding pages are derived from the boundary rows.
     *
     * @param <T>          The type of the returned items
     * @param codec        The codec to encode the surrounding tokens with
     * @param fetched      The fetched rows, one more than the requested size when another page exists
     * @param cursor       The requested position, size and ordering
     * @param resolvedSort The resolved ordering the tokens are issued for
     * @param position     The requested position, or {@code null} for the first page
     * @param keyCodec     The codec formatting the ordering keys of the boundary rows into the tokens
     * @return The corresponding page, with the tokens of the surrounding ones
     */
    public static <T> CursorResult<T> toResult(CursorCodec codec, List<T> fetched, Cursor cursor, Sort resolvedSort, @Nullable CursorPosition position, CursorKeyCodec keyCodec) {
        // The keys are read lazily, so that only the boundary rows of the page are actually reflected upon
        List<Supplier<List<String>>> keys = fetched.stream()
                .<Supplier<List<String>>>map(item -> () -> Keysets.valuesOf(item, resolvedSort, keyCodec))
                .toList();
        return toResult(codec, fetched, keys, cursor, resolvedSort, position);
    }

    /**
     * Builds the resulting page from the fetched rows and from the ordering keys the query itself returned.
     * <p>
     * Reading the keys back from the entity is only a fallback: the {@code order by} clause compares the value
     * the database holds, whereas an entity exposes the value its accessor returns, and nothing guarantees the
     * two are the same. Selecting the keys alongside the entity keeps the token and the seek predicate expressed
     * in the very same terms.
     *
     * @param <T>          The type of the returned items
     * @param codec        The codec to encode the surrounding tokens with
     * @param fetched      The fetched rows, one more than the requested size when another page exists
     * @param keys         The textual ordering keys of each fetched row, in the same order
     * @param cursor       The requested position, size and ordering
     * @param resolvedSort The resolved ordering the tokens are issued for
     * @param position     The requested position, or {@code null} for the first page
     * @return The corresponding page, with the tokens of the surrounding ones
     */
    public static <T> CursorResult<T> toResult(CursorCodec codec, List<T> fetched, List<Supplier<List<String>>> keys, Cursor cursor, Sort resolvedSort, @Nullable CursorPosition position) {
        boolean backward = isBackward(position);
        boolean hasMore = fetched.size() > cursor.size();
        int size = hasMore ? cursor.size() : fetched.size();

        List<T> items = new ArrayList<>(fetched.subList(0, size));
        List<Supplier<List<String>>> boundaries = new ArrayList<>(keys.subList(0, size));
        if (backward) {
            Collections.reverse(items);
            Collections.reverse(boundaries);
        }
        if (items.isEmpty()) {
            if (position == null) {
                // The very first page is empty, so nothing matches at all and there is nowhere to navigate to
                return CursorResult.empty(cursor.size());
            }

            // Walking onto an emptied page, the rows we came from having been deleted in between, the consumer
            // would otherwise be stranded with no token at all: the position it walked from is re-issued in the
            // opposite direction, so that it can still reach whatever now lies on the side it came from
            return backward
                    ? new CursorResult<>(List.of(), cursor.size(), reissued(codec, position, resolvedSort, false), null, true, false)
                    : new CursorResult<>(List.of(), cursor.size(), null, reissued(codec, position, resolvedSort, true), false, true);
        }

        // Walking backwards, a following page necessarily exists, since it is the one we come from
        boolean hasNext = backward || hasMore;
        boolean hasPrevious = backward ? hasMore : position != null;
        String fingerprint = fingerprint(resolvedSort);

        return new CursorResult<>(
                items,
                cursor.size(),
                hasNext ? token(codec, boundaries.getLast().get(), false, fingerprint) : null,
                hasPrevious ? token(codec, boundaries.getFirst().get(), true, fingerprint) : null,
                hasNext,
                hasPrevious);
    }

    /**
     * Lazily walks every entity a cursor query returns, fetching a page at a time instead of loading the whole
     * result set at once.
     * <p>
     * The pages are fetched on demand as the stream is consumed, so a short-circuiting operation such as
     * {@link Stream#limit(long)} or {@link Stream#findFirst()} fetches only the pages it actually needs, and so
     * does an {@link Stream#iterator() iterator} pulling the items one at a time. Only the fetching is lazy
     * though, not the retention: the entities walked stay managed by the persistence context until the
     * transaction ends, and the stream must be consumed within that very same transaction.
     *
     * @param <T>      The type of the returned items
     * @param pages    The page fetcher, which is the cursor query being walked
     * @param sort     The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @param pageSize The number of items fetched per underlying page, capped to {@link Cursor#MAX_SIZE}
     * @return The lazy stream of every matching item, in the requested ordering
     */
    public static <T> Stream<T> stream(Function<Cursor, CursorResult<T>> pages, Sort sort, int pageSize) {
        // A spliterator of its own rather than a flat mapped iteration of the pages: flatMap only stays lazy for a
        // short-circuiting terminal operation, and pushes the whole inner iteration, every page of it, into a
        // buffer as soon as the stream is pulled through its iterator or its spliterator instead
        return StreamSupport.stream(new PageWalk<>(pages, sort, pageSize), false);
    }

    /**
     * Walk of the pages of a cursor query, fetching a page only once every item of the previous one was handed
     * over, whether the stream is consumed by a terminal operation or pulled through its iterator.
     *
     * @param <T> The type of the returned items
     */
    private static final class PageWalk<T> extends Spliterators.AbstractSpliterator<T> {

        private final Function<Cursor, CursorResult<T>> pages;
        private final Sort sort;
        private final int pageSize;

        /**
         * The request of the page to fetch once the items at hand are exhausted, {@code null} when the walk is
         * over. Nothing is fetched on construction, so that a stream nobody consumes issues no query at all.
         */
        private @Nullable Cursor following;

        private Iterator<T> items = Collections.emptyIterator();

        private PageWalk(Function<Cursor, CursorResult<T>> pages, Sort sort, int pageSize) {
            super(Long.MAX_VALUE, ORDERED | NONNULL);
            this.pages = pages;
            this.sort = sort;
            this.pageSize = pageSize;
            this.following = Cursor.first(pageSize, sort);
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            while (!items.hasNext()) {
                if (following == null) {
                    return false;
                }
                CursorResult<T> page = pages.apply(following);
                items = page.items().iterator();
                // The token is what makes the walk progress: a page announcing a successor without handing one
                // over would otherwise be requested as a first page again, and the walk would never terminate
                following = page.hasNext() && page.next() != null ? Cursor.of(page.next(), pageSize, sort) : null;
            }
            action.accept(items.next());
            return true;
        }

        @Override
        public @Nullable Spliterator<T> trySplit() {
            // The pages are fetched through an entity manager, which is neither thread safe nor usable outside the
            // thread of its transaction: a split would have another thread pull the following items, and query
            return null;
        }

    }

    /**
     * Computes the fingerprint of an ordering, which binds a token to the ordering it was issued for.
     * <p>
     * A digest is used rather than the hash code of the textual ordering: two orderings sharing a 32 bit hash are
     * trivially written down by hand, {@code "Aa"} and {@code "BB"} being the classic pair, and a colliding
     * ordering of the same arity replays a token the seek predicate then compares against the wrong keys, which
     * is precisely what the fingerprint exists to prevent. This remains an accident guard and not an integrity
     * tag, a consumer being free to compute the fingerprint of any ordering it likes; reject forged positions by
     * signing them in a {@link CursorCodec} instead.
     *
     * @param sort The ordering to fingerprint
     * @return The corresponding fingerprint
     */
    public static String fingerprint(Sort sort) {
        return HexFormat.of().formatHex(digest(sort.toString()), 0, FINGERPRINT_LENGTH);
    }

    /**
     * Digests the textual representation of an ordering.
     *
     * @param ordering The textual representation to digest
     * @return The corresponding digest
     */
    private static byte[] digest(String ordering) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(ordering.getBytes(UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256, so this cannot happen on a conformant runtime
            throw new IllegalStateException("The SHA-256 digest the cursor fingerprints are computed with is missing", e);
        }
    }

    /**
     * Re-issues the given position in the opposite direction, its keys being the boundary of the page the
     * consumer walked from, so that an emptied page still leads back to it.
     *
     * @param codec        The codec to encode the token with
     * @param position     The position to re-issue
     * @param resolvedSort The resolved ordering the token is issued for
     * @param backward     The direction to re-issue it in
     * @return The corresponding token
     */
    private static String reissued(CursorCodec codec, CursorPosition position, Sort resolvedSort, boolean backward) {
        return codec.encode(new CursorPosition(position.values(), backward, fingerprint(resolvedSort)));
    }

    private static String token(CursorCodec codec, List<String> keys, boolean backward, String fingerprint) {
        return codec.encode(new CursorPosition(keys, backward, fingerprint));
    }

}