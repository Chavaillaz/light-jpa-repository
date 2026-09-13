/**
 * Building blocks the {@link com.chavaillaz.jakarta.persistence.repository.AbstractRepository JPA repository
 * base class} composes its queries from.
 * <p>
 * {@link com.chavaillaz.jakarta.persistence.repository.Pageable} and
 * {@link com.chavaillaz.jakarta.persistence.repository.Cursor} carry the two pagination requests a repository
 * accepts, and {@link com.chavaillaz.jakarta.persistence.repository.Sort} the ordering shared by both, none of them
 * depending on Hibernate. {@link com.chavaillaz.jakarta.persistence.repository.SortCriterion} also accepts
 * attributes of the JPA static metamodel, so that a rename of the attribute fails the build.
 * <p>
 * {@link com.chavaillaz.jakarta.persistence.repository.EntityOrdering} and
 * {@link com.chavaillaz.jakarta.persistence.repository.EntityQueries} translate these requests into criteria
 * queries, naming the attributes through {@link com.chavaillaz.jakarta.persistence.repository.AttributePaths} and
 * building the clauses of the cursor pagination with {@link com.chavaillaz.jakarta.persistence.repository.Keysets}.
 * They are implementation details of {@code AbstractRepository}, whose {@code protected} methods are what a
 * repository is written with, and what the RSQL filtering of the sibling {@code rsql-jpa-repository} artifact is
 * built on.
 * <p>
 * A cursor token is encoded by a {@link com.chavaillaz.jakarta.persistence.repository.CursorCodec},
 * {@link com.chavaillaz.jakarta.persistence.repository.Base64CursorCodec} by default, and each of its keys by a
 * {@link com.chavaillaz.jakarta.persistence.repository.CursorKeyCodec}, whose default delegates to
 * {@link com.chavaillaz.jakarta.persistence.repository.CursorValues}; both are overridable per repository.
 * <p>
 * The package is {@link org.jspecify.annotations.NullMarked}: every type is non-null unless explicitly annotated
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package com.chavaillaz.jakarta.persistence.repository;

import org.jspecify.annotations.NullMarked;
