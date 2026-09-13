package com.chavaillaz.jakarta.persistence.repository.example;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Locale;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;

@Getter
@Setter
@Entity(name = "BeanBatch")
@Table(name = "bean_batch")
public class BeanBatchEntity implements Identifiable<BeanBatchEntity.BatchId> {

    @EmbeddedId
    private @Nullable BatchId id;

    private LocalDate roastedOn;

    private int kilograms;

    /**
     * Mandatory on purpose: it is what the diverging accessor below is scrolled on, and a cursor refuses a
     * nullable key.
     */
    @Column(name = "label", length = 20, nullable = false)
    private @Nullable String label;

    /**
     * Deliberately divergent from the column it maps: the accessor trims and upper cases what the database
     * holds, so an ordering key read from the entity is not the value the {@code order by} clause compares.
     */
    public @Nullable String getLabel() {
        return label == null ? null : label.trim().toUpperCase(Locale.ROOT);
    }

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