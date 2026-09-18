package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.Pageable.sortedBy;
import static com.chavaillaz.jakarta.persistence.repository.Pageable.unpaged;

import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;

/**
 * Contract of all the repositories, providing the common operations to read, search, persist and delete entities.
 * <p>
 * The operations require an already active transaction.
 *
 * @param <E> The type of the managed entity
 * @param <I> The type of the entity identifier
 * @see Pageable
 * @see Cursor
 */
public interface Repository<E extends Identifiable<I>, I> {

    /**
     * Gets all the existing entities of the current repository, with no pagination and the default ordering.
     *
     * @return The list of entities
     * @see Pageable#unpaged()
     */
    default List<E> findAll() {
        return findAll(unpaged()).items();
    }

    /**
     * Gets all the existing entities of the current repository, ordered by the default ordering of the repository.
     *
     * @param page The page number, starting at zero, or {@code null} to disable the pagination
     * @param size The number of items per page, or {@code null} to disable the pagination
     * @return The entities of the requested page with the total number of entities
     * @see Pageable#of(Integer, Integer)
     * @see #findAll(Pageable)
     */
    default PaginationResult<E> findAll(@Nullable Integer page, @Nullable Integer size) {
        return findAll(Pageable.of(page, size));
    }

    /**
     * Gets all the existing entities of the current repository, with no pagination.
     *
     * @param sort The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The list of entities
     * @see Pageable#sortedBy(Sort)
     */
    default List<E> findAll(Sort sort) {
        return findAll(sortedBy(sort)).items();
    }

    /**
     * Gets all the existing entities of the current repository.
     *
     * @param page The page number, starting at zero, or {@code null} to disable the pagination
     * @param size The number of items per page, or {@code null} to disable the pagination
     * @param sort The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The entities of the requested page with the total number of entities
     * @see Pageable#of(Integer, Integer, Sort)
     * @see #findAll(Pageable)
     */
    default PaginationResult<E> findAll(@Nullable Integer page, @Nullable Integer size, Sort sort) {
        return findAll(Pageable.of(page, size, sort));
    }

    /**
     * Gets all the existing entities of the current repository.
     *
     * @param pageable The requested page and ordering, {@link Pageable#UNPAGED} to return all the entities with
     *                 the default ordering of the repository
     * @return The entities of the requested page with the total number of entities
     * @throws IllegalArgumentException if the ordering refers to an unknown property or to a collection
     */
    PaginationResult<E> findAll(Pageable pageable);

    /**
     * Gets the entity from its identifier, same as {@link #findById(I)} but throwing instead of returning an
     * empty result when it does not exist.
     *
     * @param id The entity identifier
     * @return The corresponding entity, never {@code null}
     * @throws NoSuchElementException if the entity corresponding to the given identifier does not exist
     * @see #findById(I)
     */
    default E getById(I id) {
        return findById(id).orElseThrow(() -> new NoSuchElementException("No entity found with identifier %s in %s".formatted(id, getClass().getSimpleName())));
    }

    /**
     * Gets the entity from its identifier.
     *
     * @param id The entity identifier, {@code null} never matching any entity
     * @return The corresponding entity, or {@link Optional#empty()} if it does not exist
     */
    Optional<E> findById(@Nullable I id);

    /**
     * Gets the entity from its identifier, holding the requested lock on its row.
     * <p>
     * The row is locked as it is read, which is what a read-modify-write needs. The state is only guaranteed
     * fresh when the entity is not managed yet: otherwise, the provider locks the row but keeps the copy it
     * already holds, which {@link #lock(Identifiable, LockModeType)} refreshes.
     *
     * @param id       The entity identifier, {@code null} never matching any entity
     * @param lockMode The lock to hold on the row until the end of the transaction
     * @return The corresponding entity, or {@link Optional#empty()} if it does not exist
     */
    Optional<E> findById(@Nullable I id, LockModeType lockMode);

    /**
     * Gets the entity from its identifier, holding the requested lock on its row, and throwing instead of
     * returning an empty result when it does not exist.
     *
     * @param id       The entity identifier
     * @param lockMode The lock to hold on the row until the end of the transaction
     * @return The corresponding entity, never {@code null}
     * @throws NoSuchElementException if the entity corresponding to the given identifier does not exist
     * @see #findById(Object, LockModeType)
     */
    default E getById(I id, LockModeType lockMode) {
        return findById(id, lockMode).orElseThrow(() -> new NoSuchElementException("No entity found with identifier %s in %s".formatted(id, getClass().getSimpleName())));
    }

