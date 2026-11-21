// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// M-1b-delta-S (stream-mem): no production code calls a shim form of a removal refusal. Every raise site names its
/// cause, so a `CursorExpired` built in `src/main/java` never carries the `UNRECORDED` a shim passes.
///
/// A shim call is `new StreamError.CursorExpired(` or `new CursorExpired(` with 2 arguments, or with 3 whose last
/// argument is a `Reason` (its text names `reason`, as `Reason.LOST` or `reasonOfCursor()` do), and `new ReadRefusal(`
/// with 11 arguments. The canonical forms are `CursorExpired(requested, tail, reason, cause)`,
/// `CursorExpired(requested, tail, cause)` and the 12-argument `ReadRefusal`. The shim constructors themselves delegate
/// through `this(...)`, which is not a `new` call, so they never match.
///
/// Arguments are counted by balanced parentheses, brackets and braces over the source with comments and string and
/// character literals blanked, never by a line regex: a lambda argument (`x -> f(x, y)`) or a nested call
/// (`g(a, h(b, c))`) has commas of its own. A comma inside explicit type arguments (`Foo.<A, B>bar()`) is not
/// recognised and counts as a separator; no argument of these constructors is written that way today.
///
/// Scanned: every tracked `.java` file under a `/src/main/java/` directory, from `git ls-files` at the repository root,
/// never a directory walk (a `target/` copy would match one).
class CursorExpiredShimCallersTest {
    private static final Pattern RAISE = Pattern.compile("\\bnew\\s+(?:StreamError\\s*\\.\\s*)?(CursorExpired|ReadRefusal)\\s*\\(");
    private static final Pattern REASON = Pattern.compile("(?i)reason");
    private static final String MAIN_SOURCES = "/src/main/java/";
    private static final String RING_BUFFER = "aether/aether-stream/src/main/java/org/pragmatica/aether/stream/OffHeapRingBuffer.java";

    @Test
    void noProductionSourceCallsAShimForm() throws IOException {
        var root = repositoryRoot();
        var files = mainSources(root);
        var hits = new ArrayList<String>();
        files.forEach(file -> hits.addAll(shimCalls(file, read(root, file))));

        System.out.println("CursorExpiredShimCallersTest scanned " + files.size() + " main source files");

        assertThat(hits).as("shim-form raise sites in " + files.size() + " main source files: use the canonical "
                            + "constructor `CursorExpired(requested, tail, reason, cause)` or `(requested, tail, cause)` "
                            + "(and the 12-argument `ReadRefusal`), naming the removal's cause")
                        .isEmpty();
    }

    @Test
    void realScan_coversTheMainSources_includingTheRingBuffer() throws IOException {
        var files = mainSources(repositoryRoot());

        assertThat(files).as("the scan covers the repository's main sources (%d files)", files.size())
                         .hasSizeGreaterThan(1000)
                         .contains(RING_BUFFER);
    }

    @Test
    void scanner_countsEachShimForm_andNoCanonicalForm() {
        var shims = """
            var a = new StreamError.CursorExpired(offset, tail);
            var b = new CursorExpired(offset, tail, Reason.LOST);
            var c = new ReadRefusal(kind, stream, partition, first, second, flag, detail, peerFirst, peerSecond, e1, e2);
            """;
        var canonical = """
            var a = new StreamError.CursorExpired(offset, tail, Reason.LOST, RemovalCause.RING_FROZEN);
            var b = new CursorExpired(offset, tail, RemovalCause.RETENTION);
            var c = new ReadRefusal(kind, stream, partition, first, second, flag, detail, peerFirst, peerSecond, e1, e2,
                                    RemovalCause.AGE);
            """;

        assertThat(shimCalls("Shims.java", shims)).as("one hit per shim form")
                                                  .containsExactly("Shims.java:1", "Shims.java:2", "Shims.java:3");
        assertThat(shimCalls("Canonical.java", canonical)).as("canonical forms")
                                                          .isEmpty();
    }

    @Test
    void scanner_countsArgumentsByBalancedParentheses_notByCommas() {
        var canonical = "var a = new CursorExpired(x -> f(x, y), g(a, h(b, c)), RemovalCause.AGE);";
        var shim = "var b = new StreamError.CursorExpired(g(a, h(b, c)), x -> f(x, y));";

        assertThat(shimCalls("Lambda.java", canonical)).as("3 arguments: a lambda, a nested call and a cause")
                                                       .isEmpty();
        assertThat(shimCalls("Nested.java", shim)).as("2 arguments: a nested call and a lambda")
                                                  .containsExactly("Nested.java:1");
    }

