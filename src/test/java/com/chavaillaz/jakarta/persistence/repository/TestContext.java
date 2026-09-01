package com.chavaillaz.jakarta.persistence.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The context a repository hands over to the query collaborators, assembled by hand so that the collaborators can
 * be exercised on their own, without a repository around them.
 *
 * @param <E>            The type of the managed entity
 * @param entityManager  The entity manager the queries run on
 * @param defaults       The default ordering of the repository
 * @param properties     The properties that can be sorted and filtered on
 * @param cursorCodec    The codec of the cursor tokens
 * @param cursorKeyCodec The codec of the cursor key values
 */
record TestContext<E>(
        EntityManager entityManager,
        BiFunction<CriteriaBuilder, Root<E>, List<Order>> defaults,
        Map<String, String> properties,
        CursorCodec cursorCodec,
        CursorKeyCodec cursorKeyCodec) implements RepositoryContext<E> {

    TestContext(EntityManager entityManager, BiFunction<CriteriaBuilder, Root<E>, List<Order>> defaults, Map<String, String> properties) {
        this(entityManager, defaults, properties, CursorCodec.DEFAULT, CursorKeyCodec.DEFAULT);
    }

    @Override
    public List<Order> defaultOrders(CriteriaBuilder criteriaBuilder, Root<E> root) {
        return defaults.apply(criteriaBuilder, root);
    }

    @Override
    public Map<String, String> searchableProperties() {
        return properties;
    }

}
