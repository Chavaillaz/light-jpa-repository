package com.chavaillaz.jakarta.persistence.repository.example;

import static jakarta.persistence.CascadeType.ALL;
import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.chavaillaz.jakarta.persistence.Identifiable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity(name = "Coffee")
@Table(name = "coffee")
public class CoffeeEntity implements Identifiable<Long> {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String origin;

    @Enumerated(STRING)
    @Column(nullable = false, length = 10)
    private Roast roast;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int strength;

    private Instant roastedAt;

    /**
     * Deliberately nullable, to prove that a nullable attribute cannot be used as a cursor key.
     */
    private String decafLabel;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "roaster_id")
    private RoasterEntity roaster;

    @OneToMany(mappedBy = "coffee", cascade = ALL, orphanRemoval = true)
    private List<TastingNoteEntity> notes = new ArrayList<>();

    public CoffeeEntity addNote(String flavour) {
        TastingNoteEntity note = new TastingNoteEntity();
        note.setFlavour(flavour);
        note.setCoffee(this);
        notes.add(note);
        return this;
    }

    @Override
    public String toString() {
        return "Coffee[%s]".formatted(name);
    }

}