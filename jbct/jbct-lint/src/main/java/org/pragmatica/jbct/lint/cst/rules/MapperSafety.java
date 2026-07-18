package org.pragmatica.jbct.lint.cst.rules;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;


/// Shared detection helpers for the mapper-safety / totality rule family (JBCT-TOT-*, #486).
///
/// The family targets the #483 incident class: a partial operation reached from inside a
/// carrier mapper (`map`/`flatMap`/`filter`/`replaceResult`/`fold`) throws, the throw is
/// swallowed by the async machinery, and the `Promise` hangs forever. The fix is to make the
/// mapper total or lift the failure to a typed `Cause`.
///
/// Two rules share this catalog: [CstPartialOperationMapperRule] (R-A, inline lambdas) and
/// [CstMapperMethodReferenceRule] (R-B, method references resolved within the compilation unit).
sealed interface MapperSafety permits MapperSafety.unused {
    record unused() implements MapperSafety {}

    /// Carrier combinators whose lambda / method-ref argument sits in "mapper position".
    Set<String> MAPPER_METHODS = Set.of("map", "flatMap", "filter", "replaceResult", "fold");

    /// The subset of [#MAPPER_METHODS] whose names collide with `java.util.stream.Stream`.
    /// Only these need the Stream-pipeline exemption; `replaceResult` / `fold` are carrier-only.
    Set<String> STREAM_SHARED = Set.of("map", "flatMap", "filter");

    /// Syntactic markers that a chain is a `Stream` pipeline rather than a carrier chain.
    /// Used to suppress false positives on the JBCT Iteration pattern (`.stream()...toList()`).
    Set<String> STREAM_MARKERS = Set.of(".stream()",
                                        ".parallelStream()",
                                        ".toList()",
                                        ".collect(",
                                        ".forEach(",
                                        ".mapToInt(",
                                        ".mapToObj(",
                                        ".mapToLong(",
                                        ".mapToDouble(",
                                        ".boxed()",
                                        ".toArray(",
                                        ".sorted(",
                                        ".distinct(",
                                        "Stream.of(",
                                        "Stream.iterate(",
                                        "Collectors.",
                                        "IntStream.",
                                        "LongStream.",
                                        "DoubleStream.");

    /// Partial operations — each can throw on an empty / null / out-of-range container.
    /// `throw` is the explicit form; the rest are the JDK / carrier partial accessors named in #486.
    Pattern PARTIAL_OP = Pattern.compile("\\bthrow\\b"
                                         + "|\\.getFirst\\s*\\(\\s*\\)"
                                         + "|\\.getLast\\s*\\(\\s*\\)"
                                         + "|\\.get\\s*\\(\\s*\\d+\\s*\\)"
                                         + "|\\.get\\s*\\(\\s*\\)"
                                         + "|\\.orElseThrow\\s*\\("
                                         + "|\\.iterator\\s*\\(\\s*\\)\\s*\\.next\\s*\\(\\s*\\)");

    /// Java single-line and text-block string literals — blanked before scanning so a partial-op
    /// or stream marker never matches inside a literal.
    Pattern STRING_LITERAL = Pattern.compile("\"\"\"(?:[^\"\\\\]|\\\\.|\"(?!\"\"))*\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    /// Line (`//`) and block (`/* */`) comments — blanked before scanning so a marker or guard
    /// mentioned only in a comment is never mistaken for code.
    Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    /// True when the given code fragment contains a partial operation (strings / comments ignored).
    static boolean containsPartialOperation(String code) {
        return PARTIAL_OP.matcher(blankNonCode(code))
                         .find();
    }

    /// True when the enclosing chain is a `Stream` pipeline (carries a collector / source marker).
    ///
    /// Known false negative: a Stream marker anywhere in the scanned fragment exempts even when the
    /// carrier mapper is a *different* sub-chain of the same statement (markers are matched
    /// statement-wide, not per-chain). `@SuppressWarnings("JBCT-TOT-01")` is the escape hatch.
    static boolean isStreamPipeline(String enclosing) {
        var code = blankNonCode(enclosing);

        return STREAM_MARKERS.stream()
                             .anyMatch(code::contains);
    }

    /// Name of the call whose argument list directly encloses the offset `start` in `source`,
    /// or `none` when the position is not a call argument. Scans backward balancing brackets so it
    /// works for both the sole-argument form (`map(<lambda>`) and a later argument (`fold(f, <lambda>`).
    static Option<String> enclosingCallName(String source, int start) {
        var depth = 0;

        for (var i = start - 1; i >= 0; i--) {
            var c = source.charAt(i);

            if (c == ')' || c == ']' || c == '}') {
                depth++;
                continue;
            }

            if (c == '(' || c == '[' || c == '{') {
                if (depth > 0) {
                    depth--;
                    continue;
                }

                return c == '('
                       ? identifierBefore(source, i)
                       : Option.none();
            }

            if (depth == 0 && c == ';') {
                return Option.none();
            }
        }

        return Option.none();
    }

    /// Identifier immediately preceding the `(` at `parenIdx` (skipping whitespace), or `none`.
    private static Option<String> identifierBefore(String source, int parenIdx) {
        var end = parenIdx - 1;

        while (end >= 0 && Character.isWhitespace(source.charAt(end))) {
            end--;
        }

        var startIdx = end;

        while (startIdx >= 0 && isIdentifierChar(source.charAt(startIdx))) {
            startIdx--;
        }

        return startIdx == end
               ? Option.none()
               : Option.some(source.substring(startIdx + 1, end + 1));
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /// String- and comment-blanked view of `code`: every literal and comment character becomes a
    /// space (newlines preserved) so scans see only executable code and offsets/lines still align.
    /// Strings are blanked first so a `//` or `/* */` inside a literal is not treated as a comment.
    static String blankNonCode(String code) {
        return blankComments(blankStrings(code));
    }

    /// Replaces every string-literal character with a space (newlines preserved).
    static String blankStrings(String code) {
        return blankMatches(code, STRING_LITERAL.matcher(code));
    }

    /// Replaces every comment character with a space (newlines preserved).
    static String blankComments(String code) {
        return blankMatches(code, COMMENT.matcher(code));
    }

    private static String blankMatches(String code, Matcher matcher) {
        var sb = new StringBuilder(code);

        while (matcher.find()) {
            for (var i = matcher.start(); i < matcher.end(); i++) {
                var c = sb.charAt(i);

                if (c != '\n' && c != '\r') {
                    sb.setCharAt(i, ' ');
                }
            }
        }

        return sb.toString();
    }

    /// Number of line breaks in `code` before offset `end` — added to a node's start line to
    /// locate a regex match on an exact source line.
    static int newlinesBefore(String code, int end) {
        var count = 0;

        for (var i = 0; i < end && i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                count++;
            }
        }

        return count;
    }
}
