package com.chavaillaz.jakarta.persistence.repository.example;

import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;
import com.chavaillaz.jakarta.persistence.repository.example.BeanBatchEntity.BatchId;
import jakarta.persistence.EntityManager;

/**
 * No default ordering: only the two components of the embedded identifier order the queries, exercising the
 * composite cursor key end to end.
 */
public class BeanBatchRepositoryJpa extends AbstractRepository<BeanBatchEntity, BatchId> {

    public BeanBatchRepositoryJpa(EntityManager entityManager) {
        super(entityManager, BeanBatchEntity.class);
    }

}
