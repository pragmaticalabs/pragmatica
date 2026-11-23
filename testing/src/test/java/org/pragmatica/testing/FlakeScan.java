/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/// The line scanner behind `FlakeScanTest`: finds the line patterns that past flakes were fixed by removing, in every
/// `src/test/java` and `src/it/java` tree under a root, and checks the hits against a shrink-only allow-list.
///
/// Scope: `src/main` stays out, so test-support code that ships in a main tree (for example the cloud harness agent
/// `M1ProbeAgent`) is not examined; that is a decision, not an oversight.
///
/// Two passes. The line pass is the original: lines starting with `//`, `*` or `/*` are skipped, and a pattern inside a
/// string literal or after a trailing `//` comment is still counted (a new test text that merely mentions one must be
/// reworded or listed). The statement pass covers what a line cannot hold: the formatter wraps a fluent call, so a match
/// that spans lines (`assertThat(elapsed)` then `.isLessThan(`) is found over the statement, the text between two
/// boundaries (`;`, `{`, `}` outside string literals) with comment lines and trailing `//` comments dropped. A match on one
/// line belongs to the line pass alone, so every pre-existing entry keeps its key; a match across lines is reported at the
/// first line of its statement, keyed on the whitespace-normalised statement. The allow-list key is file + pattern + hash of
/// the whitespace-normalised line (or statement), never a line number, so unrelated edits above a hit do not stale its
/// entry. The corollary is that the list pins counts per file, not locations: moving a listed line elsewhere in the same
/// file is accepted. A pattern the regex does not describe (`assertThat(elapsed).as("x").isLessThan(`) is invisible to both
/// passes; wrapping is the only blind spot this closes.
final class FlakeScan {
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", ".git", ".m2-local", "node_modules");
    private static final List<String> SCANNED_SOURCES = List.of("/src/test/java/", "/src/it/java/");

    /// A pattern with a fixed flake behind it, and the fix the failure message points at.
    enum Kind {
        SLEEP("\\bThread\\s*\\.\\s*sleep\\s*\\(|\\bTimeUnit\\s*\\.\\s*[A-Z]+\\s*\\.\\s*sleep\\s*\\(",
              "wait for the condition with org.pragmatica.testing.PollUntil.pollUntil(supplier, predicate, deadline): a bounded poll that reports first and final value, elapsed time and margin; a fixed sleep guesses how long the work takes"),
        FIXED_PORT("(?i)\\b(static\\s+final\\s+int|int|long|Integer)\\s+\\w*port\\w*\\s*=\\s*[0-9][0-9_]{3,5}\\b"
                   + "|new\\s+(ServerSocket|DatagramSocket)\\s*\\(\\s*[0-9]{4,5}\\b"
                   + "|\\.\\s*(bind|listen|withPort|port|udpPort|tcpPort|httpPort)\\s*\\(\\s*[0-9][0-9_]{3,5}\\b",
                   "take a free or held port at bind time (the module's ClusterTestPorts/EmberTestPorts helper, or a port-0 bind) instead of a literal: a literal collides under the module-parallel build"),
        WALL_CLOCK_BOUND("(?i)\\b\\w*(elapsed|took|returnedAfter)\\w*\\s*(\\(\\))?\\s*\\)?\\s*\\.\\s*(isLessThan|isLessThanOrEqualTo|isBetween|isGreaterThan|isGreaterThanOrEqualTo|isCloseTo)\\s*\\("
                         + "|assertTrue\\s*\\(\\s*\\w*(elapsed|took)\\w*\\s*[<>]",
                         "assert the ordering structurally (a latch, a returned-before-released flag, an injected clock), not an elapsed time against a bound: a loaded host stretches any bound"),
        SHORT_AWAIT("\\batMost\\s*\\(\\s*(Duration\\s*\\.\\s*ofMillis\\s*\\(|Duration\\s*\\.\\s*ofSeconds\\s*\\(\\s*[1-4]\\s*\\)|[0-9_]+\\s*,\\s*TimeUnit\\s*\\.\\s*MILLISECONDS|[1-4]\\s*,\\s*(TimeUnit\\s*\\.\\s*)?SECONDS)",
                    "give the wait a deadline of at least 5 s (PollUntil.pollUntil(..., Duration.ofSeconds(10)) or atMost(Duration.ofSeconds(10))): the deadline bounds only the failure, a pass returns at once");

