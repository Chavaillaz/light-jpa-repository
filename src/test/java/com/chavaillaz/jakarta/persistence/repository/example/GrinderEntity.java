package com.chavaillaz.jakarta.persistence.repository.example;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * A grinder, identified by a {@code Long} its generic superclass declares as a type variable.
 */
@Getter
@Setter
@Entity(name = "Grinder")
@Table(name = "grinder")
public class GrinderEntity extends EquipmentEntity<Long> {

    @Column(nullable = false)
    private String name;

    public static GrinderEntity grinder(String name) {
        GrinderEntity grinder = new GrinderEntity();
        grinder.setName(name);
        return grinder;
    }

    @Override
    public String toString() {
        return "Grinder[%s]".formatted(name);
    }

}
