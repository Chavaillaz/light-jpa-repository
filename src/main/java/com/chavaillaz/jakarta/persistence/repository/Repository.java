package com.chavaillaz.jakarta.persistence.repository;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import com.chavaillaz.jakarta.persistence.Identifiable;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

/**
 * Repository to manage entities.
 *
 * @param <E> The entity type
 * @param <I> The entity identifier type
 */
@Transactional
public interface Repository<E extends Identifiable<I>, I> {

    /**
     * Gets all the existing entities of the current repository.
     *
     * @return The list of entities
     */
    List<E> findAll();

    /**
     * Gets the entity from its identifier.
     *
     * @param id The entity identifier
     * @return The corresponding entity
     * @throws NoSuchElementException if the entity corresponding to the given identifier does not exist
     */
    default E getById(I id) {
        return findById(id).orElseThrow();
    }

    /**
     * Gets the entity from its identifier.
     *
     * @param id The entity identifier
     * @return The corresponding entity
     */
    default Optional<E> findById(I id) {
        return findById(id, false);
    }

    /**
     * Gets the entity from its identifier.
     *
     * @param id   The entity identifier
     * @param lock The indicator if a pessimistic lock write has to be acquired
     * @return The corresponding entity
     */
    Optional<E> findById(I id, boolean lock);

    /**
     * Configures a pessimistic lock on an entity.
     *
     * @param entity The entity
     */
    default void lock(E entity) {
        lock(entity, PESSIMISTIC_WRITE);
    }

    /**
     * Configures a lock on an entity.
     *
     * @param entity The entity
     * @param lockMode The lock mode
     */
    void lock(E entity, LockModeType lockMode);

    /**
     * Gets the reference to an entity of the current repository, whose state is lazily fetched.
     *
     * @param id The entity identifier
     * @return The reference to the entity
     */
    E getReference(I id);

    /**
     * Gets the reference to an entity, whose state is lazily fetched.
     *
     * @param type The entity class
     * @param id   The entity identifier
     * @param <T>  The entity type
     * @return The reference to the entity
     */
    <T> T getReference(Class<T> type, Object id);

    /**
     * Saves the given entity.
     *
     * @param entity The entity to save
     * @return The saved entity
     */
    E save(E entity);

    /**
     * Deletes the entity with the given identifier.
     *
     * @param id The entity identifier
     */
    default void delete(I id) {
        findById(id).ifPresent(this::delete);
    }

    /**
     * Deletes the given entity.
     *
     * @param entity The entity to delete
     */
    void delete(E entity);

}