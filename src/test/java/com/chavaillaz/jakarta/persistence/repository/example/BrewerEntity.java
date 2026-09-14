package com.chavaillaz.jakarta.persistence.repository.example;

import static jakarta.persistence.GenerationType.IDENTITY;
import static jakarta.persistence.InheritanceType.SINGLE_TABLE;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;

/**
 * A brewer, the root of a hierarchy whose subtypes declare collections of their own, which a filter over every
 * brewer can only reach through a treat.
 */
@Getter
@Setter
@Entity(name = "Brewer")
@Table(name = "brewer")
@Inheritance(strategy = SINGLE_TABLE)
public class BrewerEntity implements Identifiable<Long> {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private @Nullable Long id;

    @Column(nullable = false)
    private String name;

    @Override
    public String toString() {
        return "Brewer[%s]".formatted(name);
    }

}
