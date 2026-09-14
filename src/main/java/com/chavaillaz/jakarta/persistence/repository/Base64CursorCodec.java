package com.chavaillaz.jakarta.persistence.repository;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Default {@link CursorCodec}, encoding a position as URL safe Base64, so that a token travels as a query
 * parameter without any escaping.
 * <p>
 * Each key is encoded on its own before being joined, so that a value containing the separator cannot break the
 * token, the whole payload being then encoded once more to stay opaque.
 */
public class Base64CursorCodec implements CursorCodec {

    private static final String SEPARATOR = "|";
    private static final char FORWARD = 'f';
    private static final char BACKWARD = 'b';

    /**
     * Prefix of a key encoded as its UTF-16 code units rather than as UTF-8, outside the URL safe Base64 alphabet
     * so that no key encoded as UTF-8 starts with it.
     */
    private static final String CODE_UNITS_PREFIX = "~";

    /**
     * Compiled form of the {@link #SEPARATOR}, quoted since the pipe is a regex metacharacter.
     */
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile(Pattern.quote(SEPARATOR));

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /**
     * Creates the default codec, stateless and thread safe, therefore safely shared as {@link CursorCodec#DEFAULT}.
     */
    public Base64CursorCodec() {
        // No argument constructor to call
    }

    /**
     * Encodes a single key as URL safe Base64, so that a value carrying the separator or non ASCII text cannot
     * break the token.
     * <p>
     * A key holding an unpaired surrogate, such as a text cut in the middle of an emoji, is encoded as its UTF-16
     * code units instead: UTF-8 replaces that surrogate with a question mark, and the following page would then
     * seek from another key than the one of the boundary row, repeating or skipping rows.
     *
     * @param value The key to encode
     * @return The corresponding encoded value
     */
    protected static String encodeValue(String value) {
        if (UTF_8.newEncoder().canEncode(value)) {
            return ENCODER.encodeToString(value.getBytes(UTF_8));
        }
        ByteBuffer codeUnits = ByteBuffer.allocate(value.length() * Character.BYTES);
        codeUnits.asCharBuffer().put(value);
        return CODE_UNITS_PREFIX + ENCODER.encodeToString(codeUnits.array());
    }

    /**
     * Decodes a single key encoded by {@link #encodeValue(String)}.
     *
     * @param value The encoded value to decode
     * @return The corresponding key
     * @throws IllegalArgumentException if the value is not valid Base64, or ends in the middle of a code unit
     */
    protected static String decodeValue(String value) {
        if (!value.startsWith(CODE_UNITS_PREFIX)) {
            return new String(DECODER.decode(value), UTF_8);
        }
        byte[] codeUnits = DECODER.decode(value.substring(CODE_UNITS_PREFIX.length()));
        if (codeUnits.length % Character.BYTES != 0) {
            throw new IllegalArgumentException("Truncated code unit in cursor key");
        }
        return ByteBuffer.wrap(codeUnits).asCharBuffer().toString();
    }

    @Override
    public String encode(CursorPosition position) {
        StringBuilder payload = new StringBuilder()
                .append(position.backward() ? BACKWARD : FORWARD)
                .append(position.fingerprint());
        position.values().forEach(value -> payload.append(SEPARATOR).append(encodeValue(value)));
        return encodeValue(payload.toString());
    }

    @Override
    public CursorPosition decode(String token) {
        try {
            String[] parts = SEPARATOR_PATTERN.split(decodeValue(token), -1);
            String header = parts[0];
            char direction = header.charAt(0);
            if (direction != FORWARD && direction != BACKWARD) {
                // Not produced by this codec, and read as forward it would seek the wrong way
                throw new IllegalArgumentException("Unknown cursor direction " + direction);
            }
            return new CursorPosition(
                    Arrays.stream(parts)
                            .skip(1)
                            .map(Base64CursorCodec::decodeValue)
                            .toList(),
                    direction == BACKWARD,
                    header.substring(1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed cursor", e);
        }
    }

}
