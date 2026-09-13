package com.chavaillaz.jakarta.persistence.repository;

import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.function.LongSupplier;

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
     * Checks whether the offset of the requested page overflows the {@code int} the JPA providers and the JDBC
     * drivers take as a first result, which a page number sent by an API consumer is free to make it do. Such a
     * page holds no item anyway.
     *
     * @param pageable The requested page, which must be {@link Pageable#isPaginated() paginated}
     * @return {@code true} if the offset of the page overflows, {@code false} otherwise
     */
    public static boolean overflows(Pageable pageable) {
        return (long) pageable.page() * pageable.size() > Integer.MAX_VALUE;
    }

    /**
     * Applies the pagination to the given query, leaving it unpaginated when no valid page is requested, and
     * fetching nothing for a page beyond the largest offset a query can express.
     *
     * @param query    The query to paginate
     * @param pageable The requested page
     * @see #overflows(Pageable)
     */
    public static void apply(TypedQuery<?> query, Pageable pageable) {
        if (!pageable.isPaginated()) {
            return;
        }
        if (overflows(pageable)) {
            // Left unpaginated, the query would return the whole table instead of an empty page
            query.setMaxResults(0);
            return;
        }
        query.setFirstResult(pageable.page() * pageable.size());
        query.setMaxResults(pageable.size());
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
