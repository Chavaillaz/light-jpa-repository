package com.chavaillaz.jakarta.persistence.repository;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.jspecify.annotations.Nullable;

/**
 * Cursor helpers shared by the query collaborators of the repositories: the decoding of the requested position,
 * the direction of the walk and the assembly of the resulting page, the {@link Cursor} itself staying free of any
 * persistence dependency.
 */
public final class Cursors {

    /**
     * Number of bytes of the digest kept as the fingerprint of an ordering, enough to rule out an accidental
     * collision while keeping the tokens short.
     */
    private static final int FINGERPRINT_LENGTH = 8;

    private Cursors() {
        // This utility class should not be instantiated
    }

    /**
     * Decodes the requested position and checks that it was issued for the very same ordering, the seek predicate
     * of a position being meaningless for another one.
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
     * Builds the resulting page from the fetched rows, reading the ordering keys of the boundary rows back off the
     * items themselves, for a query selecting the entity alone.
     *
     * @param <T>          The type of the returned items
     * @param codec        The codec to encode the surrounding tokens with
     * @param fetched      The fetched rows, one more than the requested size when another page exists
     * @param cursor       The requested position, size and ordering
     * @param resolvedSort The resolved ordering the tokens are issued for
     * @param position     The requested position, or {@code null} for the first page
     * @param keyCodec     The codec formatting the ordering keys of the boundary rows into the tokens
     * @return The corresponding page, with the tokens of the surrounding ones
     * @throws IllegalStateException if the page would issue the very position it was requested with, which only a
     *                               key reading back as another value than the one written brings about
     * @see Keysets#valuesOf(Object, Sort, CursorKeyCodec)
     */
    public static <T> CursorResult<T> toResult(CursorCodec codec, List<T> fetched, Cursor cursor, Sort resolvedSort, @Nullable CursorPosition position, CursorKeyCodec keyCodec) {
        // Read lazily, so that only the boundary rows are reflected upon
        List<Supplier<List<String>>> keys = fetched.stream()
                .<Supplier<List<String>>>map(item -> () -> Keysets.valuesOf(item, resolvedSort, keyCodec))
                .toList();
        return toResult(codec, fetched, keys, cursor, resolvedSort, position);
    }

    /**
     * Builds the resulting page from the fetched rows, which contain one extra row when another page exists, and
     * from the ordering keys the query selected alongside them.
     * <p>
     * The extra row is dropped, a backward page is put back in the natural ordering, and the tokens of the
     * surrounding pages are built from the keys of the boundary rows.
     *
     * @param <T>          The type of the returned items
     * @param codec        The codec to encode the surrounding tokens with
     * @param fetched      The fetched rows, one more than the requested size when another page exists
     * @param keys         The textual ordering keys of each fetched row, in the same order
     * @param cursor       The requested position, size and ordering
     * @param resolvedSort The resolved ordering the tokens are issued for
     * @param position     The requested position, or {@code null} for the first page
     * @return The corresponding page, with the tokens of the surrounding ones
     * @throws IllegalStateException if the page would issue the very position it was requested with, which only a
     *                               key reading back as another value than the one written brings about
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
                // Nothing matches at all, so there is nowhere to navigate to
                return CursorResult.empty(cursor.size());
            }

            // The rows walked onto were deleted in between: the position is re-issued in the opposite direction,
            // so that the consumer is not stranded on an empty page holding no token at all
            return backward
                    ? new CursorResult<>(List.of(), cursor.size(), reissued(codec, position, resolvedSort, false), null, true, false)
                    : new CursorResult<>(List.of(), cursor.size(), null, reissued(codec, position, resolvedSort, true), false, true);
        }

        // A backward walk comes from the following page, which therefore exists
        boolean hasNext = backward || hasMore;
        boolean hasPrevious = backward ? hasMore : position != null;
        List<String> nextKeys = hasNext ? boundaries.getLast().get() : null;
        List<String> previousKeys = hasPrevious ? boundaries.getFirst().get() : null;

        if (position != null && position.values().equals(backward ? previousKeys : nextKeys)) {
            // The identifier ends every ordering, so these are the keys of the boundary row itself, which the seek
            // predicate excludes: it only came back through keys that do not read back as they were written, and the
            // page would issue the very token it was requested with, forever
            throw new IllegalStateException("The cursor cannot advance past its boundary row, whose ordering keys do not read back as they were written");
        }

        String fingerprint = fingerprint(resolvedSort);
        return new CursorResult<>(
                items,
                cursor.size(),
                nextKeys != null ? token(codec, nextKeys, false, fingerprint) : null,
                previousKeys != null ? token(codec, previousKeys, true, fingerprint) : null,
                hasNext,
                hasPrevious);
    }

    /**
     * Lazily walks every item a cursor query returns, fetching a page at a time instead of loading the whole
     * result set at once.
     * <p>
     * A page is only fetched once the items of the previous one were consumed, be it by a short-circuiting
     * operation such as {@link Stream#limit(long)} or through the {@link Stream#iterator() iterator}. Only the
     * fetching is lazy, not the retention: the entities walked stay managed until the transaction ends, and the
     * stream must be consumed within that very transaction.
     *
     * @param <T>      The type of the returned items
     * @param pages    The page fetcher, which is the cursor query being walked
     * @param sort     The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @param pageSize The number of items fetched per underlying page, capped to {@link Cursor#MAX_SIZE}
     * @return The lazy stream of every matching item, in the requested ordering
     */
    public static <T> Stream<T> stream(Function<Cursor, CursorResult<T>> pages, Sort sort, int pageSize) {
        // A spliterator of its own, a flat mapped iteration of the pages buffering every one of them as soon as the
        // stream is pulled through its iterator
        return StreamSupport.stream(new PageWalk<>(pages, sort, pageSize), false);
    }

    /**
     * Computes the fingerprint of an ordering, which binds a token to the ordering it was issued for.
     * <p>
     * A digest is used rather than a 32 bit hash code, two colliding orderings being trivially written, such as
     * {@code Aa} and {@code BB}. It guards against an accidental replay and is no integrity tag, anyone being able
     * to compute the fingerprint of an ordering: reject forged positions by signing them in a {@link CursorCodec}.
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
            // Every Java platform is required to provide SHA-256
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
