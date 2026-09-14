package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.GrinderEntity.grinder;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.GrinderEntity;
import com.chavaillaz.jakarta.persistence.repository.example.GrinderRepositoryJpa;

/**
 * An identifier declared by a generic mapped superclass is reported by the metamodel as the erasure of its type
 * variable, which no cursor key can be parsed back into.
 */
@DisplayName("Scrolling through an entity whose identifier a generic superclass declares")
class GrinderCursorTest extends HibernateTest {

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(GrinderEntity.class);
    }

    @BeforeEach
    void installTheGrinders() {
        persist(grinder("Comandante"), grinder("Kinu"), grinder("Baratza"), grinder("Eureka"), grinder("Niche"));
    }

    private CursorResult<GrinderEntity> page(String token) {
        return withRepository(GrinderRepositoryJpa.class, repository -> repository.findAll(Cursor.of(token, 2, Sort.NONE)));
    }

    private static List<String> namesOf(CursorResult<GrinderEntity> result) {
        return result.items().stream().map(GrinderEntity::getName).toList();
    }

    @Test
    @DisplayName("seeks from the identifier key of a token, parsed as the type argument of the entity")
    void seeksOnAGenericIdentifier() {
        CursorResult<GrinderEntity> first = page(null);
        CursorResult<GrinderEntity> second = page(first.next());
        CursorResult<GrinderEntity> last = page(second.next());

        assertThat(namesOf(first)).containsExactly("Comandante", "Kinu");
        assertThat(namesOf(second)).containsExactly("Baratza", "Eureka");
        assertThat(namesOf(last)).containsExactly("Niche");
        assertThat(last.hasNext()).isFalse();
        assertThat(namesOf(page(last.previous()))).containsExactly("Baratza", "Eureka");
    }

    @Test
    @DisplayName("streams every entity past its first page")
    void streamsPastTheFirstPage() {
        List<String> names = withRepository(GrinderRepositoryJpa.class, repository ->
                repository.streamAll(Sort.NONE, 2).map(GrinderEntity::getName).toList());

        assertThat(names).containsExactly("Comandante", "Kinu", "Baratza", "Eureka", "Niche");
    }

}
