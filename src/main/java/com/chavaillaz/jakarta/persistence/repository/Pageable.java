package com.chavaillaz.jakarta.persistence.repository;

import org.jspecify.annotations.Nullable;

/**
 * Slice of results requested for a query, made of the page coordinates and of the ordering to apply.
 * <p>
 * The ordering is carried along, because paginating without a deterministic total order returns unstable pages:
 * the database is free to return the rows in an arbitrary order, which may differ from one page to the next and
 * therefore duplicate or skip items. A {@link Sort} stays usable on its own though, for the queries returning all
 * the results, which is why both types remain distinct, this one only composing the other.
 * <p>
 * The pagination is disabled when the page or the size is {@code null}, {@link #UNPAGED} expressing it explicitly.
 *
 * @param page The page number, starting at zero, or {@code null} to disable the pagination
 * @param size The number of items per page, or {@code null} to disable the pagination
 * @param sort The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
 */
public record Pageable(
        @Nullable Integer page,
        @Nullable Integer size,
        Sort sort) {

    /**
     * Unpaged request, returning all the results with the default ordering of the repository.
     */
    public static final Pageable UNPAGED = new Pageable(null, null, Sort.NONE);

    /**
     * Defaults the ordering to {@link Sort#NONE}, so that every other collaborator can assume it is always set.
     */
    public Pageable {
        sort = sort == null ? Sort.NONE : sort;
    }

    /**
     * Creates a request for the given page, ordered by the default ordering of the repository.
     *
     * @param page The page number, starting at zero, or {@code null} to disable the pagination
     * @param size The number of items per page, or {@code null} to disable the pagination
     * @return The corresponding request
     */
    public static Pageable of(@Nullable Integer page, @Nullable Integer size) {
        return of(page, size, Sort.NONE);
    }

    /**
     * Creates a request for the given page, with the given ordering.
     *
     * @param page The page number, starting at zero, or {@code null} to disable the pagination
     * @param size The number of items per page, or {@code null} to disable the pagination
     * @param sort The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding request
     */
    public static Pageable of(@Nullable Integer page, @Nullable Integer size, Sort sort) {
        return new Pageable(page, size, sort);
    }

    /**
     * Creates an unpaged request with the given ordering, for the queries returning all the results.
     *
     * @param sort The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding request
     */
    public static Pageable sortedBy(Sort sort) {
        return new Pageable(null, null, sort);
    }

    /**
     * Creates an unpaged request, returning all the results with the default ordering of the repository.
     *
     * @return The corresponding request
     */
    public static Pageable unpaged() {
        return UNPAGED;
    }

    /**
     * Checks if the pagination parameters are set and valid, the results being returned as a whole otherwise.
     *
     * @return {@code true} if the results have to be paginated, {@code false} otherwise
     */
    public boolean isPaginated() {
        return page != null && page >= 0 && size != null && size > 0;
    }

    /**
     * Derives a request applying the given ordering, the page coordinates being kept.
     *
     * @param sort The ordering to apply
     * @return The corresponding request
     */
    public Pageable withSort(Sort sort) {
        return new Pageable(page, size, sort);
    }

    /**
     * Derives a request falling back to the given page coordinates when they are not set, for the endpoints
     * paginating by default.
     *
     * @param defaultPage The page number to apply when none is requested
     * @param defaultSize The number of items per page to apply when none is requested
     * @return The corresponding request
     */
    public Pageable orDefault(int defaultPage, int defaultSize) {
        return new Pageable(page == null ? defaultPage : page, size == null ? defaultSize : size, sort);
    }

}

