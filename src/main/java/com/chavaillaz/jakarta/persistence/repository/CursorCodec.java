package com.chavaillaz.jakarta.persistence.repository;

/**
 * Codec of the cursor tokens, turning a {@link CursorPosition} into the opaque string exposed to the API
 * consumers and back.
 * <p>
 * The tokens are opaque by contract: their content is an implementation detail the consumers must not build nor
 * parse, so that the format can evolve. Override the default implementation to sign or encrypt them when the
 * ordering keys must not leak, or when forged positions must be rejected.
 */
public interface CursorCodec {

    /**
     * The default codec, encoding the positions as URL safe Base64.
     */
    CursorCodec DEFAULT = new Base64CursorCodec();

    /**
     * Encodes a position into the opaque token exposed to the API consumers, which they send back as is to
     * request the surrounding page.
     * <p>
     * The token must be safe to carry as a query parameter without any escaping, and it must survive the round
     * trip verbatim: the keys are formatted values that may contain any character, including the separators of
     * the format, the empty string or non ASCII text, and a single altered key silently shifts the boundary of
     * the next page.
     * <p>
     * The encoding must be deterministic, the same position always producing the same token, so that a consumer
     * replaying a token gets the very same page, and so that the tokens can be compared in the tests. It must
     * also distinguish the two directions of a same boundary row and the orderings the position was issued for,
     * both being part of the position and both changing which rows the seek predicate keeps.
     *
     * @param position The position to encode, made of the ordering keys of the boundary row, of the direction of
     *                 the navigation and of the fingerprint of the ordering
     * @return The corresponding token, never {@code null} nor blank, a blank token being what denotes the first
     * page and therefore never reaching {@link #decode(String)}
     */
    String encode(CursorPosition position);

    /**
     * Decodes a token back into the position it was {@link #encode(CursorPosition) issued for}.
     * <p>
     * The token comes straight from an API consumer, so it is untrusted input: it may be truncated, hand crafted,
     * built for another endpoint or simply left over from a previous release. Anything that is not a token this
     * codec produced must be rejected with an {@link IllegalArgumentException} rather than decoded into a
     * partial position, which would seek on meaningless keys and return an arbitrary page. Implementations
     * verifying an integrity tag reject a tampered token here as well, the fingerprint carried by the position
     * only guarding against the accidental replay across orderings, not against a forged one.
     * <p>
     * The token is never blank when this method is called, {@link Cursor#isFirst()} being checked beforehand, so
     * an implementation does not have to treat the absence of a position as a valid input.
     *
     * @param token The token returned by a previous call to {@link #encode(CursorPosition)}, as received from the
     *              consumer
     * @return The corresponding position, never {@code null}
     *
     * @throws IllegalArgumentException if the token is malformed, truncated, was not produced by this codec or
     *                                  fails its integrity check, typically surfaced as a {@code 400 Bad Request}
     *                                  by the API layer
     */
    CursorPosition decode(String token);

}