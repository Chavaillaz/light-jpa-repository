package com.chavaillaz.jakarta.persistence.repository;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Slice of results requested with a cursor, made of the opaque position of the previous page, of the number of
 * items to return and of the ordering to apply.
 * <p>
 * Unlike a {@link Pageable}, the position is not an offset but the ordering keys of the last returned row, so
 * that the database seeks directly to it through the index instead of walking and discarding the preceding rows.
 * The pages therefore stay stable when items are inserted or deleted in between, and no total count is computed.
 * <p>
 * The ordering is carried along and is part of the token: a cursor issued for an ordering cannot be replayed on
 * another one, as the seek predicate would then be meaningless.
 *
 * @param token The opaque position of the previous page, {@code null} or blank to request the first page
 * @param size  The number of items per page, capped to {@value #MAX_SIZE}
 * @param sort  The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
 */
public record Cursor(String token, int size, Sort sort) {

    /**
     * Number of items returned when the consumer requests none.
     */
    public static final int DEFAULT_SIZE = 50;

    /**
     * Maximum number of items a consumer may request, so that a single call cannot drain the table.
     */
    public static final int MAX_SIZE = 1_000;

    /**
     * Normalizes the requested size to {@code [1, MAX_SIZE]}, defaulting to {@value #DEFAULT_SIZE}, and defaults
     * the ordering to {@link Sort#NONE}, so that every other collaborator can assume both are always set.
     */
    public Cursor {
        size = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        sort = sort == null ? Sort.NONE : sort;
    }

    /**
     * Creates a request from the raw query parameters, applying the default size when none is requested.
     *
     * @param token The opaque position of the previous page, {@code null} or blank to request the first page
     * @param size  The number of items per page, or {@code null} to apply {@value #DEFAULT_SIZE}
     * @param sort  The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding request
     */
    public static Cursor of(String token, Integer size, Sort sort) {
        return new Cursor(token, size == null ? DEFAULT_SIZE : size, sort);
    }

    /**
     * Creates a request for the first page.
     *
     * @param size The number of items per page, or {@code null} to apply {@value #DEFAULT_SIZE}
     * @param sort The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding request
     */
    public static Cursor first(Integer size, Sort sort) {
        return of(null, size, sort);
    }

    /**
     * Checks if the first page is requested, no position being given.
     *
     * @return {@code true} when no token is provided, {@code false} otherwise
     */
    public boolean isFirst() {
        return isBlank(token);
    }

    /**
     * Gets the number of rows to fetch, which is one more than the page size, the extra row being what tells
     * whether another page exists without having to count the matching entities.
     *
     * @return The maximum number of rows to fetch
     */
    public int limit() {
        return size + 1;
    }

    /**
     * Derives a request applying the given ordering, the position being dropped as it no longer applies.
     *
     * @param sort The ordering to apply
     * @return The corresponding request
     */
    public Cursor withSort(Sort sort) {
        return new Cursor(null, size, sort);
    }

}