    /**
     * Checks whether an entity exists for the given identifier, without fetching its state.
     * <p>
     * An identifier class is the exception: it spreads the identifier over several attributes, which no single
     * predicate compares, so the entity is looked up as {@link #findById(Object)} does and its state is fetched.
     *
     * @param id The entity identifier, {@code null} never matching any entity
     * @return {@code true} if an entity exists for the given identifier, {@code false} otherwise
     */
    boolean existsById(@Nullable I id);

    /**
     * Gets the entities matching the given identifiers, silently skipping the ones that do not exist.
     *
     * @param ids The entity identifiers to look up
     * @return The matching entities, in no particular order, at most one per given identifier
     */
    List<E> findAllById(Collection<I> ids);

    /**
     * Counts all the entities of the current repository.
     *
     * @return The total number of entities
     */
    long count();

    /**
     * Scrolls through all the existing entities of the current repository, seeking to the requested position
     * instead of skipping the preceding rows.
     * <p>
     * Prefer this over {@link #findAll(Pageable)} for the endpoints walking a large or a frequently updated
     * collection, see {@link Cursor} for the trade-off between the two.
     *
     * @param cursor The requested position, size and ordering
     * @return The corresponding page with the tokens of the surrounding ones
     * @throws IllegalArgumentException if the ordering refers to an unknown property, to a collection, or if the
     *                                  cursor is malformed or was issued for another ordering
     */
    CursorResult<E> findAll(Cursor cursor);

    /**
     * Scrolls through all the existing entities of the current repository, seeking to the requested position
     * instead of skipping the preceding rows.
     *
     * @param cursor The opaque position of the previous page, {@code null} or blank to request the first page
     * @param size   The number of items per page, or {@code null} to apply {@link Cursor#DEFAULT_SIZE}
     * @param sort   The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding page with the tokens of the surrounding ones
     * @see Cursor#of(String, Integer, Sort)
     * @see #findAll(Cursor)
     */
    default CursorResult<E> findAll(@Nullable String cursor, @Nullable Integer size, Sort sort) {
        return findAll(Cursor.of(cursor, size, sort));
    }

    /**
     * Lazily walks all the existing entities of the current repository, fetching a page at a time through
     * {@link #findAll(Cursor)} instead of loading the whole result set at once.
     * <p>
     * A short-circuiting operation such as {@link Stream#limit(long)} only fetches the pages it needs. The stream
     * keeps querying as it is pulled, like {@link jakarta.persistence.Query#getResultStream()}, so it must be
     * consumed within the transaction it was obtained from: collect it beforehand to return it from a
     * transactional method.
     * <p>
     * Only the fetching is lazy, not the retention: every entity walked stays managed until the transaction ends.
     * Clear the persistence context periodically, or walk the table with a stateless session, when the rows must
     * not be held in memory.
     *
     * @param sort     The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @param pageSize The number of items fetched per underlying page, capped to {@link Cursor#MAX_SIZE}
     * @return The lazy stream of every matching entity, in the requested ordering
     * @throws IllegalArgumentException if the ordering is not usable as a cursor key, raised when the stream is
     *                                  first consumed, nothing being queried until then
     * @see #findAll(Cursor)
     */
    default Stream<E> streamAll(Sort sort, int pageSize) {
        return Cursors.stream(this::findAll, sort, pageSize);
    }

    /**
     * Lazily walks all the existing entities of the current repository, applying {@link Cursor#DEFAULT_SIZE}.
     *
     * @param sort The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The lazy stream of every matching entity, in the requested ordering
     * @see #streamAll(Sort, int)
     */
    default Stream<E> streamAll(Sort sort) {
        return streamAll(sort, Cursor.DEFAULT_SIZE);
    }

    /**
     * Lazily walks all the existing entities of the current repository, following its default ordering and
     * applying {@link Cursor#DEFAULT_SIZE}.
     *
     * @return The lazy stream of every matching entity
     * @see #streamAll(Sort, int)
     */
    default Stream<E> streamAll() {
        return streamAll(Sort.NONE, Cursor.DEFAULT_SIZE);
    }

