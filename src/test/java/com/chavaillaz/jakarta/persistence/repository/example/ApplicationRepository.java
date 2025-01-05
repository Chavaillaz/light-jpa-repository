package com.chavaillaz.jakarta.persistence.repository.example;

import java.util.Optional;

import com.chavaillaz.jakarta.persistence.repository.Repository;

/**
 * Repository to manage the application entities.
 */
public interface ApplicationRepository extends Repository<ApplicationEntity, Long> {

    /**
     * Gets the application from its reference.
     *
     * @param reference The application reference
     * @return The corresponding application
     */
    Optional<ApplicationEntity> findByReference(String reference);

}
