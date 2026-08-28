package com.chavaillaz.jakarta.persistence;

import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;
import org.jspecify.annotations.Nullable;

/**
 * Interface to identify an entity.
 *
 * @param <I> The type of the entity identifier
 */
public interface Identifiable<I> {

    /**
     * Gets the entity identifier.
     *
     * @return The entity identifier, or {@code null} for a transient entity not yet persisted, which is exactly
     * what {@link AbstractRepository#save} tests to decide whether to persist or merge it
     */
    @Nullable I getId();

}
