// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #2583 P1 ratchet: every unicast transport `send` in `src/main` either hands its [WriteOutcome] on, or is counted in
/// the allow-list (`transport-outcome-allowlist.txt`) against an entry that cites what compensates for a lost frame (a
/// `path:line@anchor`, the anchor being a symbol the cited line must still contain), or the `#2583-P<n>` phase that
/// gives it a policy.
///
/// The scan is lexical, per file, over comment- and string-stripped source:
///
///   - a site is every `.send(` call, every `::send` method reference and every `new (NetworkServiceMessage.)?Send(`
///     message-bus unicast (it reaches `QuicClusterNetwork.handleSend`, which discards the outcome);
///   - a `.send(` call whose FIRST continuation reads the value (`onSuccess`, `withSuccess`, `map`, `flatMap`, `fold`,
///     `onResult`) or which is the whole returned expression (`return network.send(...)`) hands the outcome on and
///     needs no entry. `onFailure`, `withFailure` and `mapToUnit` read nothing: the promise never fails;
///   - every other site is outcome-blind: a statement, a lambda body, a method reference, a router `Send`, a chain
///     that does not read the value.
///
/// A file's entry must carry its exact outcome-blind count, so a new blind send fails this test and so does a
/// stale entry. The search space is asserted, not assumed: every module whose `src/main` names one of the transport
/// interfaces must be scanned, and the scan must cover at least a pinned number of files.
///
/// [WriteOutcome]: org.pragmatica.consensus.net.WriteOutcome
class TransportOutcomeDisciplineTest {
    static final List<String> SCANNED_MODULES = List.of("integrations/consensus",
                                                        "integrations/cluster",
                                                        "integrations/dht",
                                                        "aether/aether-invoke",
                                                        "aether/aether-metrics",
                                                        "aether/aether-stream",
                                                        "aether/node");
    static final Pattern TRANSPORT_TYPE = Pattern.compile("\\b(ClusterNetwork|DHTNetwork|ReplicationTransport|StreamForwardTransport|NetworkServiceMessage)\\b");
    /// The scanned file count at the commit that introduced the ratchet, rounded down: shrinking far below it means
    /// the walk lost part of its search space.
    static final int FILE_FLOOR = 650;
    static final String ALLOW_LIST = "transport-outcome-allowlist.txt";
    static final List<String> READER_CONTINUATIONS = List.of("onSuccess", "withSuccess", "map", "flatMap", "fold", "onResult");

    @Test
    void searchSpace_coversEveryModuleThatNamesATransport() {
        var root = repoRoot();

        assertThat(SCANNED_MODULES).as("every module whose main sources name a transport interface is scanned")
                                   .containsAll(modulesNamingATransport(root));
        assertThat(SCANNED_MODULES).allSatisfy(module -> assertThat(root.resolve(module).resolve("src/main/java")).isDirectory());
    }

    @Test
    void searchSpace_scansAtLeastThePinnedFileFloor() {
        assertThat(scannedFiles(repoRoot())).as("java files scanned").hasSizeGreaterThanOrEqualTo(FILE_FLOOR);
    }

    @Test
    void everyOutcomeBlindSend_isCountedInACitedAllowListEntry() {
        var root = repoRoot();
        var blind = blindCounts(root, scannedFiles(root));
        var entries = AllowList.parse(readResource(ALLOW_LIST));

        assertThat(violations(blind, entries, root)).as("outcome-blind transport sends without a matching allow-list entry")
                                                    .isEmpty();
    }

    /// The instrument against fixtures, so a scanner that misses a shape or accepts an uncited entry fails here.
    @Nested
    class Fixture {
        @Test
        void bareSendStatement_isBlind() {
            assertThat(SendScanner.blindLines("class A { void f() { network.send(peer, message); } }")).containsExactly(1);
        }

        @Test
        void sendMethodReference_isBlind() {
            assertThat(SendScanner.blindLines("class A { Object f() { return wire(network::send); } }")).containsExactly(1);
        }

        @Test
        void sendInLambdaBody_isBlind() {
            assertThat(SendScanner.blindLines("class A {\n void f() {\n peers.forEach(p -> network.send(p, m));\n }\n}")).containsExactly(3);
        }

