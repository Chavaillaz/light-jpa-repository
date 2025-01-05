package com.chavaillaz.jakarta.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.chavaillaz.jakarta.persistence.repository.example.ApplicationEntity;
import com.chavaillaz.jakarta.persistence.repository.example.ApplicationRepository;
import com.chavaillaz.jakarta.persistence.repository.example.ApplicationRepositoryJpa;
import org.hibernate.internal.util.MutableLong;
import org.junit.jupiter.api.Test;

class ApplicationRepositoryTest extends HibernateTest {

    @Test
    void testRepository() {
        MutableLong identifier = new MutableLong();
        testCreate(identifier);
        testFindAll();
        testGetById(identifier);
        testCustomFind();
        testDelete(identifier);
    }

    private void testCreate(MutableLong identifier) {
        runInTransaction(entityManager -> {
            ApplicationRepository repository = new ApplicationRepositoryJpa(entityManager);
            ApplicationEntity application = new ApplicationEntity();
            application.setName("Application");
            application.setReference("REF-1");
            repository.save(application); // Persist
            repository.save(application); // Merge
            identifier.set(application.getId());
        });
    }

    private void testFindAll() {
        runInTransaction(entityManager -> {
            ApplicationRepository repository = new ApplicationRepositoryJpa(entityManager);
            List<ApplicationEntity> retrieved = repository.findAll();
            assertEquals(1, retrieved.size());
        });
    }

    private void testGetById(MutableLong identifier) {
        runInTransaction(entityManager -> {
            ApplicationRepository repository = new ApplicationRepositoryJpa(entityManager);
            ApplicationEntity retrieved = repository.getById(identifier.get());
            assertNotNull(retrieved);
        });
    }

    private void testCustomFind() {
        runInTransaction(entityManager -> {
            ApplicationRepository repository = new ApplicationRepositoryJpa(entityManager);
            Optional<ApplicationEntity> retrieved = repository.findByReference("REF-1");
            assertTrue(retrieved.isPresent());
        });
    }

    private void testDelete(MutableLong identifier) {
        runInTransaction(entityManager -> {
            ApplicationRepositoryJpa repository = new ApplicationRepositoryJpa(entityManager);
            ApplicationEntity reference = repository.getReference(identifier.get());
            repository.delete(reference); // Remove
            entityManager.flush();
            List<ApplicationEntity> retrieved = repository.findAll();
            assertTrue(retrieved.isEmpty());
            assertEquals(ApplicationEntity.class, repository.getEntityType());
        });
    }

}
