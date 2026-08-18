package com.chavaillaz.jakarta.persistence.repository.example;

import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;
import jakarta.persistence.EntityManager;

/**
 * No searchable properties and no default ordering: every attribute is reachable, only the id orders.
 */
public class RoasterRepositoryJpa extends AbstractRepository<RoasterEntity, Long> {

    public RoasterRepositoryJpa(EntityManager entityManager) {
        super(entityManager, RoasterEntity.class);
    }

}