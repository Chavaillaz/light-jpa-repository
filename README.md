# Light JPA Repository

![Quality Gate](https://github.com/chavaillaz/light-jpa-repository/actions/workflows/sonarcloud.yml/badge.svg)
![Dependency Check](https://github.com/chavaillaz/light-jpa-repository/actions/workflows/snyk.yml/badge.svg)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/light-jpa-repository/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/light-jpa-repository)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Library to help implementing JPA based (Java Persistence API) repositories.

It gives you a `Repository` base implementation covering the usual CRUD operations, offset and cursor (keyset)
pagination, sorting and type-safe filtering with the Hibernate `Restriction` API — without generating queries for
you like a heavier framework such as Spring Data would. You stay in control of every custom query you write.

Dynamic filtering exposed to the API consumers as [RSQL](https://github.com/jirutka/rsql-parser) query strings is
not part of this library: it is an optional extension, built on the very same collaborators, provided by the
sibling [rsql-jpa-repository](https://github.com/chavaillaz/rsql-jpa-repository).

## Installation

The dependency is available in maven central (see badge for version):

```xml
<dependency>
    <groupId>com.chavaillaz</groupId>
    <artifactId>light-jpa-repository</artifactId>
</dependency>
```

Hibernate itself is a `provided` dependency, so that the library does not force a specific version on your project:
add `hibernate-core` alongside it. Type-safe filtering with `Restriction` and the metamodel-typed `SortCriterion`
factories rely on the JPA static metamodel, so also add the `hibernate-processor` annotation processor:

```xml
<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-core</artifactId>
    <version>${hibernate.version}</version>
</dependency>

<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-processor</artifactId>
    <version>${hibernate.version}</version>
    <scope>provided</scope>
</dependency>
```

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.hibernate.orm</groupId>
                <artifactId>hibernate-processor</artifactId>
                <version>${hibernate.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

## Usage

The library's goal is to provide the following essential methods for your repositories (`Repository` interface):

- **`findAll`**: Retrieves the entities of the repository, with no, offset or cursor pagination.
- **`getById`** and **`findById`**: Fetches an entity by its identifier.
- **`count`**: Counts the entities of the repository.
- **`lock`**: Applies a pessimistic lock to an entity, re-attaching and refreshing it first if needed.
- **`refresh`**: Reloads the state of a managed entity from the database, discarding local changes.
- **`getReference`**: Retrieves a reference to an entity, with its state lazily fetched.
- **`save`**: Persists a new entity or merges an existing one.
- **`delete`**, **`deleteById`** and **`deleteAllById`**: Removes an entity.

To use the library, ensure that your entities implement the `Identifiable` interface to enable retrieval of their
primary key. Next, define an interface that extends the `Repository` interface and add any custom methods needed for
your entity management. Then, create an implementation of this interface that extends the `AbstractRepository` class
and provides implementations for your custom methods.

Once the repository is implemented, it's ready for use. If you're working in a CDI environment, you can annotate the
repository with `@JpaRepository` and `@ApplicationScoped` to enable automatic injection into your services.

## Example

Taking the example of a coffee entity, implementing the `Identifiable` interface:

```java
@Entity
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
    @Column(nullable = false)
    private Roast roast;

    @Column(nullable = false)
    private int strength;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "roaster_id")
    private RoasterEntity roaster;

    // Getters, setters, ...
}
```

We can now create the repository interface, with, for example, a few custom methods:

```java
public interface CoffeeRepository extends Repository<CoffeeEntity, Long> {

    List<CoffeeEntity> findByOrigin(String origin);

    Optional<CoffeeEntity> findStrongest();

    CursorResult<CoffeeEntity> scrollByRoast(Roast roast, Cursor cursor);

}
```

Next, implement the repository by extending the `AbstractRepository` class. Its `protected` helpers (`search`,
`first`, `scroll`, `count`, ...) are what a custom method is written with:

```java
@Transactional
@JpaRepository
@ApplicationScoped
public class CoffeeRepositoryJpa extends AbstractRepository<CoffeeEntity, Long> implements CoffeeRepository {

    @Inject
    public CoffeeRepositoryJpa(EntityManager entityManager) {
        super(entityManager, CoffeeEntity.class);
    }

    @Override
    public List<CoffeeEntity> findByOrigin(String origin) {
        return search(Restriction.equal(CoffeeEntity_.origin, origin));
    }

    @Override
    public Optional<CoffeeEntity> findStrongest() {
        return first(null, null, Sort.of(SortCriterion.desc(CoffeeEntity_.strength)));
    }

    @Override
    public CursorResult<CoffeeEntity> scrollByRoast(Roast roast, Cursor cursor) {
        return scroll(Restriction.equal(CoffeeEntity_.roast, roast), cursor);
    }

}
```

Now you can inject the repository into your services:

```java
@ApplicationScoped
public class CoffeeService {

    @Inject
    @JpaRepository
    private CoffeeRepository coffeeRepository;

    public List<CoffeeEntity> findAll() {
        return coffeeRepository.findAll();
    }

    public List<CoffeeEntity> findFromEthiopia() {
        return coffeeRepository.findByOrigin("Ethiopia");
    }

}
```

If you're using Lombok, you can combine the service and repository, providing repository methods directly when
interacting with the service:

```java
@ApplicationScoped
public class CoffeeService implements CoffeeRepository {

    @Inject
    @Delegate
    @JpaRepository
    private CoffeeRepository coffeeRepository;

    public List<CoffeeEntity> findFromEthiopia() {
        return findByOrigin("Ethiopia");
    }

}
```

Note that this example, extended with a roaster and tasting notes, can be found in the library's tests.

## Sorting

A `Sort` is an ordered list of `SortCriterion`, each made of a property name and a direction. It is built from a
comma separated string, a property being prefixed with `-` for a descending order:

```java
Sort sort = Sort.parse("-price,name");
```

The identifier of the entity is always appended by the repository, so that the ordering stays unique and the
pagination therefore stable, whatever is requested. By default, every attribute of the entity can be sorted on;
override `searchableProperties()` in your repository to restrict which properties the API consumers may reach and to
decouple their public naming from the entity one — an extension built on top, such as the RSQL filtering of
[rsql-jpa-repository](https://github.com/chavaillaz/rsql-jpa-repository), shares this very same map — and
`getDefaultOrders()` to change the ordering applied when none is requested:

```java
@Override
protected Map<String, String> searchableProperties() {
    return Map.of(
            "name", "name",
            "roaster", "roaster.name");
}

@Override
protected List<Order> getDefaultOrders(CriteriaBuilder criteriaBuilder, Root<CoffeeEntity> root) {
    return List.of(criteriaBuilder.asc(root.get(CoffeeEntity_.name)));
}
```

A `SortCriterion` built from a hardcoded property name in your own repository code, such as in `findStrongest()`
above, can instead be built from the JPA static metamodel, so that renaming the attribute fails the build instead of
silently misbehaving at runtime:

```java
Sort.of(SortCriterion.desc(CoffeeEntity_.strength));
Sort.of(SortCriterion.asc(CoffeeEntity_.roaster, RoasterEntity_.name)); // a nested property
```

This overload needs the given attribute to already be initialized: a JPA provider only populates a generated static
metamodel field the first time it bootstraps a persistence unit mapping the owning entity, so call it from code that
runs after that bootstrap — a repository method, not a static initializer.

It also keeps working when `searchableProperties()` restricts the entity: the resolved path is accepted as long as
it is the target of some declared property, under whatever public alias — `roaster.name` above is accepted because
`roaster` maps to it, not because it is spelled out. A metamodel path that is the target of no declared property is
still rejected, exactly as its public name would be.

Sorting on a nested property navigates the association with a left join, reused across the criteria reaching the
same association: an entity whose association on the path is `null`, such as a coffee with no roaster, is still
returned and still counted, the database placing it first or last depending on its own null ordering. Cursor
pagination is stricter, as it always is: a `null` key has no position to seek from, so scrolling on a nested
property backed by an optional association is rejected rather than silently skipped.

The `getDefaultOrders()` hook is given the raw `CriteriaBuilder` and `Root`, so a default ordering on a nested
property is up to you: `root.get("roaster").get("name")` is an implicit *inner* join and drops the entities with no
roaster, `root.join("roaster", LEFT).get("name")` keeps them.

## Pagination

Two pagination styles are available, sharing the very same `Sort`.

### Offset pagination

A `Pageable` requests a page number and size; the corresponding `PaginationResult` carries the items of the page
together with the total number of items and pages:

```java
Pageable pageable = Pageable.of(0, 20, Sort.parse("-price"));
PaginationResult<CoffeeEntity> page = coffeeRepository.findAll(pageable);
```

`Pageable.UNPAGED` (or the `page`/`size` overloads with a `null`) returns every matching entity as a single page,
still following the requested ordering.

### Cursor (keyset) pagination

A `Cursor` requests a page by seeking to the ordering keys of the previous page's last row, instead of skipping the
preceding ones with an offset. It is the preferred style for the endpoints walking a large or a frequently updated
collection: unlike an offset, a keyset page stays stable when rows are inserted or deleted in between, and no total
count needs to be computed.

```java
CursorResult<CoffeeEntity> first = coffeeRepository.findAll(Cursor.first(20, Sort.parse("-price")));
CursorResult<CoffeeEntity> next = coffeeRepository.findAll(Cursor.of(first.next(), 20, Sort.parse("-price")));
```

The `next` and `previous` tokens returned in a `CursorResult` are opaque: send them back as is to navigate, never
build or parse them yourself. A token is bound to the ordering it was issued for and is rejected if replayed on
another one. Every ordering key must be a non-nullable, non-collection attribute of a supported type (the primitive
wrapper types, `String`, `UUID`, the `java.time` types, `Date`, `BigDecimal` and `BigInteger`, and enums).

### Walking every entity

`streamAll()` drives the cursor pagination for you, returning a lazy `Stream` fetching a page at a time instead of
loading everything at once:

```java
coffeeRepository.streamAll(Sort.parse("name"), 100).forEach(this::process);
```

A short-circuiting operation such as `limit` or `findFirst` only fetches the pages it actually needs. The stream
must be consumed within the very same transaction it was obtained from — like `Query#getResultStream()`, it keeps
querying the persistence context as it is pulled from, so collect it eagerly (`toList()`) before returning it out of
a transactional method.

Only the fetching is lazy, not the retention: every entity walked stays managed until the transaction ends, so
walking a whole large table still grows the heap the way loading it at once would. When the point is to avoid
holding the rows in memory, clear the persistence context periodically or walk the table with a stateless session.

## Filtering

A `Restriction`, from `org.hibernate.query.restriction`, is checked at compile time against the JPA static
metamodel:

```java
search(Restriction.equal(CoffeeEntity_.origin, origin));
search(Restriction.greaterThan(CoffeeEntity_.strength, strength), pageable);
```

Combine several restrictions with `Criteria.anyOf` or `Criteria.allOf`:

```java
search(Criteria.anyOf(
        Criteria.of(Restriction.equal(CoffeeEntity_.roast, roast)),
        Criteria.of(Restriction.greaterThan(CoffeeEntity_.strength, strength))));
```

A `Criteria` also expresses what a `Restriction` cannot, such as a correlated subquery:

```java
public static Criteria<CoffeeEntity> tasting(String flavour) {
    return Criteria.exists(TastingNoteEntity.class, TastingNoteEntity_.COFFEE,
            (builder, note) -> builder.equal(builder.lower(note.get(TastingNoteEntity_.flavour)), flavour.toLowerCase()));
}
```

For dynamic filtering exposed to the API consumers as query strings, such as `origin==Ethiopia;strength=gt=5`, see
the [rsql-jpa-repository](https://github.com/chavaillaz/rsql-jpa-repository) extension, built on the very same
`searchableProperties()` and ordering rules.

## Existence and bulk operations

`exists` answers whether anything matches without hydrating an entity: the database stops at the first row and
only a literal is selected, so it beats both `count(...) > 0` and `first(...).isPresent()`:

```java
protected boolean existsFromOrigin(String origin) {
    return exists(Restriction.equal(CoffeeEntity_.origin, origin));
}
```

`deleteAll(restriction)` deletes every matching entity in a single statement, and returns how many were deleted.
Being a bulk deletion, it is performed by the database and therefore does **not** cascade, does not honour
`orphanRemoval`, does not run the `@PreRemove` callbacks and leaves the already loaded entities in the persistence
context. Use `deleteAll(entities)` or `deleteAllById(ids)` when any of that matters — both load the entities first
and delete them one by one, exactly as `delete` does. A bulk deletion also has no `from` clause to join, so
restrict on the attributes of the entity itself, or use a subquery.

`saveAllInBatches(entities)` is for the bulk loads a plain `saveAll` cannot hold in memory: it flushes and clears
the persistence context every `saveBatchSize()` entities, which keeps both the memory and the dirty checking
bounded, and lets `hibernate.jdbc.batch_size` group the statements. Clearing detaches **every** entity of the
persistence context, not only the saved ones, so call it from a method that holds nothing else.

## Locking

`lock` re-attaches a detached entity and refreshes its state under a pessimistic write lock, so that any concurrent
change is taken into account:

```java
CoffeeEntity coffee = coffeeRepository.getById(id);
coffeeRepository.lock(coffee);
```

## Contributing

If you have a feature request or found a bug, you can:

- Write an issue
- Create a pull request

If you want to contribute then

- Please write tests covering all your changes
- Ensure you didn't break the build by running `mvn test`
- Fork the repo and create a pull request

## License

This project is under Apache 2.0 License.
