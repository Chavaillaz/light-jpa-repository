package com.chavaillaz.jakarta.persistence.repository.example;

import java.util.List;

import com.chavaillaz.jakarta.persistence.repository.Base64CursorCodec;
import com.chavaillaz.jakarta.persistence.repository.CursorCodec;
import com.chavaillaz.jakarta.persistence.repository.CursorPosition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;

/**
 * A repository whose default ordering is computed, and whose cursor codec is overridden: the computed ordering
 * must be rejected as a cursor key, the custom codec must be the one issuing the tokens.
 */
public class RoastedCoffeeRepositoryJpa extends CoffeeRepositoryJpa {

    public static final String PREFIX = "brew-";

    public RoastedCoffeeRepositoryJpa(EntityManager entityManager) {
        super(entityManager);
    }

    @Override
    protected List<Order> getDefaultOrders(CriteriaBuilder criteriaBuilder, Root<CoffeeEntity> root) {
        return List.of(criteriaBuilder.asc(criteriaBuilder.lower(root.get(CoffeeEntity_.name))));
    }

    @Override
    protected CursorCodec cursorCodec() {
        return new Base64CursorCodec() {

            @Override
            public String encode(CursorPosition position) {
                return PREFIX + super.encode(position);
            }

            @Override
            public CursorPosition decode(String token) {
                if (!token.startsWith(PREFIX)) {
                    throw new IllegalArgumentException("Malformed cursor");
                }
                return super.decode(token.substring(PREFIX.length()));
            }

        };
    }

}