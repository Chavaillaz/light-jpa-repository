package com.chavaillaz.jakarta.persistence.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;

/**
 * Everything a query needs from the repository it is written for: the entity manager it runs on, the ordering
 * rules it follows and the codecs its cursor tokens are issued with.
 * <p>
 * The query collaborators are shared per entity type, so they hold nothing of a repository: this context is handed
 * over at each call instead, without exposing the hooks of the repository to its own callers.
 *
 * @param <E> The type of the managed entity
 * @see AbstractRepository#context()
 */
public interface RepositoryContext<E> {

    /**
     * Gets the entity manager the query runs on, which is bound to the transaction of the caller.
     *
     * @return The entity manager of the repository
     */
    EntityManager entityManager();

    /**
     * Gets the default ordering of the repository, applied when none is requested.
     *
     * @param criteriaBuilder The builder to use to create the ordering
     * @param root            The root entity of the query
     * @return The default ordering, an empty list to only sort on the identifier
     * @see AbstractRepository#getDefaultOrders(CriteriaBuilder, Root)
     */
    List<Order> defaultOrders(CriteriaBuilder criteriaBuilder, Root<E> root);

    /**
     * Gets the properties the API consumers are allowed to sort and filter on, mapped to the path of the
     * corresponding entity attribute.
     *
     * @return The searchable properties, an empty map to allow every attribute
     * @see AbstractRepository#searchableProperties()
     */
    Map<String, String> searchableProperties();

    /**
     * Gets the codec of the cursor tokens.
     *
     * @return The codec of the cursor tokens
     * @see AbstractRepository#cursorCodec()
     */
    CursorCodec cursorCodec();

    /**
     * Gets the codec of the cursor key values.
     *
     * @return The codec of the cursor key values
     * @see AbstractRepository#cursorKeyCodec()
     */
    CursorKeyCodec cursorKeyCodec();

}
