package com.chavaillaz.jakarta.persistence.repository.example;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;
import static org.hibernate.query.restriction.Restriction.equal;
import static org.hibernate.query.restriction.Restriction.greaterThan;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.jspecify.annotations.NullMarked;

import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;
import com.chavaillaz.jakarta.persistence.repository.Criteria;
import com.chavaillaz.jakarta.persistence.repository.Cursor;
import com.chavaillaz.jakarta.persistence.repository.CursorResult;
import com.chavaillaz.jakarta.persistence.repository.Pageable;
import com.chavaillaz.jakarta.persistence.repository.PaginationResult;
import com.chavaillaz.jakarta.persistence.repository.Sort;
import com.chavaillaz.jakarta.persistence.repository.SortCriterion;

/**
 * Repository of the coffees, exercising every hook of the {@link AbstractRepository}: the searchable properties,
 * the default ordering, the restrictions, the criteria and the cursor.
 */
@NullMarked
public class CoffeeRepositoryJpa extends AbstractRepository<CoffeeEntity, Long> implements CoffeeRepository {

    /**
     * Test constructor, replacing the field injection performed by the CDI container.
     */
    @Inject
    public CoffeeRepositoryJpa(EntityManager entityManager) {
        super(entityManager, CoffeeEntity.class);
    }

    /**
     * A join on the collection, written the naive way: it duplicates a coffee as many times as it has matching
     * notes, and the repository has to keep it from corrupting the results, the count and the pagination.
     */
    public static Criteria<CoffeeEntity> joiningNotes(List<String> flavours) {
        return (criteriaBuilder, query, root) -> root.join(CoffeeEntity_.notes).get(TastingNoteEntity_.flavour).in(flavours);
    }

    /**
     * A correlated subquery, which is exactly what a {@link org.hibernate.query.restriction.Restriction} cannot
     * express, and which does not duplicate the rows as a join on the collection would.
     */
    public static Criteria<CoffeeEntity> tasting(String flavour) {
        return Criteria.exists(TastingNoteEntity.class, TastingNoteEntity_.COFFEE, (criteriaBuilder, note) -> criteriaBuilder.equal(criteriaBuilder.lower(note.get(TastingNoteEntity_.flavour)), flavour.toLowerCase()));
    }

    /**
     * The public naming is deliberately decoupled from the entity one for the roaster and the tasting notes, so
     * that the resolution of a nested path is covered, both for sorting and for RSQL filtering.
     */
    @Override
    protected Map<String, String> searchableProperties() {
        return Map.of(
                "name", "name",
                "origin", "origin",
                "roast", "roast",
                "price", "price",
                "strength", "strength",
                "decaf", "decafLabel",
                "roaster", "roaster.name",
                "notes", "notes.flavour");
    }

    @Override
    protected List<Order> getDefaultOrders(CriteriaBuilder criteriaBuilder, Root<CoffeeEntity> root) {
        return List.of(criteriaBuilder.asc(root.get(CoffeeEntity_.name)));
    }

    @Override
    public List<CoffeeEntity> findByOrigin(String origin) {
        return search(equal(CoffeeEntity_.origin, origin));
    }

    @Override
    public List<CoffeeEntity> findByOrigin(String origin, Sort sort) {
        return search(equal(CoffeeEntity_.origin, origin), sort);
    }

    @Override
    public PaginationResult<CoffeeEntity> findStrongerThan(int strength, Pageable pageable) {
        return search(greaterThan(CoffeeEntity_.strength, strength), pageable);
    }

    @Override
    public Optional<CoffeeEntity> findStrongest() {
        return first(null, null, Sort.of(SortCriterion.desc("strength")));
    }

    @Override
    public List<CoffeeEntity> findTasting(String flavour) {
        return search(tasting(flavour));
    }

    @Override
    public long countTasting(String flavour) {
        return count(tasting(flavour));
    }

    @Override
    public List<CoffeeEntity> findRoastedOrStrong(Roast roast, int strength) {
        return search(Criteria.anyOf(
                Criteria.of(equal(CoffeeEntity_.roast, roast)),
                Criteria.of(greaterThan(CoffeeEntity_.strength, strength))));
    }

    @Override
    public CursorResult<CoffeeEntity> scrollByRoast(Roast roast, Cursor cursor) {
        return scroll(equal(CoffeeEntity_.roast, roast), cursor);
    }

    @Override
    public List<TastingNoteEntity> findNotesOf(CoffeeEntity coffee) {
        return search(TastingNoteEntity.class, equal(TastingNoteEntity_.coffee, coffee));
    }

    @Override
    public Stream<CoffeeEntity> streamFromOrigin(String origin, Sort sort, int pageSize) {
        return stream(equal(CoffeeEntity_.origin, origin), sort, pageSize);
    }

    @Override
    public Stream<CoffeeEntity> streamTasting(String flavour, Sort sort, int pageSize) {
        return stream(tasting(flavour), sort, pageSize);
    }

    /**
     * The queue pattern: the strongest matching coffee is claimed under a write lock, so that a concurrent
     * transaction ordering on the very same criteria cannot claim it as well.
     */
    @Override
    public Optional<CoffeeEntity> claimStrongest(int strength) {
        return first(greaterThan(CoffeeEntity_.strength, strength), null, Sort.of(SortCriterion.desc(CoffeeEntity_.strength)), PESSIMISTIC_WRITE);
    }

    @Override
    public boolean existsFromOrigin(String origin) {
        return exists(equal(CoffeeEntity_.origin, origin));
    }

    @Override
    public boolean existsTasting(String flavour) {
        return exists(tasting(flavour));
    }

    @Override
    public int deleteFromOrigin(String origin) {
        return deleteAll(equal(CoffeeEntity_.origin, origin));
    }

    /**
     * A criteria expressed on a collection, which a bulk deletion carries as a subquery and not as a join.
     */
    @Override
    public int deleteWithoutNotes() {
        return deleteAll((criteriaBuilder, query, root) -> criteriaBuilder.isEmpty(root.get(CoffeeEntity_.notes)));
    }

    @Override
    public int deleteTasting(String flavour) {
        return deleteAll(tasting(flavour));
    }

}