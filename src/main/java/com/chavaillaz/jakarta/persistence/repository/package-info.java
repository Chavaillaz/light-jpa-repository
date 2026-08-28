/**
 * Building blocks the {@link com.chavaillaz.jakarta.persistence.repository.AbstractRepository JPA repository
 * base class} composes its queries from, so that each concern stays isolated and testable on its own.
 * <p>
 * {@link com.chavaillaz.jakarta.persistence.repository.Pageable} and
 * {@link com.chavaillaz.jakarta.persistence.repository.Cursor} carry the two pagination requests a repository
 * accepts, {@link com.chavaillaz.jakarta.persistence.repository.Sort} the ordering shared by both; none of the
 * three depends on Hibernate or needs a live persistence context, so they stay usable and testable on their own.
 * {@link com.chavaillaz.jakarta.persistence.repository.SortCriterion} additionally accepts attributes of the JPA
 * static metamodel instead of a plain property name, so that a rename of the underlying attribute fails the build
 * instead of silently misbehaving at runtime; that overload alone needs a persistence unit mapping the entity to
 * have already been bootstrapped once, since that is what populates the generated metamodel fields it reads.
 * <p>
 * {@link com.chavaillaz.jakarta.persistence.repository.EntityOrdering},
 * {@link com.chavaillaz.jakarta.persistence.repository.EntityQueries} and
 * {@link com.chavaillaz.jakarta.persistence.repository.RsqlQueries} are the actual plumbing, translating the
 * requests above into JPA criteria queries; they are considered implementation details of
 * {@code AbstractRepository} rather than a public API, and are documented for the maintainers of this package
 * rather than for the authors of a repository, who are only expected to use the {@code protected} methods
 * {@code AbstractRepository} exposes.
 * <p>
 * The keyset (cursor) pagination additionally relies on {@link com.chavaillaz.jakarta.persistence.repository.Keysets}
 * to build the seek predicate and the {@code ORDER BY} clause from a resolved ordering, and on
 * {@link com.chavaillaz.jakarta.persistence.repository.CursorCodec} to turn the boundary keys into the opaque
 * token exposed to the API consumers, {@link com.chavaillaz.jakarta.persistence.repository.Base64CursorCodec}
 * being the default, overridable implementation.
 * <p>
 * The package is {@link org.jspecify.annotations.NullMarked}: every type is non-null unless explicitly annotated
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package com.chavaillaz.jakarta.persistence.repository;

import org.jspecify.annotations.NullMarked;

