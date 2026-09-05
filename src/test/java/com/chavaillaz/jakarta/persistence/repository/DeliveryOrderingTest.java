package com.chavaillaz.jakarta.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.DeliveryEntity;
import com.chavaillaz.jakarta.persistence.repository.example.DeliveryEntity.Shipment;
import com.chavaillaz.jakarta.persistence.repository.example.DeliveryRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

@DisplayName("Ordering on an association reached through an embeddable")
class DeliveryOrderingTest extends HibernateTest {

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(DeliveryEntity.class, RoasterEntity.class, CoffeeEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void ship() {
        runInTransaction(entityManager -> {
            RoasterEntity moka = new RoasterEntity();
            moka.setName("Moka Brothers");
            entityManager.persist(moka);

            entityManager.persist(delivery("routed", moka, "TRACK-1"));
            entityManager.persist(delivery("pending", null, "TRACK-2"));
        });
    }

    private static DeliveryEntity delivery(String reference, @Nullable RoasterEntity destination, String tracking) {
        DeliveryEntity delivery = new DeliveryEntity();
        delivery.setReference(reference);
        Shipment shipment = new Shipment();
        shipment.setDestination(destination);
        shipment.setTracking(tracking);
        delivery.setShipment(shipment);
        return delivery;
    }

    private <T> T withRepository(Function<DeliveryRepositoryJpa, T> action) {
        return inTransaction(entityManager -> action.apply(new DeliveryRepositoryJpa(entityManager)));
    }

    private static List<String> referencesOf(PaginationResult<DeliveryEntity> result) {
        return result.items().stream().map(DeliveryEntity::getReference).toList();
    }

    @Test
    @DisplayName("keeps a delivery whose destination is not set, the embeddable being joined and not dereferenced")
    void keepsANullAssociationBehindAnEmbeddable() {
        Sort sort = Sort.of(SortCriterion.asc("shipment.destination.name"));

        assertThat(referencesOf(withRepository(repository -> repository.findAll(Pageable.sortedBy(sort)))))
                .containsExactlyInAnyOrder("routed", "pending");
    }

    @Test
    @DisplayName("counts exactly what such an ordering returns, the count being derived from the same query")
    void countsWhatTheOrderingReturns() {
        Sort sort = Sort.of(SortCriterion.asc("shipment.destination.name"));
        PaginationResult<DeliveryEntity> page = withRepository(repository -> repository.findAll(Pageable.of(0, 10, sort)));

        assertThat(page.items()).hasSize(2);
        assertThat(page.totalItems()).as("an inner join would count the pending delivery it dropped").isEqualTo(2);
    }

    @Test
    @DisplayName("emits a left join and only one, the embeddable itself costing no join of its own")
    void emitsASingleLeftJoin() {
        recordStatements();
        withRepository(repository -> repository.findAll(Pageable.sortedBy(Sort.of(SortCriterion.asc("shipment.destination.name")))));

        assertThat(statements()).filteredOn(statement -> statement.startsWith("select")).singleElement().satisfies(statement -> {
            assertThat(statement).contains("left join roaster");
            assertThat(statement.split("join", -1)).as("the embeddable is joined in the criteria tree only").hasSize(2);
        });
    }

    @Test
    @DisplayName("scrolls on an attribute of an embeddable, whose join carries the ordering and the seek alike")
    void scrollsOnAnEmbeddedAttribute() {
        Sort sort = Sort.of(SortCriterion.desc("shipment.tracking"));
        CursorResult<DeliveryEntity> first = withRepository(repository -> repository.findAll(Cursor.first(1, sort)));

        assertThat(first.items()).extracting(DeliveryEntity::getReference).containsExactly("pending");

        CursorResult<DeliveryEntity> second = withRepository(repository -> repository.findAll(Cursor.of(first.next(), 1, sort)));
        assertThat(second.items()).extracting(DeliveryEntity::getReference).containsExactly("routed");
    }
}