        @Test
        void chainedOrReturnedSend_handsTheOutcomeOn() {
            var source = """
                class A {
                    Object f() { return network.send(peer, message); }
                    void g() { network.send(peer, message).onSuccess(this::policy); }
                    void h() { var _ = network
                                   .send(peer, message)
                                   .onSuccess(this::policy); }
                }
                """;

            assertThat(SendScanner.blindLines(source)).isEmpty();
        }

        @Test
        void routerSend_isAlwaysBlind_qualifiedOrImported() {
            var source = "class A {\n void f() { router.route(new Send(peer, m)); }\n void g() { router.route(new NetworkServiceMessage.Send(peer, m)); }\n}";

            assertThat(SendScanner.blindLines(source)).containsExactly(2, 3);
        }

        @Test
        void chainOpeningWithAnOutcomeReader_handsTheOutcomeOn() {
            READER_CONTINUATIONS.forEach(reader -> assertThat(SendScanner.blindLines("class A { void f() { network.send(p, m)." + reader + "(this::policy); } }"))
                                                                  .as(reader)
                                                                  .isEmpty());
        }

        @Test
        void chainOpeningWithAContinuationThatReadsNothing_isBlind() {
            var onFailure = "class A { void f() { var _ = network.send(p, m).onFailure(_ -> {}); } }";
            var mapToUnit = "class A { void f() { var _ = network.send(p, m).mapToUnit(); } }";
            var readerSecond = "class A { void f() { network.send(p, m).onFailure(_ -> {}).onSuccess(this::policy); } }";

            assertThat(SendScanner.blindLines(onFailure)).containsExactly(1);
            assertThat(SendScanner.blindLines(mapToUnit)).containsExactly(1);
            assertThat(SendScanner.blindLines(readerSecond)).containsExactly(1);
        }

        @Test
        void readerWhoseLambdaDiscardsItsParameter_isBlind() {
            var onSuccess = "class A { void f() { var _ = network.send(p, m).onSuccess(_ -> {}); } }";
            var map = "class A { void f() { var _ = network.send(p, m).map( _ -> unit()); } }";
            var named = "class A { void f() { var _ = network.send(p, m).onSuccess(outcome -> policy(outcome)); } }";

            assertThat(SendScanner.blindLines(onSuccess)).containsExactly(1);
            assertThat(SendScanner.blindLines(map)).containsExactly(1);
            assertThat(SendScanner.blindLines(named)).isEmpty();
        }

        @Test
        void commentsAndStrings_areNotSites() {
            var source = "class A { // network.send(a, b);\n /* x::send */ String s = \"network.send(a, b)\"; }";

            assertThat(SendScanner.blindLines(source)).isEmpty();
        }

        @Test
        void entryWithoutCitation_isRejected() {
            var entries = AllowList.parse("a/B.java | 1 |  | reason\n");

            assertThat(violations(Map.of("a/B.java", 1), entries, Path.of("."))).anyMatch(v -> v.contains("no citation"));
        }

        @Test
        void entryWithMalformedCitation_isRejected() {
            var entries = AllowList.parse("a/B.java | 1 | see the docs | reason\n");

            assertThat(violations(Map.of("a/B.java", 1), entries, Path.of("."))).anyMatch(v -> v.contains("malformed citation"));
        }

        @Test
        void countMismatch_andMissingEntry_andStaleEntry_areRejected() {
            var entries = AllowList.parse("a/B.java | 1 | #2583-P2 | reason\nc/D.java | 1 | #2583-P3 | reason\n");
            var found = violations(Map.of("a/B.java", 2, "e/F.java", 1), entries, Path.of("."));

            assertThat(found).anyMatch(v -> v.startsWith("a/B.java") && v.contains("2 outcome-blind"))
                             .anyMatch(v -> v.startsWith("e/F.java") && v.contains("no allow-list entry"))
                             .anyMatch(v -> v.startsWith("c/D.java") && v.contains("0 outcome-blind"));
        }

        @Test
        void citationWithoutAnchor_isRejected(@TempDir Path root) throws IOException {
            Files.writeString(root.resolve("C.java"), "line one\nretry(this::tick)\n");
            var entries = AllowList.parse("a/B.java | 1 | C.java | reason\n");

            assertThat(violations(Map.of("a/B.java", 1), entries, root)).anyMatch(v -> v.contains("no content anchor"));
        }

