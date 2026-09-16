package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.CuppingEntity.cupping;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CuppingEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CuppingEntity.CuppingId;
import com.chavaillaz.jakarta.persistence.repository.example.CuppingRepositoryJpa;

/**
 * Exercises the lookups by identifier against an entity with an {@code @EmbeddedId} spanning several columns, each
 * of which a lookup binds a parameter for.
 */
@DisplayName("Looking up an entity with an embedded identifier")
class CuppingLookupTest extends HibernateTest {

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CuppingEntity.class);
    }

    @Test
    @DisplayName("binds at most 2000 parameters per query, each identifier binding one per column")
    void boundsTheParametersPerQuery() {
        List<CuppingId> ids = IntStream.range(0, 700)
                .mapToObj(cup -> new CuppingId("Ana", 1, cup))
                .toList();
        runInTransaction(entityManager -> ids.forEach(id -> entityManager.persist(cupping(id))));
        recordStatements();

        List<CuppingEntity> found = inTransaction(entityManager -> new CuppingRepositoryJpa(entityManager).findAllById(ids));

        assertThat(found).hasSize(700);
        assertThat(statements())
                .as("seven hundred identifiers of three columns, which a single query binds 2100 parameters for")
                .hasSize(2)
                .allSatisfy(sql -> assertThat(sql.chars().filter(character -> character == '?').count()).isLessThanOrEqualTo(2000));
    }

}
