package com.chavaillaz.jakarta.persistence.repository.example;

import static jakarta.persistence.GenerationType.IDENTITY;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;

/**
 * A piece of equipment, leaving the type of its identifier to each subtype: the metamodel reports that identifier
 * as the erasure of its type variable, whereas it holds the type argument of the entity.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class EquipmentEntity<K> implements Identifiable<K> {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private @Nullable K id;

}
