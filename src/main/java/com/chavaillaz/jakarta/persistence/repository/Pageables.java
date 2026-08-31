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
     * Checks whether the offset of the requested page does not fit in an {@code int}, which is what the JPA
     * providers and the JDBC drivers take as a first result.
     * <p>
     * The page number comes straight from the API consumers, so a page far beyond the end must not surface as a
     * server error: such a page holds no item anyway and is returned empty, its total being still computed.
     *
     * @param pageable The requested page, which must be {@link Pageable#isPaginated() paginated}
     * @return {@code true} if the offset of the page overflows, {@code false} otherwise
     */
    public static boolean overflows(Pageable pageable) {
        return (long) pageable.page() * pageable.size() > Integer.MAX_VALUE;
    }

    /**
     * Applies the pagination to the given query, doing nothing when the pagination is not requested, invalid or
     * beyond the largest offset a query can express.
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
            // Such a page is empty, which is expressed by fetching nothing rather than by leaving the query
            // unpaginated, which would return the whole table instead
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
