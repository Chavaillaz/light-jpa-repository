package com.chavaillaz.jakarta.persistence.repository.example;

import jakarta.persistence.EntityManager;

import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;
import com.chavaillaz.jakarta.persistence.repository.example.CuppingEntity.CuppingId;

/**
 * No default ordering: the components of the embedded identifier are the only attributes the queries order on.
 */
public class CuppingRepositoryJpa extends AbstractRepository<CuppingEntity, CuppingId> {

    public CuppingRepositoryJpa(EntityManager entityManager) {
        super(entityManager, CuppingEntity.class);
    }

}