        @Test
        void compensatorThatMoved_staysAccepted(@TempDir Path root) throws IOException {
            var entries = AllowList.parse("a/B.java | 1 | C.java@retry(this::tick) | reason\n");

            Files.writeString(root.resolve("C.java"), "line one\nretry(this::tick)\nline three\n");
            assertThat(violations(Map.of("a/B.java", 1), entries, root)).isEmpty();

            Files.writeString(root.resolve("C.java"), "new\nlines\nabove\nline one\nretry(this::tick)\nline three\n");
            assertThat(violations(Map.of("a/B.java", 1), entries, root)).isEmpty();
        }

        @Test
        void deletedOrRenamedAnchor_isRejected(@TempDir Path root) throws IOException {
            Files.writeString(root.resolve("C.java"), "line one\nretry(this::tock)\n");
            var entries = AllowList.parse("a/B.java | 1 | C.java@retry(this::tick) | reason\n");

            assertThat(violations(Map.of("a/B.java", 1), entries, root)).anyMatch(v -> v.contains("no longer contains"));
        }

        @Test
        void citedFileThatIsGone_isRejected(@TempDir Path root) {
            var entries = AllowList.parse("a/B.java | 1 | Gone.java@retry(this::tick) | reason\n");

            assertThat(violations(Map.of("a/B.java", 1), entries, root)).anyMatch(v -> v.contains("names no such file"));
        }

        @Test
        void ambiguousAnchorWithoutCount_isRejected(@TempDir Path root) throws IOException {
            Files.writeString(root.resolve("C.java"), "retry(this::tick)\nother\nretry(this::tick)\n");
            var entries = AllowList.parse("a/B.java | 1 | C.java@retry(this::tick) | reason\n");

            assertThat(violations(Map.of("a/B.java", 1), entries, root)).anyMatch(v -> v.contains("occurs 2 times"));
        }

        @Test
        void ambiguousAnchorWithMatchingCount_isAccepted(@TempDir Path root) throws IOException {
            Files.writeString(root.resolve("C.java"), "retry(this::tick)\nother\nretry(this::tick)\n");
            var entries = AllowList.parse("a/B.java | 1 | C.java@retry(this::tick)#2 | reason\n");

            assertThat(violations(Map.of("a/B.java", 1), entries, root)).isEmpty();
        }

        @Test
        void countThatIsWrong_isRejected(@TempDir Path root) throws IOException {
            Files.writeString(root.resolve("C.java"), "retry(this::tick)\nother\nretry(this::tick)\nretry(this::tick)\n");
            var entries = AllowList.parse("a/B.java | 1 | C.java@retry(this::tick)#2 | reason\n");

            assertThat(violations(Map.of("a/B.java", 1), entries, root)).anyMatch(v -> v.contains("occurs 3 times"));
        }

        @Test
        void lineNumberCitation_isRejectedWithPointerToTheIssue(@TempDir Path root) throws IOException {
            Files.writeString(root.resolve("C.java"), "line one\nretry(this::tick)\n");
            var entries = AllowList.parse("a/B.java | 1 | C.java:2@retry(this::tick) | reason\n");

            assertThat(violations(Map.of("a/B.java", 1), entries, root)).anyMatch(v -> v.contains("cite by anchor, see #3223"));
        }

        @Test
        void phaseTagCitation_isAccepted() {
            var entries = AllowList.parse("a/B.java | 1 | #2583-P4a | reason\n");

            assertThat(violations(Map.of("a/B.java", 1), entries, Path.of("."))).isEmpty();
        }
    }

    // --- the instrument ---

    record Entry(String path, int count, List<String> citations, String reason) {}

    sealed interface AllowList {
        static Map<String, Entry> parse(String text) {
            var entries = new TreeMap<String, Entry>();

            text.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(AllowList::entry)
                .forEach(entry -> entries.put(entry.path(), entry));

            return entries;
        }

        private static Entry entry(String line) {
            var fields = line.split("\\|", -1);

            assertThat(fields).as("allow-list line has 4 fields: " + line).hasSize(4);

            var citations = Stream.of(fields[2].split(","))
                                  .map(String::strip)
                                  .filter(citation -> !citation.isEmpty())
                                  .toList();

            return new Entry(fields[0].strip(), Integer.parseInt(fields[1].strip()), citations, fields[3].strip());
        }

        record unused() implements AllowList {}
    }

    static final Pattern PHASE_CITATION = Pattern.compile("#2583-P[0-4][a-e]?");
    static final Pattern FILE_CITATION = Pattern.compile("([\\w./-]+\\.java):(\\d+)(?:-(\\d+))?(?:@(.+))?");

