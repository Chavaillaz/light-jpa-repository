package com.chavaillaz.jakarta.persistence.repository.example;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;

/**
 * The score a taster gave a cup of a lot, identified by an embedded identifier spanning three columns.
 */
@Getter
@Setter
@Entity(name = "Cupping")
@Table(name = "cupping")
public class CuppingEntity implements Identifiable<CuppingEntity.CuppingId> {

    @EmbeddedId
    private @Nullable CuppingId id;

    private int score;

    public static CuppingEntity cupping(CuppingId id) {
        CuppingEntity cupping = new CuppingEntity();
        cupping.setId(id);
        return cupping;
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class CuppingId implements Serializable {

        private String taster;

        private int lot;

        private int cup;

    }

}
