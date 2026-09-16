package com.chavaillaz.jakarta.persistence.repository;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Database the Hibernate tests run against, chosen with the {@code database} system property: the in-memory H2 of
 * {@code hibernate.properties} by default, or {@code postgresql}, {@code sqlserver} or {@code oracle}, started in a
 * container once for the whole run, so that what the databases disagree on is exercised on the databases themselves.
 */
final class TestDatabase {

    /**
     * The database the tests run against, as the {@code database} system property names it.
     */
    static final String NAME = System.getProperty("database", "h2");

    private static @Nullable JdbcDatabaseContainer<?> container;

    private TestDatabase() {
        // This utility class should not be instantiated
    }

    /**
     * Gets the connection settings to apply over {@code hibernate.properties}, starting the container the first
     * time, Testcontainers stopping it once the tests are over.
     *
     * @return The settings reaching the selected database, none for H2
     * @throws IllegalArgumentException if the selected database is none of the supported ones
     */
    static synchronized Map<String, Object> settings() {
        if ("h2".equals(NAME)) {
            return Map.of();
        }
        if (container == null) {
            container = switch (NAME) {
                case "postgresql" -> new PostgreSQLContainer("postgres:18-alpine");
                case "sqlserver" -> new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();
                case "oracle" -> new OracleContainer("gvenzl/oracle-free:23-slim-faststart");
                default -> throw new IllegalArgumentException("Unknown test database " + NAME + ", expected h2, postgresql, sqlserver or oracle");
            };
            container.start();
        }
        return Map.of(
                "hibernate.connection.driver_class", container.getDriverClassName(),
                "hibernate.connection.url", container.getJdbcUrl(),
                "hibernate.connection.username", container.getUsername(),
                "hibernate.connection.password", container.getPassword());
    }

}
