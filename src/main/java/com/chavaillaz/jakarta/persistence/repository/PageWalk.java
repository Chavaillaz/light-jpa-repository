package com.chavaillaz.jakarta.persistence.repository;

import java.util.Collections;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Walk of the pages of a cursor query, fetching a page only once every item of the previous one was handed over.
 *
 * @param <T> The type of the returned items
 * @see Cursors#stream(Function, Sort, int)
 */
final class PageWalk<T> extends Spliterators.AbstractSpliterator<T> {

    private final Function<Cursor, CursorResult<T>> pages;
    private final Sort sort;
    private final int pageSize;

    /**
     * The request of the page to fetch once the items at hand are exhausted, {@code null} when the walk is over.
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
            // A page announcing a successor without its token would otherwise restart the walk from the first page
            following = page.hasNext() && page.next() != null ? Cursor.of(page.next(), pageSize, sort) : null;
        }
        action.accept(items.next());
        return true;
    }

    @Override
    public @Nullable Spliterator<T> trySplit() {
        // The pages are fetched through an entity manager, which is bound to the thread of its transaction
        return null;
    }

}