        private final Pattern pattern;
        private final String fix;

        Kind(String regex, String fix) {
            this.pattern = Pattern.compile(regex);
            this.fix = fix;
        }

        String fix() {
            return fix;
        }

        boolean matches(String line) {
            return pattern.matcher(line).find();
        }

        /// The matches inside `statement` that span a line break: those on one line belong to the line pass.
        long wrappedMatches(String statement) {
            return pattern.matcher(statement).results().filter(match -> match.group().indexOf('\n') >= 0).count();
        }
    }

    /// One flagged line, or the first line of a flagged statement that spans lines. `key()` is the allow-list entry that would excuse it.
    record Hit(String file, int line, Kind kind, String hash, String text) {
        static Hit hit(String file, int line, Kind kind, String text) {
            return new Hit(file, line, kind, FlakeScan.hash(text), text);
        }

        String key() {
            return file + "|" + kind + "|" + hash;
        }
    }

    private FlakeScan() {}

    /// Every hit in every `src/test/java` or `src/it/java` tree under `root`, in path then line order; also the number of files examined.
    static Scan scan(Path root) throws IOException {
        var hits = new ArrayList<Hit>();
        var examined = new ArrayList<String>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                       ? FileVisitResult.SKIP_SUBTREE
                       : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                var relative = root.relativize(file).toString().replace('\\', '/');

                if (relative.endsWith(".java") && isScanned("/" + relative)) {
                    examined.add(relative);
                    scanFile(relative, file, hits);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        hits.sort(Comparator.comparing(Hit::file).thenComparingInt(Hit::line));
        return new Scan(hits, examined);
    }

    /// The hits and the examined files: the latter lets a caller prove the scan reached what it claims to cover.
    record Scan(List<Hit> hits, List<String> examinedFiles) {}

    private static void scanFile(String relative, Path file, List<Hit> hits) throws IOException {
        var lines = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\\R", -1);

        for (var i = 0; i < lines.length; i++) {
            var text = lines[i].strip();

            if (!isComment(text)) {
                for (var kind : Kind.values()) {
                    if (kind.matches(lines[i])) {
                        hits.add(Hit.hit(relative, i + 1, kind, text));
                    }
                }
            }
        }
        for (var statement : Statements.of(lines)) {
            for (var kind : Kind.values()) {
                for (var n = kind.wrappedMatches(statement.text()); n > 0; n--) {
                    hits.add(Hit.hit(relative, statement.line(), kind, String.join(" ", statement.text().split("\\s+"))));
                }
            }
        }
    }

    /// A statement as the statement pass sees it: its first code line (1-based) and its text, line breaks kept.
    record Statement(int line, String text) {}

    /// Splits a file into statements at `;`, `{` and `}` outside string literals, char literals and text blocks. Comment
    /// lines (as `isComment` defines them) and trailing `//` comments are dropped; line breaks stay, so a match can be
    /// told to span lines. An unterminated literal ends with its line.
    private static final class Statements {
        private final List<Statement> found = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private int firstLine = 0;
        private boolean inTextBlock = false;

        static List<Statement> of(String[] lines) {
            var reader = new Statements();

            for (var i = 0; i < lines.length; i++) {
                reader.line(i + 1, lines[i]);
            }
            reader.flush();
            return reader.found;
        }

        private void line(int number, String line) {
            text.append('\n');
            if (inTextBlock || !isComment(line.strip())) {
                code(number, line);
            }
        }

        private void code(int number, String line) {
            for (var i = 0; i < line.length(); i++) {
                var c = line.charAt(i);

                if (inTextBlock) {
                    i = textBlock(number, line, i);
                } else if (line.startsWith("\"\"\"", i)) {
                    inTextBlock = true;
                    append(number, "\"\"\"");
                    i += 2;
                } else if (c == '"' || c == '\'') {
                    i = literal(number, line, i);
                } else if (line.startsWith("//", i)) {
                    return;
                } else {
                    append(number, String.valueOf(c));
                    if (c == ';' || c == '{' || c == '}') {
                        flush();
                    }
                }
            }
        }

        /// Consumes one text-block character (or escape pair, or the closing delimiter); returns the index of the last one consumed.
        private int textBlock(int number, String line, int i) {
            if (line.startsWith("\"\"\"", i)) {
                inTextBlock = false;
                append(number, "\"\"\"");
                return i + 2;
            }
            if (line.charAt(i) == '\\' && i + 1 < line.length()) {
                append(number, line.substring(i, i + 2));
                return i + 1;
            }
            append(number, String.valueOf(line.charAt(i)));
            return i;
        }

        /// Consumes a string or char literal starting at `i`; returns the index of its closing quote (or the line's last index).
        private int literal(int number, String line, int i) {
            var quote = line.charAt(i);
            var end = i + 1;

            while (end < line.length() && line.charAt(end) != quote) {
                end += line.charAt(end) == '\\' ? 2 : 1;
            }
            end = Math.min(end, line.length() - 1);
            append(number, line.substring(i, end + 1));
            return end;
        }

        private void append(int number, String piece) {
            if (firstLine == 0 && !piece.isBlank()) {
                firstLine = number;
            }
            text.append(piece);
        }

        private void flush() {
            if (firstLine > 0) {
                found.add(new Statement(firstLine, text.toString().strip()));
            }
            text.setLength(0);
            firstLine = 0;
        }
    }

    private static boolean isScanned(String path) {
        return SCANNED_SOURCES.stream().anyMatch(path::contains);
    }

    private static boolean isComment(String text) {
        return text.startsWith("//") || text.startsWith("*") || text.startsWith("/*");
    }

    /// The allow-list entries in a file's text: one per line, blank lines and `#` comments ignored.
    static List<String> entries(String allowListText) {
        return allowListText.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
    }

    /// Everything wrong between the hits and the allow-list; empty means green. Entries are a multiset: two identical
    /// lines in one file need two entries. `committedCount` is the size the list was last committed at.
    static List<String> problems(List<Hit> hits, List<String> entries, int committedCount) {
        var problems = new ArrayList<String>();
        var remaining = new HashMap<String, Integer>();

        entries.forEach(entry -> remaining.merge(entry, 1, Integer::sum));
        for (var hit : hits) {
            if (remaining.merge(hit.key(), -1, Integer::sum) < 0) {
                problems.add("flake pattern " + hit.kind() + " at " + hit.file() + ":" + hit.line() + ": " + hit.text()
                             + "\n    fix: " + hit.kind().fix());
            }
        }
        problems.addAll(staleEntries(remaining));
        problems.addAll(sizeProblems(entries.size(), committedCount));
        return problems;
    }

    private static List<String> staleEntries(Map<String, Integer> remaining) {
        return remaining.entrySet()
                        .stream()
                        .filter(entry -> entry.getValue() > 0)
                        .map(entry -> "stale allow-list entry (matches no line any more, x" + entry.getValue() + "): " + entry.getKey()
                                      + "\n    fix: delete it from flake-scan-allowlist.txt and lower COMMITTED_ENTRIES to match")
                        .sorted()
                        .toList();
    }

    private static List<String> sizeProblems(int actual, int committed) {
        return actual > committed
               ? List.of("the allow-list holds " + actual + " entries against a committed " + committed
                         + ": it may only shrink; fix the flagged code instead of listing it")
               : actual < committed
                 ? List.of("the allow-list shrank to " + actual + " entries: lower COMMITTED_ENTRIES from " + committed + " to " + actual)
                 : List.of();
    }

    static String hash(String text) {
        try {
            var normalised = String.join(" ", text.strip().split("\\s+"));
            var digest = MessageDigest.getInstance("SHA-1").digest(normalised.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();

            for (var b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is a required JDK algorithm", e);
        }
    }
}
