package com.chavaillaz.jakarta.persistence.repository;

import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Page of results returned by a cursor query, carrying the tokens to navigate to the surrounding pages.
 * <p>
 * No total number of items is exposed: computing it would require the very aggregation cursor pagination is
 * meant to avoid. When a total is genuinely needed, it stays available through {@code count(rsql)}.
 *
 * @param <T>         The type of the returned items
 * @param items       The items of the current page
 * @param size        The requested page size
 * @param next        The token of the following page, or {@code null} when none exists
 * @param previous    The token of the preceding page, or {@code null} when none exists
 * @param hasNext     {@code true} if a following page exists
 * @param hasPrevious {@code true} if a preceding page exists
 */
public record CursorResult<T>(
        List<T> items,
        int size,
        @Nullable String next,
        @Nullable String previous,
        boolean hasNext,
        boolean hasPrevious) {

    /**
     * Defaults a {@code null} list of items to an empty one, and defends the returned page against a later
     * modification of the list it was built from.
     */
    public CursorResult {
        items = (items == null) ? List.of() : List.copyOf(items);
    }

    /**
     * Builds an empty page for the requested size, no navigation being possible.
     *
     * @param <T>  The type of the returned items
     * @param size The requested page size
     * @return The corresponding empty page
     */
    public static <T> CursorResult<T> empty(int size) {
        return new CursorResult<>(List.of(), size, null, null, false, false);
    }

    /**
     * Converts the items of this page, the tokens and the navigation flags being kept.
     *
     * @param <E>    The type of the converted items
     * @param mapper The conversion to apply to each item
     * @return The corresponding page
     */
    public <E> CursorResult<E> map(Function<? super T, ? extends E> mapper) {
        return new CursorResult<>(items.stream().<E>map(mapper).toList(), size, next, previous, hasNext, hasPrevious);
    }

}