package com.chavaillaz.jakarta.persistence.repository;

import static org.slf4j.LoggerFactory.getLogger;

import java.util.function.Consumer;

import com.chavaillaz.jakarta.persistence.repository.example.ApplicationEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;

/**
 * Base class for tests with Hibernate, setting up a usable in memory database.
 */
public abstract class HibernateTest {

    private static final Logger log = getLogger(HibernateTest.class);

    protected static SessionFactory sessionFactory = null;
    protected Session session = null;

    @BeforeAll
    public static void setupAll() {
        setupSessionFactory(ApplicationEntity.class);
    }

    /**
     * Setups the session factory for the given entity types.
     *
     * @param types The entity types
     */
    protected static void setupSessionFactory(Class<?> types) {
        StandardServiceRegistry standardRegistry = new StandardServiceRegistryBuilder()
                .loadProperties("hibernate.properties")
                .build();

        Metadata metadata = new MetadataSources(standardRegistry)
                .addAnnotatedClass(types)
                .getMetadataBuilder()
                .build();

        sessionFactory = metadata
                .getSessionFactoryBuilder()
                .applyAutoFlushing(true)
                .build();
    }

    @AfterAll
    public static void cleanAll() {
        sessionFactory.close();
    }

    @BeforeEach
    public void setupCurrent() {
        session = sessionFactory.openSession();
    }

    /**
     * Runs the given runnable in a transaction.
     *
     * @param runnable The runnable to execute
     */
    protected void runInTransaction(Consumer<EntityManager> runnable) {
        log.debug("Starting transaction");
        EntityManager entityManager = session.getEntityManagerFactory().createEntityManager();
        EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        runnable.accept(entityManager);
        if (!transaction.getRollbackOnly()) {
            log.debug("Committing transaction");
            transaction.commit();
        } else {
            log.debug("Rolling back transaction");
            transaction.rollback();
        }
        entityManager.close();
    }

}
