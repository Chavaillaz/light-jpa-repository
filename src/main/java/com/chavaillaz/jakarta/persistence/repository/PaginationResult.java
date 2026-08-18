package com.chavaillaz.jakarta.persistence.repository;

import static java.lang.Math.ceil;
import static java.lang.Math.max;

import java.util.List;
import java.util.function.Function;

/**
 * Page of results returned by an offset based query, carrying the total number of matching items and the
 * coordinates needed to navigate to the surrounding pages.
 *
 * @param <T>         The type of the returned items
 * @param items       The items of the current page
 * @param currentPage The page number, starting at zero
 * @param pageSize    The number of items per page
 * @param totalPages  The total number of pages
 * @param totalItems  The total number of matching items, without pagination
 * @param hasNext     {@code true} if a following page exists
 * @param hasPrevious {@code true} if a preceding page exists
 */
public record PaginationResult<T>(
        List<T> items,
        int currentPage,
        int pageSize,
        int totalPages,
        long totalItems,
        boolean hasNext,
        boolean hasPrevious) {

    /**
     * Defaults a {@code null} list of items to an empty one, and defends the returned page against a later
     * modification of the list it was built from.
     */
    public PaginationResult {
        items = (items == null) ? List.of() : List.copyOf(items);
    }

    /**
     * Builds a page from its items and coordinates, deriving the total number of pages and the navigation flags.
     *
     * @param <T>         The type of the returned items
     * @param items       The items of the current page
     * @param currentPage The page number, starting at zero
     * @param pageSize    The number of items per page
     * @param totalItems  The total number of matching items, without pagination
     * @return The corresponding page
     */
    public static <T> PaginationResult<T> of(List<T> items, int currentPage, int pageSize, long totalItems) {
        int totalPages = (pageSize <= 0) ? 0 : (int) ceil((double) totalItems / pageSize);
        return new PaginationResult<>(
                items,
                currentPage,
                pageSize,
                totalPages,
                totalItems,
                currentPage + 1 < totalPages,
                currentPage > 0);
    }

    /**
     * Builds the single page holding all the given items, for the queries returning their results as a whole.
     *
     * @param <T>   The type of the returned items
     * @param items All the matching items
     * @return The corresponding page
     */
    public static <T> PaginationResult<T> single(List<T> items) {
        return of(items, 0, max(items.size(), 1), items.size());
    }

    /**
     * Builds an empty page at the given coordinates.
     *
     * @param <T>         The type of the returned items
     * @param currentPage The page number, starting at zero
     * @param pageSize    The number of items per page
     * @return The corresponding empty page
     */
    public static <T> PaginationResult<T> empty(int currentPage, int pageSize) {
        return of(List.of(), currentPage, pageSize, 0);
    }

    /**
     * Converts the items of this page, the coordinates being kept.
     *
     * @param <E>    The type of the converted items
     * @param mapper The conversion to apply to each item
     * @return The corresponding page
     */
    public <E> PaginationResult<E> map(Function<? super T, ? extends E> mapper) {
        return new PaginationResult<>(
                items.stream().<E>map(mapper).toList(),
                currentPage, pageSize, totalPages, totalItems, hasNext, hasPrevious);
    }

}