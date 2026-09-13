package com.chavaillaz.jakarta.persistence.repository;

import java.util.Collections;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Walk of the pages of a cursor query, fetching a page only once every item of the previous one was handed
 * over, whether the stream is consumed by a terminal operation or pulled through its iterator.
 *
 * @param <T> The type of the returned items
 * @see Cursors#stream(Function, Sort, int)
 */
final class PageWalk<T> extends Spliterators.AbstractSpliterator<T> {

    private final Function<Cursor, CursorResult<T>> pages;
    private final Sort sort;
    private final int pageSize;

    /**
     * The request of the page to fetch once the items at hand are exhausted, {@code null} when the walk is
     * over. Nothing is fetched on construction, so that a stream nobody consumes issues no query at all.
     */
    private @Nullable Cursor following;

    private Iterator<T> items = Collections.emptyIterator();

    PageWalk(Function<Cursor, CursorResult<T>> pages, Sort sort, int pageSize) {
        super(Long.MAX_VALUE, ORDERED | NONNULL);
        this.pages = pages;
        this.sort = sort;
        this.pageSize = pageSize;
        this.following = Cursor.first(pageSize, sort);
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
        while (!items.hasNext()) {
            if (following == null) {
                return false;
            }
            CursorResult<T> page = pages.apply(following);
            items = page.items().iterator();
            // The token is what makes the walk progress: a page announcing a successor without handing one
            // over would otherwise be requested as a first page again, and the walk would never terminate
            following = page.hasNext() && page.next() != null ? Cursor.of(page.next(), pageSize, sort) : null;
        }
        action.accept(items.next());
        return true;
    }

    @Override
    public @Nullable Spliterator<T> trySplit() {
        // The pages are fetched through an entity manager, which is neither thread safe nor usable outside the
        // thread of its transaction: a split would have another thread pull the following items, and query
        return null;
    }

}
