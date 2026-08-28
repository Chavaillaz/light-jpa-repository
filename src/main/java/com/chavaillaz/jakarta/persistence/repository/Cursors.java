package com.chavaillaz.jakarta.persistence.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Cursor helpers shared by the query collaborators of the repositories.
 * <p>
 * The {@link Cursor} itself stays free of any persistence dependency, this class holding the decoding of the
 * requested position, the direction of the walk and the assembly of the resulting page.
 */
public final class Cursors {

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
     * @return The corresponding page, with the tokens of the surrounding ones
     */
    public static <T> CursorResult<T> toResult(CursorCodec codec, List<T> fetched, Cursor cursor, Sort resolvedSort, @Nullable CursorPosition position) {
        boolean backward = isBackward(position);
        boolean hasMore = fetched.size() > cursor.size();

        List<T> items = new ArrayList<>(hasMore ? fetched.subList(0, cursor.size()) : fetched);
        if (backward) {
            Collections.reverse(items);
        }
        if (items.isEmpty()) {
            return CursorResult.empty(cursor.size());
        }

        // Walking backwards, a following page necessarily exists, since it is the one we come from
        boolean hasNext = backward || hasMore;
        boolean hasPrevious = backward ? hasMore : position != null;
        String fingerprint = fingerprint(resolvedSort);

        return new CursorResult<>(
                items,
                cursor.size(),
                hasNext ? token(codec, items.getLast(), resolvedSort, false, fingerprint) : null,
                hasPrevious ? token(codec, items.getFirst(), resolvedSort, true, fingerprint) : null,
                hasNext,
                hasPrevious);
    }

    /**
     * Computes the fingerprint of an ordering, which binds a token to the ordering it was issued for.
     *
     * @param sort The ordering to fingerprint
     * @return The corresponding fingerprint
     */
    public static String fingerprint(Sort sort) {
        return Integer.toHexString(sort.toString().hashCode());
    }

    private static <T> String token(CursorCodec codec, T entity, Sort sort, boolean backward, String fingerprint) {
        return codec.encode(new CursorPosition(Keysets.valuesOf(entity, sort), backward, fingerprint));
    }

}