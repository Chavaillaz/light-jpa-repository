package com.chavaillaz.jakarta.persistence.repository.example;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
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
 * A harvest of a plot of a farm in a season, identified through an identifier class: unlike an embedded identifier,
 * no single attribute holds the whole of it, which spans three columns.
 */
@Getter
@Setter
@Entity(name = "Harvest")
@Table(name = "harvest")
@IdClass(HarvestEntity.HarvestId.class)
public class HarvestEntity implements Identifiable<HarvestEntity.HarvestId> {

    @Id
    private @Nullable String farm;

    @Id
    private int plot;

    @Id
    private int season;

    private int kilograms;

    public static HarvestEntity harvest(HarvestId id) {
        HarvestEntity harvest = new HarvestEntity();
        harvest.setFarm(id.getFarm());
        harvest.setPlot(id.getPlot());
        harvest.setSeason(id.getSeason());
        return harvest;
    }

    @Override
    public @Nullable HarvestId getId() {
        return farm == null ? null : new HarvestId(farm, plot, season);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class HarvestId implements Serializable {

        private String farm;

        private int plot;

        private int season;

    }

}