    /**
     * Configures a pessimistic lock on an entity, its state being first refreshed from the database
     * so that any concurrent change is taken into account. Any local change is therefore discarded.
     * <p>
     * A detached entity is re-attached beforehand, the lock then applying to the managed copy.
     *
     * @param entity The entity to lock
     * @throws IllegalArgumentException if the entity is transient, having no identifier yet
     * @throws NoSuchElementException   if the entity is detached and no entity with its identifier exists
     */
    default void lock(E entity) {
        lock(entity, LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * Configures the requested lock on an entity, its state being first refreshed from the database so that any
     * concurrent change is taken into account. Any local change is therefore discarded.
     * <p>
     * A detached entity is re-attached beforehand, the lock then applying to the managed copy. Unlike
     * {@link #findById(Object, LockModeType)}, the state is always refreshed, so a managed entity cannot be locked
     * on a stale copy.
     *
     * @param entity   The entity to lock
     * @param lockMode The lock to hold on the row until the end of the transaction
     * @throws IllegalArgumentException if the entity is transient, having no identifier yet
     * @throws NoSuchElementException   if the entity is detached and no entity with its identifier exists
     */
    void lock(E entity, LockModeType lockMode);

    /**
     * Refreshes the state of an entity from the database, overwriting any local changes.
     * <p>
     * Unlike {@link #lock(Identifiable)}, a detached entity is not re-attached beforehand.
     *
     * @param entity The entity to refresh, which must be managed
     * @throws IllegalArgumentException if the entity is not managed by the current persistence context
     */
    void refresh(E entity);

    /**
     * Gets the reference to an entity of the current repository, whose state is lazily fetched.
     *
     * @param id The entity identifier
     * @return The reference to the entity, or {@code null} if the given identifier is {@code null}
     * @see #getReference(Class, Object)
     */
    @Nullable E getReference(@Nullable I id);

    /**
     * Gets the reference to an entity, whose state is lazily fetched.
     *
     * @param <T>  The type of the entity
     * @param <K>  The type of the entity identifier
     * @param type The entity type
     * @param id   The entity identifier
     * @return The reference to the entity, or {@code null} if the given identifier is {@code null}
     */
    <T extends Identifiable<K>, K> @Nullable T getReference(Class<T> type, @Nullable K id);

    /**
     * Saves the given entity, persisting it when it has no identifier yet, merging it otherwise.
     *
     * @param entity The entity to save
     * @return The saved entity
     */
    E save(E entity);

    /**
     * Saves the given entities, persisting the ones with no identifier yet, merging the others.
     *
     * @param entities The entities to save
     * @return The saved entities, in the same order
     * @see #save(Identifiable)
     */
    default List<E> saveAll(Collection<E> entities) {
        return entities.stream().map(this::save).toList();
    }

    /**
     * Saves the given entities, flushing the persistence context and detaching the saved entities at the default
     * batch size of the implementation, {@link AbstractRepository#DEFAULT_SAVE_BATCH_SIZE}.
     *
     * @param entities The entities to save
     * @return The number of saved entities
     * @see #saveAllInBatches(Collection, int)
     */
    int saveAllInBatches(Collection<E> entities);

    /**
     * Saves the given entities, flushing the persistence context and detaching the saved entities every
     * {@code batchSize} entities, for the bulk loads a plain {@link #saveAll(Collection)} cannot hold in memory.
     * <p>
     * Only the number of saved entities is returned, and only their generated identifiers are guaranteed on the
     * given entities: returning the copies a merge makes is what holding a whole batch of them would defeat. See
     * {@link AbstractRepository#saveAllInBatches(Collection, int)} for what leaves the persistence context and
     * what the batch size buys.
     *
     * @param entities  The entities to save
     * @param batchSize The number of entities saved between two flushes, which must be strictly positive
     * @return The number of saved entities
     * @throws IllegalArgumentException if {@code batchSize} is not strictly positive
     */
    int saveAllInBatches(Collection<E> entities, int batchSize);

    /**
     * Deletes the entity with the given identifier, doing nothing when it does not exist.
     *
     * @param id The entity identifier
     */
    default void deleteById(I id) {
        findById(id).ifPresent(this::delete);
    }

    /**
     * Deletes the given entity.
     * <p>
     * A detached entity is re-attached with a fresh lookup rather than a merge, so that its local changes are
     * never written, and its version is checked as a merge would check it.
     *
     * @param entity The entity to delete
     * @throws IllegalArgumentException if the entity is transient, having no identifier yet
     * @throws NoSuchElementException   if the entity is detached and no entity with its identifier exists
     * @throws OptimisticLockException  if the entity is versioned and was modified since its detached copy was read
     */
    void delete(E entity);

    /**
     * Deletes the given entities.
     *
     * @param entities The entities to delete
     * @see #delete(Identifiable)
     */
    default void deleteAll(Collection<E> entities) {
        entities.forEach(this::delete);
    }

    /**
     * Deletes the entities with the given identifiers, silently skipping the ones that do not exist.
     * <p>
     * The entities are loaded first, so that the deletion cascades and runs the callbacks as
     * {@link #delete(Identifiable)} does, which a bulk deletion built on {@code deleteAll(Restriction)} does not.
     *
     * @param ids The identifiers of the entities to delete
     * @see #findAllById(Collection)
     */
    default void deleteAllById(Collection<I> ids) {
        deleteAll(findAllById(ids));
    }

}
