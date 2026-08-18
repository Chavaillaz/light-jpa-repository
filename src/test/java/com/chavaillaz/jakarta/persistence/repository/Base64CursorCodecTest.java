package com.chavaillaz.jakarta.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Base64CursorCodec")
class Base64CursorCodecTest {

    private final Base64CursorCodec codec = new Base64CursorCodec();

    @Test
    @DisplayName("round trips a forward position")
    void roundTripsAForwardPosition() {
        CursorPosition position = new CursorPosition(List.of("Yirgacheffe", "25.00", "42"), false, "cafe");

        assertThat(codec.decode(codec.encode(position))).isEqualTo(position);
    }

    @Test
    @DisplayName("round trips a backward position")
    void roundTripsABackwardPosition() {
        CursorPosition position = new CursorPosition(List.of("Geisha"), true, "1a2b3c");

        String token = codec.encode(position);

        assertThat(codec.decode(token)).isEqualTo(position);
        assertThat(codec.decode(token).backward()).isTrue();
    }

    @Test
    @DisplayName("round trips a position with no key")
    void roundTripsAnEmptyPosition() {
        CursorPosition position = new CursorPosition(List.of(), false, "cafe");

        assertThat(codec.decode(codec.encode(position))).isEqualTo(position);
    }

    @ParameterizedTest(name = "the key \"{0}\" survives the encoding")
    @ValueSource(strings = {
            "Café Crème",
            "a|b|c",
            "Blue Mountain",
            "=/+",
            "",
            "  ",
            "Ethiopia\nHarrar" })
    @DisplayName("encodes each key on its own, so that a value cannot break the token")
    void encodesEachKeyOnItsOwn(String value) {
        CursorPosition position = new CursorPosition(List.of(value, "second"), false, "cafe");

        assertThat(codec.decode(codec.encode(position))).isEqualTo(position);
    }

    @Test
    @DisplayName("produces a URL safe, unpadded token")
    void producesAUrlSafeToken() {
        String token = codec.encode(new CursorPosition(List.of("Café ~ Crème ?&="), false, "cafe"));

        assertThat(token).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("stays opaque, the keys not being readable from the token")
    void staysOpaque() {
        assertThat(codec.encode(new CursorPosition(List.of("Yirgacheffe"), false, "cafe")))
                .doesNotContain("Yirgacheffe");
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @ValueSource(strings = { "not base 64 !!", "@@@@" })
    @DisplayName("rejects a malformed token")
    void rejectsAMalformedToken(String token) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.decode(token))
                .withMessage("Malformed cursor");
    }

    @Test
    @DisplayName("rejects an empty token")
    void rejectsAnEmptyToken() {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(""));
    }

    @Test
    @DisplayName("exposes the value codec to the subclasses")
    void exposesTheValueCodec() {
        assertThat(Base64CursorCodec.decodeValue(Base64CursorCodec.encodeValue("Café"))).isEqualTo("Café");
    }

    @Test
    @DisplayName("is the default codec")
    void isTheDefaultCodec() {
        assertThat(CursorCodec.DEFAULT).isInstanceOf(Base64CursorCodec.class);
    }

}