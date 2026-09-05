package com.chavaillaz.jakarta.persistence.repository.example;

import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;

/**
 * A delivery whose destination sits behind an embeddable, so that an ordering on {@code shipment.destination.name}
 * has to reach an association through one, which only a join can do without dropping the rows having none.
 */
@Getter
@Setter
@Entity(name = "Delivery")
@Table(name = "delivery")
public class DeliveryEntity implements Identifiable<Long> {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private @Nullable Long id;

    @Column(nullable = false)
    private String reference;

    @Embedded
    private Shipment shipment = new Shipment();

    @Embeddable
    @Getter
    @Setter
    public static class Shipment {

        /**
         * Deliberately nullable, a delivery not being routed yet: an implicit inner join through the embeddable
         * would drop it from the results while the count still holds it.
         */
        @ManyToOne(fetch = LAZY)
        @JoinColumn(name = "destination_id")
        private @Nullable RoasterEntity destination;

        @Column(name = "tracking")
        private @Nullable String tracking;

    }

}
