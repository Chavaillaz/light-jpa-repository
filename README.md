# Light JPA Repository

![Quality Gate](https://github.com/chavaillaz/light-jpa-repository/actions/workflows/sonarcloud.yml/badge.svg)
![Dependency Check](https://github.com/chavaillaz/light-jpa-repository/actions/workflows/snyk.yml/badge.svg)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/light-jpa-repository/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/light-jpa-repository)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Library to help implementing JPA based (Java Persistence API) repositories.

## Installation

The dependency is available in maven central (see badge for version):

```xml
<dependency>
    <groupId>com.chavaillaz</groupId>
    <artifactId>light-jpa-repository</artifactId>
</dependency>
```

## Usage

The library's goal is to provide the following essential methods for your repositories (`Repository` interface):

- **`findAll`**: Retrieves all entities from the repository.
- **`getById`** and **`findById`**: Fetches an entity by its identifier.
- **`lock`**: Applies a pessimistic lock to an entity.
- **`getReference`**: Retrieves a reference to an entity, with its state lazily fetched.
- **`save`**: Persists an entity.
- **`delete`**: Removes an entity.

As a lightweight solution, it doesn't generate other queries like more heavyweight frameworks such as Spring Data.

To use the library, ensure that your entities implement the `Identifiable` interface to enable retrieval of their
primary key. Next, define an interface that extends the `Repository` interface and add any custom methods needed for
your entity management. Then, create an implementation of this interface that extends the `AbstractRepository` class and
provides implementations for your custom methods.

Once the repository is implemented, it’s ready for use. If you're working in a CDI environment, you can annotate the 
repository with `@JpaRepository` and `@ApplicationScoped` to enable automatic injection into your services.

## Example

Taking the example of an application entity, implementing the `Identifiable` interface:

```java
@Entity
@Table(name = "APPLICATION")
public class ApplicationEntity implements Identifiable<Long>, Serializable {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    @Column(name = "ID", nullable = false)
    private Long id;

    @Column(name = "NAME", nullable = false)
    private String name;

    @Column(name = "REFERENCE", nullable = false)
    private String reference;

    @Column(name = "STATUS", nullable = false)
    private String status;
    
    ...
```

We can now create the repository with, for example, a custom method:

```java
public interface ApplicationRepository extends Repository<ApplicationEntity, Long> {

    Optional<ApplicationEntity> findByReference(String reference);

}
```

Next, implement the repository by extending the `AbstractRepository` class:

```java
@Transactional
@JpaRepository
@ApplicationScoped
public class ApplicationRepositoryJpa extends AbstractRepository<ApplicationEntity, Long> implements ApplicationRepository {

    @Inject
    public ApplicationRepositoryJpa(EntityManager entityManager) {
        super(ApplicationEntity.class, entityManager);
    }

    @Override
    public Optional<ApplicationEntity> findByReference(String reference) {
        return getEntityManager()
                .createQuery("""
                        SELECT application
                        FROM ApplicationEntity application
                        WHERE application.reference = :reference
                        """, entityType)
                .setParameter("reference", reference)
                .getResultStream()
                .findFirst();
    }

}
```

Now you can inject the repository into your services:

```java
@ApplicationScoped
public class ApplicationService {

    @Inject
    @JpaRepository
    private ApplicationRepository applicationRepository;
    
    public List<ApplicationEntity> findAll() {
        return applicationRepository.findAll();
    }

    public void decommission(String reference) {
        applicationRepository.findByReference(reference)
                .ifPresent(app -> app.setStatus("DECOMMISSIONED"));
    }

}
```

If you're using Lombok, you can combine the service and repository, providing repository methods directly when
interacting with the service:

```java
@ApplicationScoped
public class ApplicationService implements ApplicationRepository {

    @Inject
    @Delegate
    @JpaRepository
    private ApplicationRepository applicationRepository;
    
    public void decommission(String reference) {
        findByReference(reference)
                .ifPresent(app -> app.setStatus("DECOMMISSIONED"));
    }

}
```

Note that this example can be found in the library's tests.

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