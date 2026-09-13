package com.chavaillaz.jakarta.persistence.repository;

import static java.nio.charset.StandardCharsets.UTF_8;

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
     *
     * @param value The key to encode
     * @return The corresponding encoded value
     */
    protected static String encodeValue(String value) {
        return ENCODER.encodeToString(value.getBytes(UTF_8));
    }

    /**
     * Decodes a single key encoded by {@link #encodeValue(String)}.
     *
     * @param value The encoded value to decode
     * @return The corresponding key
     */
    protected static String decodeValue(String value) {
        return new String(DECODER.decode(value), UTF_8);
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
