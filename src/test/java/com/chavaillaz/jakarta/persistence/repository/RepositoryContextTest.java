package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BLUE_MOUNTAIN;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.KONA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.MENU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity_;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.RoastedCoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

/**
 * The query collaborators are shared per entity type, which is only correct because they hold nothing of any
 * repository: whatever differs from one to the next travels with the {@link RepositoryContext} of each call.
 */
@DisplayName("Sharing the collaborators per entity type")
class RepositoryContextTest extends HibernateTest {

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void brewTheMenu() {
        runInTransaction(Coffees::persistMenu);
    }

    @Test
    @DisplayName("hands the very same collaborators to every repository over an entity")
    void sharesTheCollaborators() {
        inTransaction(entityManager -> {
            CoffeeRepositoryJpa first = new CoffeeRepositoryJpa(entityManager);
            CoffeeRepositoryJpa second = new StrongestFirstRepository(entityManager);

            assertThat(first.queries()).isSameAs(second.queries()).isSameAs(EntityQueries.of(CoffeeEntity.class));
            assertThat(first.ordering()).isSameAs(second.ordering()).isSameAs(EntityOrdering.of(CoffeeEntity.class));
            return null;
        });
    }

    @Test
    @DisplayName("still applies the ordering of each repository, sharing the collaborators notwithstanding")
    void keepsTheRulesOfEachRepository() {
        inTransaction(entityManager -> {
            List<String> byName = namesOf(new CoffeeRepositoryJpa(entityManager).findAll());
            List<String> byStrength = namesOf(new StrongestFirstRepository(entityManager).findAll());

            assertThat(byName).startsWith(BLUE_MOUNTAIN);
            assertThat(byStrength).as("the shared collaborator asked this repository for its own ordering")
                    .startsWith(HARRAR)
                    .endsWith(GEISHA);
            return null;
        });
    }

    @Test
    @DisplayName("still restricts each repository to its own searchable properties, sharing the collaborators notwithstanding")
    void keepsTheSearchablePropertiesOfEachRepository() {
        Sort byRoastingDate = Sort.parse("roastedAt");

        inTransaction(entityManager -> {
            assertThat(new UnrestrictedRepository(entityManager).findAll(byRoastingDate))
                    .as("this repository declares no searchable property, so that every attribute can be sorted on")
                    .hasSize(MENU.size());
            assertThatIllegalArgumentException()
                    .as("the coffee repository leaves the roasting date out of its searchable properties")
                    .isThrownBy(() -> new CoffeeRepositoryJpa(entityManager).findAll(byRoastingDate))
                    .withMessageContaining("unknown property roastedAt");
            return null;
        });
    }

    @Test
    @DisplayName("still writes and reads the tokens with the codecs of each repository, sharing the collaborators notwithstanding")
    void keepsTheCodecsOfEachRepository() {
        Sort sort = Sort.parse("name");

        inTransaction(entityManager -> {
            CoffeeRepositoryJpa brewing = new RoastedCoffeeRepositoryJpa(entityManager);
            CoffeeRepositoryJpa prefixing = new PrefixedKeysRepository(entityManager);
            CoffeeRepositoryJpa plain = new CoffeeRepositoryJpa(entityManager);

            String brewed = brewing.findAll(Cursor.first(3, sort)).next();
            String prefixed = prefixing.findAll(Cursor.first(3, sort)).next();
            String issued = plain.findAll(Cursor.first(3, sort)).next();

            assertThat(brewed).as("a token written with the codec of its repository").startsWith(RoastedCoffeeRepositoryJpa.PREFIX);
            assertThat(CursorCodec.DEFAULT.decode(prefixed).values())
                    .as("keys written with the key codec of their repository")
                    .hasSize(2)
                    .allMatch(key -> key.startsWith(PrefixedKeysRepository.PREFIX));
            assertThat(issued).as("a token written with the default codecs").doesNotStartWith(RoastedCoffeeRepositoryJpa.PREFIX);
            assertThat(CursorCodec.DEFAULT.decode(issued).values()).noneMatch(key -> key.startsWith(PrefixedKeysRepository.PREFIX));

            assertThat(namesOf(brewing.findAll(Cursor.of(brewed, 3, sort))))
                    .as("a token read back with the codecs that wrote it")
                    .containsExactly(HARRAR, KONA, SIDAMO);
            assertThat(namesOf(prefixing.findAll(Cursor.of(prefixed, 3, sort)))).containsExactly(HARRAR, KONA, SIDAMO);
            return null;
        });
    }

    @Test
    @DisplayName("runs the queries of each repository on its own entity manager, and on no other")
    void runsOnTheEntityManagerOfTheCaller() {
        inTransaction(first -> inTransaction(second -> {
            CoffeeRepositoryJpa firstRepository = new CoffeeRepositoryJpa(first);
            CoffeeRepositoryJpa secondRepository = new CoffeeRepositoryJpa(second);

            // Interleaved, so that a collaborator holding on to the entity manager of an earlier call would show
            List<CoffeeEntity> firstPage = firstRepository.findAll();
            List<CoffeeEntity> secondPage = secondRepository.findAll();
            List<CoffeeEntity> firstScroll = firstRepository.findAll(Cursor.first(MENU.size(), Sort.NONE)).items();
            List<CoffeeEntity> secondScroll = secondRepository.findAll(Cursor.first(MENU.size(), Sort.NONE)).items();

            assertThat(firstPage).as("an offset page").hasSize(MENU.size()).allMatch(first::contains).noneMatch(second::contains);
            assertThat(secondPage).as("an offset page").hasSize(MENU.size()).allMatch(second::contains).noneMatch(first::contains);
            assertThat(firstScroll).as("a cursor page").hasSize(MENU.size()).allMatch(first::contains).noneMatch(second::contains);
            assertThat(secondScroll).as("a cursor page").hasSize(MENU.size()).allMatch(second::contains).noneMatch(first::contains);
            return null;
        }));
    }

    /**
     * The very same entity, with another default ordering: what the shared collaborators must not mix up.
     */
    static class StrongestFirstRepository extends CoffeeRepositoryJpa {

        StrongestFirstRepository(EntityManager entityManager) {
            super(entityManager);
        }

        @Override
        protected List<Order> getDefaultOrders(CriteriaBuilder criteriaBuilder, Root<CoffeeEntity> root) {
            return List.of(criteriaBuilder.desc(root.get(CoffeeEntity_.strength)));
        }

    }

    /**
     * The very same entity, declaring no searchable property, so that every attribute can be sorted on.
     */
    static class UnrestrictedRepository extends CoffeeRepositoryJpa {

        UnrestrictedRepository(EntityManager entityManager) {
            super(entityManager);
        }

        @Override
        protected Map<String, String> searchableProperties() {
            return Map.of();
        }

    }

    /**
     * The very same entity, writing its cursor keys behind a prefix, which it strips when reading them back.
     */
    static class PrefixedKeysRepository extends CoffeeRepositoryJpa {

        static final String PREFIX = "key:";

        PrefixedKeysRepository(EntityManager entityManager) {
            super(entityManager);
        }

        @Override
        protected CursorKeyCodec cursorKeyCodec() {
            return new CursorKeyCodec() {

                @Override
                public String format(String property, @Nullable Object value) {
                    return PREFIX + CursorKeyCodec.DEFAULT.format(property, value);
                }

                @Override
                public <Y> Y parse(String value, Class<Y> type) {
                    return CursorKeyCodec.DEFAULT.parse(value.substring(PREFIX.length()), type);
                }

            };
        }

    }

}
