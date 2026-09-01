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
 * The coordinates are plain numbers rather than nullable boxes. A coordinate that cannot address a page — a
 * missing one, a negative page number or a non-positive size — is normalized to {@value #NO_PAGINATION}, which
 * is the single value the whole library reads as "not requested": an absent coordinate and an invalid one are
 * the same request, and neither has to be told apart nor unboxed to be used. The pagination is only applied when
 * both coordinates address a page, which {@link #isPaginated()} answers; {@link #UNPAGED} expresses its absence
 * explicitly, and the {@link #of(Integer, Integer, Sort)} factories accept the {@code null} an absent query
 * parameter is deserialized as.
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
     * Defaults the ordering to {@link Sort#NONE}, so that every other collaborator can assume it is always set,
     * caps the requested size to {@value #MAX_SIZE}, and normalizes coordinates that cannot address a page to
     * {@value #NO_PAGINATION}, so that {@link #isPaginated()} is the single place reading them.
     */
    public Pageable {
        sort = sort == null ? Sort.NONE : sort;
        // Each coordinate is normalized on its own, so that a page requested without a size, or the other way
        // round, still carries the one the consumer did send for orDefault to complete
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
     * Coordinates that cannot address a page, such as a negative page number, are replaced as a missing one is:
     * an endpoint paginating by default means to paginate, and returning the whole table because a consumer sent
     * {@code page=-1} is not what it asked for.
     *
     * @param defaultPage The page number to apply when none is requested
     * @param defaultSize The number of items per page to apply when none is requested
     * @return The corresponding request
     */
    public Pageable orDefault(int defaultPage, int defaultSize) {
        return new Pageable(
                page == NO_PAGINATION ? defaultPage : page,
                size == NO_PAGINATION ? defaultSize : size,
                sort);
    }

}
