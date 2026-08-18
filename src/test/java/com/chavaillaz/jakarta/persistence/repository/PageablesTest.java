package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.KONA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.hibernate.query.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Pageables")
class PageablesTest extends HibernateTest {

    private EntityManager entityManager;

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void openEntityManager() {
        entityManager = sessionFactory.createEntityManager();
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    private TypedQuery<CoffeeEntity> query() {
        return entityManager.createQuery("from Coffee", CoffeeEntity.class);
    }

    @Test
    @DisplayName("converts the coordinates into a Hibernate page, size first")
    void convertsToAHibernatePage() {
        Page page = Pageables.toPage(Pageable.of(2, 10));

        assertThat(page.getSize()).isEqualTo(10);
        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.getFirstResult()).isEqualTo(20);
        assertThat(page.getMaxResults()).isEqualTo(10);
    }

    @Test
    @DisplayName("applies the offset and the limit to the query")
    void appliesThePagination() {
        TypedQuery<CoffeeEntity> query = query();

        Pageables.apply(query, Pageable.of(2, 10));

        assertThat(query.getFirstResult()).isEqualTo(20);
        assertThat(query.getMaxResults()).isEqualTo(10);
    }

    @Test
    @DisplayName("leaves the query untouched when the pagination is not requested or invalid")
    void leavesTheQueryUntouched() {
        TypedQuery<CoffeeEntity> query = query();

        Pageables.apply(query, Pageable.UNPAGED);
        Pageables.apply(query, Pageable.of(-1, 10));
        Pageables.apply(query, Pageable.of(0, 0));

        assertThat(query.getFirstResult()).isZero();
        assertThat(query.getMaxResults()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("really paginates a query against the database")
    void paginatesAgainstTheDatabase() {
        runInTransaction(Coffees::persistMenu);

        TypedQuery<CoffeeEntity> query = entityManager.createQuery("from Coffee order by name", CoffeeEntity.class);
        Pageables.apply(query, Pageable.of(1, 3));

        assertThat(namesOf(query.getResultList())).containsExactly(HARRAR, KONA, SIDAMO);
    }

    @Test
    @DisplayName("computes the total only when the results are paginated")
    void computesTheTotalOnlyWhenPaginated() {
        AtomicInteger calls = new AtomicInteger();
        LongSupplier total = () -> {
            calls.incrementAndGet();
            return 42L;
        };
        List<String> items = List.of("Arabica", "Robusta");

        PaginationResult<String> paginated = Pageables.toResult(items, Pageable.of(1, 2), total);
        assertThat(paginated.totalItems()).isEqualTo(42);
        assertThat(paginated.currentPage()).isEqualTo(1);
        assertThat(paginated.pageSize()).isEqualTo(2);
        assertThat(calls).hasValue(1);

        PaginationResult<String> single = Pageables.toResult(items, Pageable.UNPAGED, total);
        assertThat(single.totalItems()).as("the returned rows are the whole result set").isEqualTo(2);
        assertThat(calls).as("no count query is issued when unpaged").hasValue(1);
    }

}