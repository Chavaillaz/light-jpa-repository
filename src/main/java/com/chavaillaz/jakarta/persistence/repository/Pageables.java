package com.chavaillaz.jakarta.persistence.repository;

import java.util.List;
import java.util.function.LongSupplier;

import jakarta.persistence.TypedQuery;
import org.hibernate.query.Page;

/**
 * Pagination helpers shared by the query collaborators of the repositories.
 * <p>
 * The {@link Pageable} itself stays free of any persistence dependency, this class holding the translation
 * towards the JPA and Hibernate constructs.
 */
public final class Pageables {

    private Pageables() {
        // This utility class should not be instantiated
    }

    /**
     * Converts the requested page into its Hibernate counterpart.
     *
     * @param pageable The requested page, which must be {@link Pageable#isPaginated() paginated}
     * @return The corresponding page
     */
    public static Page toPage(Pageable pageable) {
        // The Hibernate factory takes the size first, then the page number
        return Page.page(pageable.size(), pageable.page());
    }

    /**
     * Applies the pagination to the given query, doing nothing when the pagination is not requested or invalid.
     *
     * @param query    The query to paginate
     * @param pageable The requested page
     */
    public static void apply(TypedQuery<?> query, Pageable pageable) {
        if (pageable.isPaginated()) {
            query.setFirstResult(pageable.page() * pageable.size());
            query.setMaxResults(pageable.size());
        }
    }

    /**
     * Builds the pagination result, the total number of items being only computed when the results are actually
     * paginated.
     *
     * @param <T>        The type of the returned items
     * @param items      The items of the current page
     * @param pageable   The requested page
     * @param totalItems The supplier of the total number of items matching the query, without pagination
     * @return The corresponding pagination result
     */
    public static <T> PaginationResult<T> toResult(List<T> items, Pageable pageable, LongSupplier totalItems) {
        if (pageable.isPaginated()) {
            return PaginationResult.of(items, pageable.page(), pageable.size(), totalItems.getAsLong());
        }
        return PaginationResult.single(items);
    }

}