    static List<String> violations(Map<String, Integer> blind, Map<String, Entry> entries, Path root) {
        var found = new ArrayList<String>();
        var paths = new TreeSet<String>(blind.keySet());

        paths.addAll(entries.keySet());
        paths.forEach(path -> checkPath(path, blind.getOrDefault(path, 0), entries.get(path), root, found));

        return found;
    }

    private static void checkPath(String path, int count, Entry entry, Path root, List<String> found) {
        if (entry == null) {
            found.add(path + ": " + count + " outcome-blind send(s), no allow-list entry");

            return;
        }

        if (entry.count() != count) {
            found.add(path + ": " + count + " outcome-blind send(s), the allow-list entry says " + entry.count());
        }

        if (entry.citations().isEmpty()) {
            found.add(path + ": allow-list entry has no citation");
        }

        entry.citations().forEach(citation -> checkCitation(path, citation, root, found));
    }

    private static void checkCitation(String path, String citation, Path root, List<String> found) {
        if (PHASE_CITATION.matcher(citation).matches()) {
            return;
        }

        var file = FILE_CITATION.matcher(citation);

        if (!file.matches()) {
            found.add(path + ": malformed citation '" + citation + "' (want path.java:line or #2583-P<n>)");

            return;
        }

        var cited = root.resolve(file.group(1));
        var first = Integer.parseInt(file.group(2));
        var last = Integer.parseInt(Objects.requireNonNullElse(file.group(3), file.group(2)));

        if (!Files.isRegularFile(cited) || lineCount(cited) < last) {
            found.add(path + ": citation '" + citation + "' names no such file line");

            return;
        }

        checkAnchor(path, citation, file.group(4), citedText(cited, first, last), found);
    }

    private static void checkAnchor(String path, String citation, String anchor, String text, List<String> found) {
        if (anchor == null) {
            found.add(path + ": citation '" + citation + "' has no content anchor (want path.java:line@symbol)");
        } else if (!text.contains(anchor)) {
            found.add(path + ": citation '" + citation + "' has drifted: the cited line(s) no longer contain '" + anchor + "'");
        }
    }

    private static String citedText(Path file, int first, int last) {
        return read(file).lines().skip(first - 1L).limit(last - first + 1L).collect(Collectors.joining("\n"));
    }

    sealed interface SendScanner {
        Pattern SITE = Pattern.compile("\\.\\s*send\\s*\\(|::\\s*send\\b");
        /// A message-bus unicast: always blind, because `QuicClusterNetwork.handleSend` discards the outcome.
        Pattern ROUTER_SEND = Pattern.compile("\\bnew\\s+(?:NetworkServiceMessage\\s*\\.\\s*)?Send\\s*\\(");
        /// The continuation a chained send must open with to count as reading the outcome.
        Pattern FIRST_CONTINUATION = Pattern.compile("^\\.\\s*(\\w+)");
        /// A reader whose lambda discards its parameter (`onSuccess(_ -> ...)`, `map(_ -> ...)`) reads nothing.
        Pattern UNNAMED_PARAMETER = Pattern.compile("^\\.\\s*\\w+\\s*\\(\\s*_\\s*->");
        Set<String> READERS = Set.of("onSuccess", "withSuccess", "map", "flatMap", "fold", "onResult");

        /// 1-based lines of the outcome-blind sites in `source`.
        static List<Integer> blindLines(String source) {
            var code = stripCommentsAndStrings(source);
            var lines = new ArrayList<Integer>();
            var site = SITE.matcher(code);

            while (site.find()) {
                if (site.group().startsWith(":") || isBlindCall(code, site.start(), site.end() - 1)) {
                    lines.add(lineOf(code, site.start()));
                }
            }

            var router = ROUTER_SEND.matcher(code);

            while (router.find()) {
                lines.add(lineOf(code, router.start()));
            }

            return lines.stream().sorted().toList();
        }

        private static boolean isBlindCall(String code, int dot, int open) {
            var close = matchingParen(code, open);
            var next = code.substring(close + 1).stripLeading();

            return ! (readsOutcome(next) || isReturned(code, dot) && next.startsWith(";"));
        }

        private static boolean readsOutcome(String continuation) {
            var first = FIRST_CONTINUATION.matcher(continuation);

            return first.find() && READERS.contains(first.group(1)) && !UNNAMED_PARAMETER.matcher(continuation).find();
        }

