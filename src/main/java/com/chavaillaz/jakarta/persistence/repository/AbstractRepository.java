package com.chavaillaz.jakarta.persistence.repository;

import static jakarta.persistence.LockModeType.NONE;
import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;
import static jakarta.transaction.Transactional.TxType.MANDATORY;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.chavaillaz.jakarta.persistence.Identifiable;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;

/**
 * Abstract repository to manage entities.
 *
 * @param <E> The entity type
 * @param <I> The entity identifier type
 */
@Transactional(MANDATORY)
public abstract class AbstractRepository<E extends Identifiable<I>, I> implements Repository<E, I> {

    /**
     * The entity type.
     */
    protected final Class<E> entityType;

    /**
     * The entity manager.
     */
    protected final EntityManager entityManager;

    /**
     * Constructs a new repository.
     *
     * @param entityType    The entity type
     * @param entityManager The entity manager
     */
    protected AbstractRepository(Class<E> entityType, EntityManager entityManager) {
        this.entityType = entityType;
        this.entityManager = entityManager;
    }

    /**
     * Gets the entity type.
     *
     * @return The entity type
     */
    public Class<E> getEntityType() {
        return entityType;
    }

    /**
     * Gets the entity manager.
     *
     * @return The entity manager
     */
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public List<E> findAll() {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<E> criteriaQuery = criteriaBuilder.createQuery(entityType);
        Root<E> rootEntry = criteriaQuery.from(entityType);
        CriteriaQuery<E> all = criteriaQuery.select(rootEntry);
        TypedQuery<E> allQuery = entityManager.createQuery(all);
        return allQuery.getResultList();
    }

    @Override
    public Optional<E> findById(I id, boolean lock) {
        Map<String, Object> properties = new HashMap<>();
        LockModeType lockMode = lock ? PESSIMISTIC_WRITE : NONE;
        return Optional.ofNullable(id)
                .map(identifier -> entityManager.find(entityType, identifier, lockMode, properties));
    }

    @Override
    public void lock(E entity, LockModeType lockMode) {
        // Refresh to get last state of the entity if being already locked and changed
        entityManager.refresh(entity, lockMode);
    }

    @Override
    public E getReference(I id) {
        return getReference(entityType, id);
    }

    @Override
    public <T> T getReference(Class<T> type, Object id) {
        return Optional.ofNullable(id)
                .map(identifier -> entityManager.getReference(type, identifier))
                .orElse(null);
    }

    @Override
    public E save(E entity) {
        return saveIdentifiable(entity);
    }

    /**
     * Saves the given identifiable entity.
     *
     * @param entity The identifiable entity to save
     * @param <T>    The type of the identifiable entity
     * @return The saved identifiable entity
     */
    protected <T extends Identifiable<?>> T saveIdentifiable(T entity) {
        if (entity.getId() == null) {
            entityManager.persist(entity);
        } else {
            entity = entityManager.merge(entity);
        }
        return entity;
    }

    @Override
    public void delete(E entity) {
        if (entityManager.contains(entity)) {
            entityManager.remove(entity);
        } else {
            entityManager.merge(entity);
        }
    }

}