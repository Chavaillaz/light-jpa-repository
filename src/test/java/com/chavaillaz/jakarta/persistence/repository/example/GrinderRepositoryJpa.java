package com.chavaillaz.jakarta.persistence.repository.example;

import jakarta.persistence.EntityManager;

import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;

/**
 * No default ordering: the identifier a generic superclass declares is the only cursor key, parsed back from each
 * token as the type argument of the entity.
 */
public class GrinderRepositoryJpa extends AbstractRepository<GrinderEntity, Long> {

    public GrinderRepositoryJpa(EntityManager entityManager) {
        super(entityManager, GrinderEntity.class);
    }

}
