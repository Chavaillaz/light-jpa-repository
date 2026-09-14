package com.chavaillaz.jakarta.persistence.repository.example;

import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;

import jakarta.persistence.Column;
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
 * A drink an espresso machine brews.
 */
@Getter
@Setter
@Entity(name = "Drink")
@Table(name = "drink")
public class DrinkEntity implements Identifiable<Long> {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private @Nullable Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "machine_id")
    private EspressoMachineEntity machine;

    public static DrinkEntity drink(String name, EspressoMachineEntity machine) {
        DrinkEntity drink = new DrinkEntity();
        drink.setName(name);
        drink.setMachine(machine);
        return drink;
    }

    @Override
    public String toString() {
        return "Drink[%s]".formatted(name);
    }

}
