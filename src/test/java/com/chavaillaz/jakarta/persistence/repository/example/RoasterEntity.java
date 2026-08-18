package com.chavaillaz.jakarta.persistence.repository.example;

import static jakarta.persistence.GenerationType.IDENTITY;

import java.util.ArrayList;
import java.util.List;

import com.chavaillaz.jakarta.persistence.Identifiable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity(name = "Roaster")
@Table(name = "roaster")
public class RoasterEntity implements Identifiable<Long> {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String country;

    @OneToMany(mappedBy = "roaster")
    private List<CoffeeEntity> coffees = new ArrayList<>();

    @Override
    public String toString() {
        return "Roaster[%s]".formatted(name);
    }

}