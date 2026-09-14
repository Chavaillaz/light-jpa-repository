package com.chavaillaz.jakarta.persistence.repository.example;

import static jakarta.persistence.CascadeType.ALL;

import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * An espresso machine, whose drinks only a brewer treated as one can join.
 */
@Getter
@Setter
@Entity(name = "EspressoMachine")
public class EspressoMachineEntity extends BrewerEntity {

    @OneToMany(mappedBy = "machine", cascade = ALL)
    private List<DrinkEntity> drinks = new ArrayList<>();

    public static EspressoMachineEntity machine(String name, String... drinks) {
        EspressoMachineEntity machine = new EspressoMachineEntity();
        machine.setName(name);
        for (String drink : drinks) {
            machine.getDrinks().add(DrinkEntity.drink(drink, machine));
        }
        return machine;
    }

}
