package org.pragmatica.storage;

import java.util.Random;

import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2257: percent-encoding of snapshot ref names. Percent-encoding over length-prefixing because the
/// snapshot stays line-oriented text (greppable by an operator, a torn file still fails on one line, no
/// byte counts to desynchronise), and it is bijective on every Java `String`.
class SnapshotNameCodecTest {
    @Test
    void encode_leavesLiteralCharacters_unchanged() {
        assertThat(SnapshotNameCodec.encode("cursors/org.example:app-1_x~y/ns:orders:1/0"))
            .isEqualTo("cursors/org.example:app-1_x~y/ns:orders:1/0");
    }

    @Test
    void encode_escapesSeparatorsAndControls() {
        assertThat(SnapshotNameCodec.encode("a=b|c#d\ne%f g")).isEqualTo("a%3Db%7Cc%23d%0Ae%25f%20g");
    }

    @Test
    void encode_emitsEmptyToken_forEmptyName() {
        assertThat(SnapshotNameCodec.encode("")).isEmpty();
    }

    @Test
    void encode_usesUtf8Bytes_forNonAscii() {
        assertThat(SnapshotNameCodec.encode("é")).isEqualTo("%C3%A9");
        assertThat(SnapshotNameCodec.encode("😀")).isEqualTo("%F0%9F%98%80");
    }

    /// U+1D800 is one supplementary code point whose low 16 bits are a surrogate value; it must be UTF-8, not a unit escape.
    @Test
    void encode_usesUtf8Bytes_forSupplementaryCodePointWithSurrogateLowBits() {
        assertThat(SnapshotNameCodec.encode("\uD836\uDC00")).isEqualTo("%F0%9D%A0%80");
    }

    @Test
    void encode_usesUnitEscape_forLoneSurrogate() {
        assertThat(SnapshotNameCodec.encode("\uD800")).isEqualTo("%uD800");
    }

    @Test
    void decode_acceptsLowercaseHex() {
        assertThat(SnapshotNameCodec.decode("%c3%a9").unwrap()).isEqualTo("é");
    }

    @Test
    void decode_returnsEmptyName_forEmptyToken() {
        assertThat(SnapshotNameCodec.decode("").unwrap()).isEmpty();
    }

    @Test
    void decode_rejectsRawSeparator() {
        SnapshotNameCodec.decode("a|b").onSuccess(SnapshotNameCodecTest::unexpected);
        SnapshotNameCodec.decode("a=b").onSuccess(SnapshotNameCodecTest::unexpected);
        SnapshotNameCodec.decode("a\nb").onSuccess(SnapshotNameCodecTest::unexpected);
        SnapshotNameCodec.decode("a b").onSuccess(SnapshotNameCodecTest::unexpected);
        SnapshotNameCodec.decode("é").onSuccess(SnapshotNameCodecTest::unexpected);
    }

    @Test
    void decode_rejectsTruncatedOrNonHexEscape() {
        SnapshotNameCodec.decode("%4").onSuccess(SnapshotNameCodecTest::unexpected);
        SnapshotNameCodec.decode("%zz").onSuccess(SnapshotNameCodecTest::unexpected);
        SnapshotNameCodec.decode("%u12").onSuccess(SnapshotNameCodecTest::unexpected);
        SnapshotNameCodec.decode("%uZZZZ").onSuccess(SnapshotNameCodecTest::unexpected);
    }

    @Test
    void decode_rejectsRunThatIsNotUtf8() {
        SnapshotNameCodec.decode("%C3").onSuccess(SnapshotNameCodecTest::unexpected);
        SnapshotNameCodec.decode("%FF%FE").onSuccess(SnapshotNameCodecTest::unexpected);
    }

    @Test
    void decode_distinguishesEscapedPercentFromUnitEscape() {
        assertThat(SnapshotNameCodec.decode("%25u0041").unwrap()).isEqualTo("%u0041");
        assertThat(SnapshotNameCodec.decode("%u0041").unwrap()).isEqualTo("A");
    }

    @Test
    void roundTrip_restoresEveryName_acrossRandomCodePoints() {
        var random = new Random(2257L);

        for (var i = 0; i < 3000; i++) {
            var name = randomName(random);

            assertThat(SnapshotNameCodec.decode(SnapshotNameCodec.encode(name)).unwrap()).isEqualTo(name);
        }
    }

    @Test
    void roundTrip_restoresDollarAndBackslash() {
        var name = "price$1\\2$&";

        assertThat(SnapshotNameCodec.decode(SnapshotNameCodec.encode(name)).unwrap()).isEqualTo(name);
    }

    private static void failed(Cause cause) {
        Assertions.fail(cause.message());
    }

    private static void unexpected(Object value) {
        Assertions.fail("Expected a failure but got: " + value);
    }

    private static String randomName(Random random) {
        var sb = new StringBuilder();
        var length = random.nextInt(12);

        for (var i = 0; i < length; i++) {
            sb.append((char) pick(random));
        }

        return sb.toString();
    }

    /// Mixes ASCII, the separators, BMP text and the whole surrogate range (so lone and paired surrogates both occur).
    private static int pick(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> "=|#\n%u$\\ ".charAt(random.nextInt(9));
            case 1 -> 0x20 + random.nextInt(0x5F);
            case 2 -> 0xD800 + random.nextInt(0x800);
            default -> 0x80 + random.nextInt(0xD700 - 0x80);
        };
    }
}
