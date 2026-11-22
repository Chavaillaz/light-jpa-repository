/**
 * Repositories built on Jakarta Persistence and Hibernate, with the usual CRUD operations, offset and cursor
 * pagination, sorting and filtering.
 * <ul>
 *     <li>{@link com.chavaillaz.jakarta.persistence}: the contract every entity a repository manages shares</li>
 *     <li>{@link com.chavaillaz.jakarta.persistence.repository}: the repositories, and the pagination, sorting and
 *     filtering requests they take</li>
 * </ul>
 * Hibernate declares no module name: it is required by the one its jar names, {@code org.hibernate.orm.core}, and
 * transitively, the API exposing its sessions and restrictions. The warnings of javac against requiring such an
 * automatic module, and against requiring one transitively, are suppressed: Hibernate offers no other module to
 * require. The application brings Hibernate along, as it brings JSpecify, which is only required at compile time.
 */
@SuppressWarnings({"requires-automatic", "requires-transitive-automatic"})
module com.chavaillaz.jakarta.persistence {

    requires transitive jakarta.inject;
    requires transitive jakarta.persistence;
    requires transitive org.hibernate.orm.core;
    requires static transitive org.jspecify;
    requires jakarta.transaction;
    requires org.apache.commons.lang3;

    exports com.chavaillaz.jakarta.persistence;
    exports com.chavaillaz.jakarta.persistence.repository;

}
