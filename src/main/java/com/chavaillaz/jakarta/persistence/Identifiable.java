package com.chavaillaz.jakarta.persistence;

/**
 * Interface to identify an entity.
 *
 * @param <I> The type of the entity identifier
 */
public interface Identifiable<I> {

    /**
     * Gets the entity identifier.
     *
     * @return The entity identifier
     */
    I getId();

}