        private static boolean isReturned(String code, int dot) {
            var statementStart = Math.max(code.lastIndexOf(';', dot), Math.max(code.lastIndexOf('{', dot), code.lastIndexOf('}', dot)));

            return code.substring(statementStart + 1, dot).stripLeading().startsWith("return ");
        }

        private static int matchingParen(String code, int open) {
            var depth = 0;

            for (var i = open; i < code.length(); i++) {
                depth += switch (code.charAt(i)) {
                    case '(' -> 1;
                    case ')' -> -1;
                    default -> 0;
                };
                if (depth == 0) {
                    return i;
                }
            }

            return code.length() - 1;
        }

        private static int lineOf(String code, int offset) {
            return (int) code.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
        }

        /// Blanks comments, string, text-block and char literals, keeping every newline so line numbers survive.
        static String stripCommentsAndStrings(String source) {
            var out = new StringBuilder(source.length());
            var i = 0;

            while (i < source.length()) {
                var end = literalEnd(source, i);

                if (end == i) {
                    out.append(source.charAt(i));
                    i++;
                } else {
                    source.substring(i, end).chars().forEach(c -> out.append(c == '\n' ? '\n' : ' '));
                    i = end;
                }
            }

            return out.toString();
        }

        private static int literalEnd(String s, int i) {
            if (s.startsWith("//", i)) {
                var eol = s.indexOf('\n', i);

                return eol < 0 ? s.length() : eol;
            }
            if (s.startsWith("/*", i)) {
                return s.indexOf("*/", i + 2) + 2;
            }
            if (s.startsWith("\"\"\"", i)) {
                return s.indexOf("\"\"\"", i + 3) + 3;
            }
            if (s.charAt(i) == '"' || s.charAt(i) == '\'') {
                return quotedEnd(s, i, s.charAt(i));
            }

            return i;
        }

        private static int quotedEnd(String s, int i, char quote) {
            var j = i + 1;

            while (s.charAt(j) != quote) {
                j += s.charAt(j) == '\\' ? 2 : 1;
            }

            return j + 1;
        }

        record unused() implements SendScanner {}
    }

    // --- search space ---

    private static Map<String, Integer> blindCounts(Path root, List<Path> files) {
        var counts = new TreeMap<String, Integer>();

        files.forEach(file -> {
            var blind = SendScanner.blindLines(read(file)).size();

            if (blind > 0) {
                counts.put(root.relativize(file).toString().replace('\\', '/'), blind);
            }
        });

        return counts;
    }

    private static List<Path> scannedFiles(Path root) {
        return SCANNED_MODULES.stream()
                              .flatMap(module -> javaFiles(root.resolve(module).resolve("src/main/java")).stream())
                              .toList();
    }

    private static Set<String> modulesNamingATransport(Path root) {
        var modules = new TreeSet<String>();

        mainSourceRoots(root).forEach(sources -> {
            var named = javaFiles(sources).stream()
                                          .anyMatch(file -> TRANSPORT_TYPE.matcher(SendScanner.stripCommentsAndStrings(read(file))).find());

            if (named) {
                modules.add(root.relativize(sources.getParent().getParent().getParent()).toString().replace('\\', '/'));
            }
        });

        return modules;
    }

    private static final Set<String> PRUNED = Set.of(".git", ".m2-local", "target", "node_modules", ".bb", ".idea");

    private static List<Path> mainSourceRoots(Path root) {
        var roots = new ArrayList<Path>();

        walk(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (PRUNED.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (dir.endsWith("src/main/java")) {
                    roots.add(dir);

                    return FileVisitResult.SKIP_SUBTREE;
                }

                return FileVisitResult.CONTINUE;
            }
        });

        return roots;
    }

    private static List<Path> javaFiles(Path dir) {
        try (var files = Files.walk(dir)) {
            return files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void walk(Path root, SimpleFileVisitor<Path> visitor) {
        try {
            Files.walkFileTree(root, visitor);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.isRegularFile(dir.resolve("integrations/consensus/pom.xml"))) {
            dir = dir.getParent();
        }

        assertThat(dir).as("repository root above " + Path.of("").toAbsolutePath()).isNotNull();

        return dir;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long lineCount(Path file) {
        return read(file).lines().count();
    }

    private static String readResource(String name) {
        try (var in = TransportOutcomeDisciplineTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("test resource " + name).isNotNull();

            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
