package com.chavaillaz.jakarta.persistence.repository.example;

import jakarta.persistence.EntityManager;

import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;
import com.chavaillaz.jakarta.persistence.repository.example.BeanBatchEntity.BatchId;

/**
 * No default ordering: only the two components of the embedded identifier order the queries, exercising the
 * composite cursor key end to end.
 */
public class BeanBatchRepositoryJpa extends AbstractRepository<BeanBatchEntity, BatchId> {

    public BeanBatchRepositoryJpa(EntityManager entityManager) {
        super(entityManager, BeanBatchEntity.class);
    }

}
