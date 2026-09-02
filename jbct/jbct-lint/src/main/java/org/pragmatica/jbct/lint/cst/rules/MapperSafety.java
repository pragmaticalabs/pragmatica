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
///
/// Public because the generic source-masking helpers ([#blankNonCode] and friends) are reused
/// outside this package by [org.pragmatica.jbct.lint.cst.shape.MethodShapeClassifier]'s phase-2
/// mutation-signal guard (#448); the rule catalog itself stays internal to this package.
public sealed interface MapperSafety permits MapperSafety.unused {
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
    /// `throw` is the explicit form; `getFirst`/`getLast`/indexed `get(<int>)` are the container
    /// partial accessors; `orElseThrow` and the Optional-evidence `.get()` shapes cover
    /// `Optional`. A bare `.get()` is NOT listed — it over-matches total getters
    /// (`Supplier.get`, `AtomicReference.get`); only receivers with Optional evidence
    /// (`Optional.x(...).get()`, `.findFirst().get()`, `.findAny().get()`) flag. Known false
    /// negative: an `Optional` held in a plain variable (`opt.get()`) escapes — acceptable; the
    /// report / `@SuppressWarnings` remain the hatch.
    Pattern PARTIAL_OP = Pattern.compile("\\bthrow\\b"
                                         + "|\\.getFirst\\s*\\(\\s*\\)"
                                         + "|\\.getLast\\s*\\(\\s*\\)"
                                         + "|\\.get\\s*\\(\\s*\\d+\\s*\\)"
                                         + "|\\.findFirst\\s*\\(\\s*\\)\\s*\\.get\\s*\\(\\s*\\)"
                                         + "|\\.findAny\\s*\\(\\s*\\)\\s*\\.get\\s*\\(\\s*\\)"
                                         + "|\\bOptional\\s*\\.\\s*\\w+\\s*\\([^;{}]*\\)\\s*\\.get\\s*\\(\\s*\\)"
                                         + "|\\.orElseThrow\\s*\\("
                                         + "|\\.iterator\\s*\\(\\s*\\)\\s*\\.next\\s*\\(\\s*\\)");

    /// Java single-line and text-block string literals — masked before op-scanning so a partial-op
    /// pattern never matches inside a literal, and so a string argument (`get("key")`) is never
    /// seen as empty / integer parens.
    ///
    /// The bulk alternatives are possessive (`++`) for stack safety (#540). The JDK compiles a
    /// quantified group containing alternation to `Loop`/`Branch`/`GroupHead`/`GroupTail` nodes
    /// whose `Loop.match` **recurses once per iteration**; with a plain `[^"\\]` each iteration
    /// consumes one character, so stack depth grew linearly with literal length and a 7481-char
    /// text block overflowed the default stack. Possessive matching makes one iteration consume a
    /// maximal RUN of ordinary characters, so depth tracks the number of `"` / `\` occurrences
    /// instead. It matches exactly the same language: every closing delimiter begins with `"`, and
    /// the sibling alternatives require `"` or `\` — all excluded from the possessive class — so
    /// stopping mid-run can never yield a match the greedy form would have found.
    Pattern STRING_LITERAL = Pattern.compile("\"\"\"(?:[^\"\\\\]++|\\\\.|\"(?!\"\"))*\"\"\"|\"(?:[^\"\\\\\\n]++|\\\\.)*\"");

    /// Line (`//`) and block (`/* */`) comments — blanked before scanning so a marker or guard
    /// mentioned only in a comment is never mistaken for code.
    Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    /// True when the given code fragment contains a partial operation.
    ///
    /// Op-scanning masks string literals with a NON-space placeholder (not blanks): collapsing a
    /// string to spaces would turn `get("key")` into `get(   )` and false-match the empty-parens
    /// shape, so string content is filled with `#` — present but non-empty and non-numeric.
    static boolean containsPartialOperation(String code) {
        return PARTIAL_OP.matcher(maskForOps(code))
                         .find();
    }

    /// True when the given chain fragment is a `Stream` pipeline (carries a collector / source
    /// marker). Callers pass the lambda-free chain *spine* (mapper lambda bodies blanked), so a
    /// `.stream()` inside the mapper's own lambda does not exempt the carrier — only markers on the
    /// pipeline structure itself count. Residual false negative: an unrelated Stream sub-expression
    /// sharing the same statement spine still exempts; `@SuppressWarnings` is the hatch.
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

    /// Like [#blankNonCode] but string literals are filled with `#` (a non-space, non-numeric
    /// placeholder) instead of spaces, so op-argument shapes see a string as a present-but-opaque
    /// argument rather than empty parens.
    static String maskForOps(String code) {
        return blankComments(fillMatches(code, STRING_LITERAL.matcher(code), '#'));
    }

    /// Replaces every string-literal character with a space (newlines preserved).
    static String blankStrings(String code) {
        return fillMatches(code, STRING_LITERAL.matcher(code), ' ');
    }

    /// Replaces every comment character with a space (newlines preserved).
    static String blankComments(String code) {
        return fillMatches(code, COMMENT.matcher(code), ' ');
    }

    private static String fillMatches(String code, Matcher matcher, char fill) {
        var sb = new StringBuilder(code);

        while (matcher.find()) {
            for (var i = matcher.start(); i < matcher.end(); i++) {
                var c = sb.charAt(i);

                if (c != '\n' && c != '\r') {
                    sb.setCharAt(i, fill);
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
