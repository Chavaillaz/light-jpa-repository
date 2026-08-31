package com.chavaillaz.jakarta.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.BeanBatchEntity;
import com.chavaillaz.jakarta.persistence.repository.example.BeanBatchEntity.BatchId;
import com.chavaillaz.jakarta.persistence.repository.example.BeanBatchRepositoryJpa;

/**
 * Exercises the cursor pagination end to end against an entity with an {@code @EmbeddedId}, the composite key
 * being otherwise only covered at the unit level by {@link EntityOrderingTest}.
 */
@DisplayName("Scrolling through an entity with a composite identifier")
class BeanBatchCursorTest extends HibernateTest {

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(BeanBatchEntity.class);
    }

    private static BeanBatchEntity batch(String roasterCode, int batchNumber) {
        BeanBatchEntity batch = new BeanBatchEntity();
        batch.setId(new BatchId(roasterCode, batchNumber));
        batch.setRoastedOn(LocalDate.of(2024, 1, batchNumber));
        batch.setKilograms(10 * batchNumber);
        // Stored padded and in lower case, whereas the accessor returns it trimmed and upper cased: the two
        // therefore do not even sort the same way, every stored value starting with a space
        batch.setLabel(" %s%d ".formatted(roasterCode.toLowerCase(), batchNumber));
        return batch;
    }

    private static List<BatchId> idsOf(CursorResult<BeanBatchEntity> result) {
        return result.items().stream().map(BeanBatchEntity::getId).toList();
    }

    @BeforeEach
    void brewTheBatches() {
        runInTransaction(entityManager -> {
            entityManager.persist(batch("KLD", 1));
            entityManager.persist(batch("MKB", 1));
            entityManager.persist(batch("KLD", 2));
            entityManager.persist(batch("MKB", 2));
        });
    }

    private CursorResult<BeanBatchEntity> page(String token, int size) {
        return withRepository(BeanBatchRepositoryJpa.class, repository -> repository.findAll(Cursor.of(token, size, Sort.NONE)));
    }

    private CursorResult<BeanBatchEntity> labelled(String token) {
        return withRepository(BeanBatchRepositoryJpa.class, repository -> repository.findAll(Cursor.of(token, 1, Sort.parse("label"))));
    }

    @Test
    @DisplayName("orders on the components of the embedded identifier, sorted by name, no default ordering set")
    void ordersOnTheComponents() {
        CursorResult<BeanBatchEntity> first = page(null, 4);

        assertThat(idsOf(first)).containsExactly(
                new BatchId("KLD", 1), new BatchId("MKB", 1),
                new BatchId("KLD", 2), new BatchId("MKB", 2));
    }

    @Test
    @DisplayName("seeks correctly on both components of the composite key across pages")
    void seeksOnBothComponents() {
        CursorResult<BeanBatchEntity> first = page(null, 2);
        assertThat(idsOf(first)).containsExactly(new BatchId("KLD", 1), new BatchId("MKB", 1));
        assertThat(first.hasNext()).isTrue();

        CursorResult<BeanBatchEntity> second = page(first.next(), 2);
        assertThat(idsOf(second)).containsExactly(new BatchId("KLD", 2), new BatchId("MKB", 2));
        assertThat(second.hasNext()).isFalse();
        assertThat(second.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("walks backward and gives back the very same first page")
    void walksBackward() {
        CursorResult<BeanBatchEntity> first = page(null, 2);
        CursorResult<BeanBatchEntity> second = page(first.next(), 2);

        CursorResult<BeanBatchEntity> back = page(second.previous(), 2);

        assertThat(idsOf(back)).containsExactlyElementsOf(idsOf(first));
    }

    @Test
    @DisplayName("seeks on the value the database holds, not on the one the accessor of the entity returns")
    void seeksOnTheStoredValue() {
        // The label is stored padded and in lower case, and its accessor returns it trimmed and upper cased: a
        // key read from the entity would be compared against a column no row of which even starts the same way,
        // so the walk would stop after its first page instead of reaching the following ones
        List<BatchId> visited = new ArrayList<>();
        String token = null;
        for (int page = 0; page < 5; page++) {
            CursorResult<BeanBatchEntity> result = labelled(token);
            visited.addAll(idsOf(result));
            token = result.next();
            if (token == null) {
                break;
            }
        }

        assertThat(visited).containsExactly(
                new BatchId("KLD", 1), new BatchId("KLD", 2),
                new BatchId("MKB", 1), new BatchId("MKB", 2));
    }

}
