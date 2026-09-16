package com.chavaillaz.jakarta.persistence.repository;

import org.jspecify.annotations.Nullable;

/**
 * Slice of results requested for a query, made of the page coordinates and of the ordering to apply.
 * <p>
 * The ordering is carried along, because paginating without a deterministic total order returns unstable pages,
 * a {@link Sort} staying usable on its own for the queries returning all the results.
 * <p>
 * A coordinate that cannot address a page — a missing one, a negative page number or a non-positive size — is
 * normalized to {@value #NO_PAGINATION}, so that {@link #isPaginated()} is the single place reading them and an
 * invalid coordinate never has to be told apart from an absent one. The {@link #of(Integer, Integer, Sort)}
 * factories accept the {@code null} an absent query parameter is deserialized as.
 *
 * @param page The page number, starting at zero, {@value #NO_PAGINATION} when the pagination is disabled
 * @param size The number of items per page, {@value #NO_PAGINATION} when the pagination is disabled
 * @param sort The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
 */
public record Pageable(
        int page,
        int size,
        Sort sort) {

    /**
     * Value both coordinates take when the pagination is disabled, whether it was never requested or requested
     * with coordinates that cannot address a page.
     */
    public static final int NO_PAGINATION = -1;

    /**
     * Unpaged request, returning all the results with the default ordering of the repository.
     */
    public static final Pageable UNPAGED = new Pageable(NO_PAGINATION, NO_PAGINATION, Sort.NONE);

    /**
     * Maximum number of items a consumer may request per page, so that a single call cannot drain the table.
     */
    public static final int MAX_SIZE = 1_000;

    /**
     * Defaults the ordering to {@link Sort#NONE}, caps the requested size to {@value #MAX_SIZE}, and normalizes the
     * coordinates that cannot address a page to {@value #NO_PAGINATION}.
     */
    public Pageable {
        sort = sort == null ? Sort.NONE : sort;
        // Normalized one by one, so that orDefault can complete the coordinate a consumer left out
        page = page < 0 ? NO_PAGINATION : page;
        size = size < 1 ? NO_PAGINATION : Math.min(size, MAX_SIZE);
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
        return new Pageable(
                page == null ? NO_PAGINATION : page,
                size == null ? NO_PAGINATION : size,
                sort);
    }

    /**
     * Creates an unpaged request with the given ordering, for the queries returning all the results.
     *
     * @param sort The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding request
     */
    public static Pageable sortedBy(Sort sort) {
        return new Pageable(NO_PAGINATION, NO_PAGINATION, sort);
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
        return page != NO_PAGINATION && size != NO_PAGINATION;
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
     * <p>
     * A coordinate that cannot address a page, such as a negative page number, is replaced as a missing one is,
     * so that such an endpoint never returns the whole table.
     *
     * @param defaultPage The page number to apply when none is requested, which must be positive or zero
     * @param defaultSize The number of items per page to apply when none is requested, which must be strictly
     *                    positive
     * @return The corresponding request
     * @throws IllegalArgumentException if a default coordinate cannot address a page either
     */
    public Pageable orDefault(int defaultPage, int defaultSize) {
        if (defaultPage < 0 || defaultSize < 1) {
            // Normalized as any other coordinate, such a default would disable the pagination it is meant to enforce
            throw new IllegalArgumentException("The default coordinates must address a page, got page %d of size %d".formatted(defaultPage, defaultSize));
        }
        return new Pageable(
                page == NO_PAGINATION ? defaultPage : page,
                size == NO_PAGINATION ? defaultSize : size,
                sort);
    }

}
