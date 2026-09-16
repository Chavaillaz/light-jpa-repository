package com.chavaillaz.jakarta.persistence.repository.example;

import jakarta.persistence.EntityManager;

import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;
import com.chavaillaz.jakarta.persistence.repository.example.HarvestEntity.HarvestId;

/**
 * No default ordering: the attributes of the identifier class are the only ones the queries order on.
 */
public class HarvestRepositoryJpa extends AbstractRepository<HarvestEntity, HarvestId> {

    public HarvestRepositoryJpa(EntityManager entityManager) {
        super(entityManager, HarvestEntity.class);
    }

}
