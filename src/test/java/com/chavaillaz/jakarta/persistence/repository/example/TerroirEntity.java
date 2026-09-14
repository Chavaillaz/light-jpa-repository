package com.chavaillaz.jakarta.persistence.repository.example;

import static jakarta.persistence.GenerationType.IDENTITY;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;

/**
 * A terroir, whose region keeps the accent of its French name, as a Java identifier may.
 */
@Getter
@Setter
@Entity(name = "Terroir")
@Table(name = "terroir")
public class TerroirEntity implements Identifiable<Long> {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private @Nullable Long id;

    @Column(name = "region", nullable = false)
    private String région;

}
