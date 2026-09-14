package com.chavaillaz.jakarta.persistence.repository;

/**
 * Codec of the cursor tokens, turning a {@link CursorPosition} into the opaque string exposed to the API
 * consumers and back.
 * <p>
 * The tokens are opaque by contract, so that their format can evolve. Override the default implementation to sign
 * or encrypt them when the ordering keys must not leak, or when forged positions must be rejected.
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
     * The token must travel as a query parameter without escaping, and survive the round trip verbatim whatever
     * the keys hold, the empty string and the separators of the format included, as well as an unpaired surrogate,
     * which UTF-8 cannot carry. The encoding must be deterministic, and tell apart the directions and the orderings
     * a position is issued for.
     *
     * @param position The position to encode, made of the ordering keys of the boundary row, of the direction of
     *                 the navigation and of the fingerprint of the ordering
     * @return The corresponding token, never {@code null} nor blank, a blank token denoting the first page
     */
    String encode(CursorPosition position);

    /**
     * Decodes a token back into the position it was {@link #encode(CursorPosition) issued for}.
     * <p>
     * The token is untrusted input: anything this codec did not produce must be rejected rather than decoded into
     * a partial position, which would seek on meaningless keys, and so must a token failing the integrity check of
     * a signing implementation. A blank token denotes the first page and is never decoded.
     *
     * @param token The token returned by a previous call to {@link #encode(CursorPosition)}, as received from the
     *              consumer
     * @return The corresponding position, never {@code null}
     * @throws IllegalArgumentException if the token is malformed, truncated, was not produced by this codec or
     *                                  fails its integrity check, typically answered with a {@code 400 Bad Request}
     *                                  by the API layer
     */
    CursorPosition decode(String token);

}
