package com.chavaillaz.jakarta.persistence.repository.example;

import jakarta.persistence.EntityManager;

import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;

/**
 * No searchable properties and no default ordering: every attribute is reachable, only the identifier orders.
 */
public class DeliveryRepositoryJpa extends AbstractRepository<DeliveryEntity, Long> {

    public DeliveryRepositoryJpa(EntityManager entityManager) {
        super(entityManager, DeliveryEntity.class);
    }

}
