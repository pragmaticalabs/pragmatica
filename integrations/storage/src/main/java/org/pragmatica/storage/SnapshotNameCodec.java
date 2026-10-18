package org.pragmatica.storage;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.success;


/// Percent-encoding of ref names for the snapshot text format (#2257).
///
/// A ref name is user data: any `String`, including the empty one, `=`, `|`, `#`, newlines and
/// non-ASCII. The snapshot is line-oriented text with `|` as the field separator and `#`/`=` meaning
/// something to a reader, so a name is written as a token that contains none of them:
/// - `A-Z a-z 0-9 - . _ ~ / :` stand for themselves (cursor and path-like refs stay readable);
/// - every other code point is its UTF-8 bytes, each as `%XX` (uppercase hex);
/// - a code point with no UTF-8 form -- a lone surrogate, which a Java `String` can hold -- is
///   `%uXXXX`, so even that name round-trips exactly.
/// The empty name encodes to the empty token. Decoding accepts only tokens of that shape; anything else
/// (a raw separator, a truncated escape, a `%XX` run that is not UTF-8) is a failure, never a different name.
sealed interface SnapshotNameCodec {
    HexFormat PERCENT_HEX = HexFormat.of().withPrefix("%").withUpperCase();
    HexFormat HEX = HexFormat.of();
    Cause MALFORMED_TOKEN = Causes.cause("Malformed snapshot name token");

    /// A loop, not a stream or a regex: a name is arbitrary user data and may be megabytes long, so
    /// neither the call depth nor the allocation per character may grow with its length.
    static String encode(String name) {
        return appendEncoded(new StringBuilder(name.length()), name).toString();
    }

    /// Appends the encoding of `name`. A literal character is appended as it is, so a name with nothing
    /// to escape costs one pass and no allocation per character.
    static StringBuilder appendEncoded(StringBuilder sb, String name) {
        var index = 0;

        while (index < name.length()) {
            var codePoint = name.codePointAt(index);

            index += Character.charCount(codePoint);
            if (isLiteral(codePoint)) {
                sb.append((char) codePoint);
            } else {
                sb.append(escape(codePoint));
            }
        }

        return sb;
    }

    /// True when `token` is well formed and decodes to exactly `expected`. A token with no escape is compared
    /// as it is; one with escapes is decoded.
    static boolean decodesTo(String token, String expected) {
        if (token.indexOf('%') < 0) {
            return isWellFormed(token) && token.equals(expected);
        }

        return decode(token).map(expected::equals)
                     .or(false);
    }

    static Result<String> decode(String encoded) {
        return success(encoded).filter(MALFORMED_TOKEN, SnapshotNameCodec::isWellFormed)
                      .flatMap(SnapshotNameCodec::decodeWellFormed);
    }

    private static String escape(int codePoint) {
        return isLoneSurrogate(codePoint)
               ? "%u" + HEX.withUpperCase().toHexDigits((char) codePoint)
               : PERCENT_HEX.formatHex(Character.toString(codePoint).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isLiteral(int codePoint) {
        return codePoint < 0x80 && (Character.isLetterOrDigit(codePoint) || "-._~/:".indexOf(codePoint) >= 0);
    }

    private static boolean isLoneSurrogate(int codePoint) {
        return codePoint <= Character.MAX_VALUE && Character.isSurrogate((char) codePoint);
    }

    /// Linear scan of the token grammar: literal | `%XX` | `%uXXXX`. No backtracking and no recursion,
    /// so the cost and the stack depth are independent of the length of the name.
    private static boolean isWellFormed(String encoded) {
        var index = 0;

        while (index < encoded.length()) {
            var width = tokenWidth(encoded, index);

            if (width == 0) {
                return false;
            }

            index += width;
        }

        return true;
    }

    /// Characters the token at `index` spans; 0 when it is not a valid token.
    private static int tokenWidth(String encoded, int index) {
        if (encoded.charAt(index) != '%') {
            return isLiteral(encoded.charAt(index))
                   ? 1
                   : 0;
        }

        if (startsUnitEscape(encoded, index)) {
            return escapeWidth(hasHexDigits(encoded, index + 2, 4), 6);
        }

        return escapeWidth(hasHexDigits(encoded, index + 1, 2), 3);
    }

    private static int escapeWidth(boolean valid, int width) {
        return valid
               ? width
               : 0;
    }

    private static boolean startsUnitEscape(String encoded, int index) {
        return index + 1 < encoded.length() && encoded.charAt(index + 1) == 'u';
    }

    private static boolean hasHexDigits(String encoded, int from, int count) {
        if (from + count > encoded.length()) {
            return false;
        }

        for (var i = from; i < from + count; i++) {
            if (!isHexDigit(encoded.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    private static boolean isHexDigit(int c) {
        return Character.digit(c, 16) >= 0 && c < 0x80;
    }

    /// Strict UTF-8 per run of literals and `%XX`; a `%uXXXX` ends a run and is a single UTF-16 unit.
    /// A malformed run fails instead of decoding to replacement characters.
    private static Result<String> decodeWellFormed(String encoded) {
        var out = new StringBuilder(encoded.length());
        var run = new ByteArrayOutputStream(encoded.length());
        var index = 0;

        while (index < encoded.length()) {
            if (encoded.charAt(index) != '%') {
                run.write(encoded.charAt(index));
                index++;
            } else if (startsUnitEscape(encoded, index)) {
                if (!appendStrictUtf8(run, out)) {
                    return MALFORMED_TOKEN.result();
                }

                out.append((char) HexFormat.fromHexDigits(encoded, index + 2, index + 6));
                index += 6;
            } else {
                run.write(HexFormat.fromHexDigits(encoded, index + 1, index + 3));
                index += 3;
            }
        }

        return appendStrictUtf8(run, out)
               ? success(out.toString())
               : MALFORMED_TOKEN.result();
    }

    /// Decodes and clears `run` into `out`; false when it is not valid UTF-8. The `CoderResult` form of the
    /// decoder reports malformed input as a value, so no exception is thrown or caught.
    private static boolean appendStrictUtf8(ByteArrayOutputStream run, StringBuilder out) {
        var bytes = run.toByteArray();
        var decoded = CharBuffer.allocate(bytes.length);
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
        var result = decoder.decode(ByteBuffer.wrap(bytes), decoded, true);

        run.reset();
        if (result.isError() || decoder.flush(decoded).isError()) {
            return false;
        }

        decoded.flip();
        out.append(decoded);

        return true;
    }

    record unused() implements SnapshotNameCodec {}
}
