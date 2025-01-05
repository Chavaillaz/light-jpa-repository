package com.chavaillaz.jakarta.persistence.repository.example;

import java.util.Optional;

import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;
import com.chavaillaz.jakarta.persistence.repository.JpaRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@Transactional
@JpaRepository
@ApplicationScoped
public class ApplicationRepositoryJpa extends AbstractRepository<ApplicationEntity, Long> implements ApplicationRepository {

    @Inject
    public ApplicationRepositoryJpa(EntityManager entityManager) {
        super(ApplicationEntity.class, entityManager);
    }

    @Override
    public Optional<ApplicationEntity> findByReference(String reference) {
        return getEntityManager()
                .createQuery("""
                        SELECT application
                        FROM ApplicationEntity application
                        WHERE application.reference = :reference
                        """, entityType)
                .setParameter("reference", reference)
                .getResultStream()
                .findFirst();
    }

}