    @Test
    void scanner_ignoresCommentsAndLiterals() {
        var source = """
            /// new CursorExpired(offset, tail) in a doc comment
            // new CursorExpired(offset, tail) in a line comment
            /* new CursorExpired(offset, tail) in a block comment */
            var s = "new CursorExpired(offset, tail)";
            var a = new CursorExpired(offset, tail, cause(","));
            """;

        assertThat(shimCalls("Comments.java", source)).isEmpty();
    }

    private static List<String> shimCalls(String file, String source) {
        var code = blankCommentsAndLiterals(source);
        var matcher = RAISE.matcher(code);
        var hits = new ArrayList<String>();
        while (matcher.find()) {
            var arguments = arguments(code, matcher.end());
            if (isShim(matcher.group(1), arguments)) {
                hits.add(file + ":" + lineOf(code, matcher.start()));
            }
        }
        return hits;
    }

    private static boolean isShim(String type, List<String> arguments) {
        return type.equals("ReadRefusal")
               ? arguments.size() == 11
               : arguments.size() == 2 || (arguments.size() == 3 && REASON.matcher(arguments.getLast()).find());
    }

    /// The top-level arguments of the call whose `(` ends just before `start`.
    private static List<String> arguments(String code, int start) {
        var arguments = new ArrayList<String>();
        var depth = 0;
        var from = start;
        for (int i = start; i < code.length(); i++) {
            var ch = code.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if ((ch == ')' || ch == ']' || ch == '}') && depth > 0) {
                depth--;
            } else if (ch == ')') {
                addArgument(arguments, code.substring(from, i));
                return arguments;
            } else if (ch == ',' && depth == 0) {
                addArgument(arguments, code.substring(from, i));
                from = i + 1;
            }
        }
        throw new IllegalStateException("unbalanced argument list at offset " + start);
    }

    private static void addArgument(List<String> arguments, String argument) {
        if (!argument.isBlank() || !arguments.isEmpty()) {
            arguments.add(argument.strip());
        }
    }

    /// Comments and the contents of string, text-block and character literals become spaces; line breaks stay, so an
    /// offset in the result has the line it had in the source.
    private static String blankCommentsAndLiterals(String source) {
        var out = new StringBuilder(source);
        var i = 0;
        while (i < source.length()) {
            i = skipToken(source, out, i);
        }
        return out.toString();
    }

    private static int skipToken(String source, StringBuilder out, int i) {
        if (source.startsWith("//", i)) {
            return blank(source, out, i, endOfLine(source, i));
        }
        if (source.startsWith("/*", i)) {
            return blank(source, out, i, endAfter(source, i + 2, "*/"));
        }
        if (source.startsWith("\"\"\"", i)) {
            return blank(source, out, i, endAfter(source, i + 3, "\"\"\""));
        }
        if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
            return blank(source, out, i, endOfLiteral(source, i));
        }
        return i + 1;
    }

    private static int blank(String source, StringBuilder out, int from, int to) {
        for (int i = from; i < to; i++) {
            if (source.charAt(i) != '\n') {
                out.setCharAt(i, ' ');
            }
        }
        return to;
    }

    private static int endOfLine(String source, int from) {
        var end = source.indexOf('\n', from);
        return end < 0
               ? source.length()
               : end;
    }

    private static int endAfter(String source, int from, String terminator) {
        var end = source.indexOf(terminator, from);
        return end < 0
               ? source.length()
               : end + terminator.length();
    }

    private static int endOfLiteral(String source, int start) {
        var quote = source.charAt(start);
        var i = start + 1;
        while (i < source.length() && source.charAt(i) != quote && source.charAt(i) != '\n') {
            i += source.charAt(i) == '\\'
                 ? 2
                 : 1;
        }
        return Math.min(i + 1, source.length());
    }

    private static int lineOf(String code, int offset) {
        return (int) code.substring(0, offset)
                         .chars()
                         .filter(ch -> ch == '\n')
                         .count() + 1;
    }

    private static List<String> mainSources(Path root) throws IOException {
        return trackedFiles(root).filter(file -> file.contains(MAIN_SOURCES) && file.endsWith(".java"))
                                 .filter(file -> Files.isRegularFile(root.resolve(file)))
                                 .toList();
    }

    private static String read(Path root, String file) {
        try {
            return Files.readString(root.resolve(file), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    private static Stream<String> trackedFiles(Path root) throws IOException {
        var process = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z").redirectErrorStream(false)
                                                                                      .start();
        try (var in = process.getInputStream()) {
            var output = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(waitFor(process)).as("git ls-files exit code")
                                        .isZero();
            return Stream.of(output.split("\0"))
                         .filter(name -> !name.isEmpty());
        }
    }

    private static int waitFor(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            throw new IllegalStateException("interrupted waiting for git", e);
        }
    }

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".git"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root (a directory holding .git) above the working directory")
                       .isNotNull();
        return dir;
    }
}
