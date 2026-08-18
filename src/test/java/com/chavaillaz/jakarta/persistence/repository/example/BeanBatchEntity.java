package com.chavaillaz.jakarta.persistence.repository.example;

import java.io.Serializable;
import java.time.LocalDate;

import com.chavaillaz.jakarta.persistence.Identifiable;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity(name = "BeanBatch")
@Table(name = "bean_batch")
public class BeanBatchEntity implements Identifiable<BeanBatchEntity.BatchId> {

    @EmbeddedId
    private BatchId id;

    private LocalDate roastedOn;

    private int kilograms;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class BatchId implements Serializable {

        @Column(name = "roaster_code", nullable = false, length = 10)
        private String roasterCode;

        @Column(name = "batch_number", nullable = false)
        private int batchNumber;

    }

}