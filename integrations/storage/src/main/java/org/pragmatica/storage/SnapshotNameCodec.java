package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
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
    Pattern ENCODED = Pattern.compile("(?:[A-Za-z0-9._~/:-]|%[0-9A-Fa-f]{2}|%u[0-9A-Fa-f]{4})*");
    Pattern TOKEN = Pattern.compile("%u([0-9A-Fa-f]{4})|((?:%[0-9A-Fa-f]{2})+)|([A-Za-z0-9._~/:-]+)");
    HexFormat PERCENT_HEX = HexFormat.of().withPrefix("%").withUpperCase();
    HexFormat HEX = HexFormat.of();
    Cause MALFORMED_TOKEN = Causes.cause("Malformed snapshot name token");

    static String encode(String name) {
        return name.codePoints()
                   .mapToObj(SnapshotNameCodec::encodeCodePoint)
                   .collect(Collectors.joining());
    }

    static Result<String> decode(String encoded) {
        return success(encoded).filter(MALFORMED_TOKEN,
                                       ENCODED.asMatchPredicate())
                      .flatMap(SnapshotNameCodec::decodeTokens);
    }

    private static String encodeCodePoint(int codePoint) {
        if (isLiteral(codePoint)) {
            return Character.toString(codePoint);
        }

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

    private static Result<String> decodeTokens(String encoded) {
        return Result.allOf(TOKEN.matcher(encoded).results().map(SnapshotNameCodec::decodeToken).toList()).map(parts -> String.join("",
                                                                                                                                    parts));
    }

    private static Result<String> decodeToken(MatchResult token) {
        if (token.group(1) != null) {
            return Number.parseInt(token.group(1),
                                   16)
                         .map(SnapshotNameCodec::unitToString);
        }

        return token.group(2) != null
               ? decodeUtf8(token.group(2))
               : success(token.group(3));
    }

    private static String unitToString(int unit) {
        return String.valueOf((char) unit);
    }

    /// Strict UTF-8: a malformed run fails instead of decoding to replacement characters.
    private static Result<String> decodeUtf8(String percentRun) {
        return Result.lift(SnapshotError.ParseFailed::new,
                           () -> StandardCharsets.UTF_8.newDecoder()
                                                       .onMalformedInput(CodingErrorAction.REPORT)
                                                       .onUnmappableCharacter(CodingErrorAction.REPORT)
                                                       .decode(ByteBuffer.wrap(HEX.parseHex(percentRun.replace("%", ""))))
                                                       .toString());
    }

    record unused() implements SnapshotNameCodec {}
}
