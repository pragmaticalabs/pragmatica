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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Fails the build when a test source carries a pattern, on one line or wrapped over several, that past flakes were fixed by
/// removing (a fixed sleep, a literal port, an elapsed-time bound, an await deadline under 5 s) and the hit is not in the
/// shrink-only allow-list.
///
/// The allow-list (`flake-scan-allowlist.txt`, beside this test's resources) is the census of what existed when the scan
/// landed. It may only shrink: an entry that no longer matches fails the build (delete it), and a list whose size differs
/// from [#COMMITTED_ENTRIES] fails it (growth is refused; a shrink must lower the constant in the same change).
/// The wait to use instead is [PollUntil].
class FlakeScanTest {
    /// The allow-list size as last committed. Lower it when entries are deleted; raising it is the change a reviewer must refuse.
    static final int COMMITTED_ENTRIES = 1286;

    private static final String ALLOW_LIST = "testing/src/test/resources/flake-scan-allowlist.txt";
    private static final int MINIMUM_EXAMINED_FILES = 1_000;

    @TempDir
    Path tmp;

    @Test
    void repository_hasNoFlakePatternOutsideTheAllowList() throws IOException {
        var root = repositoryRoot();
        var scan = FlakeScan.scan(root);

        assertThat(Files.isRegularFile(root.resolve(ALLOW_LIST))).as("repository root %s must hold %s (else the scan examines nothing)", root, ALLOW_LIST).isTrue();
        assertThat(scan.examinedFiles()).as("positive controls: the scan reached the whole repository, including this test")
                                        .hasSizeGreaterThan(MINIMUM_EXAMINED_FILES)
                                        .contains("testing/src/test/java/org/pragmatica/testing/FlakeScanTest.java");
        assertThat(scan.hits()).as("positive control: the allow-listed census is still seen by the scan").isNotEmpty();
        assertThat(FlakeScan.problems(scan.hits(), FlakeScan.entries(Files.readString(root.resolve(ALLOW_LIST))), COMMITTED_ENTRIES))
            .as("flake patterns outside the allow-list, stale or grown allow-list entries")
            .isEmpty();
    }

    @Test
    void problems_aHitWithoutAnEntry_isReportedWithFileLinePatternAndFix() throws IOException {
        var hit = FlakeScan.scan(plant("A.java", sleepLine())).hits().getFirst();

        var problems = FlakeScan.problems(List.of(hit), List.of(), 0);

        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst()).contains("SLEEP", "m/src/test/java/A.java:1", "PollUntil.pollUntil");
    }

    @Test
    void problems_anEntryMatchingNoLine_isReportedStale() throws IOException {
        var hit = FlakeScan.scan(plant("A.java", sleepLine())).hits().getFirst();
        var gone = "m/src/test/java/Gone.java|SLEEP|000000000000";

        var problems = FlakeScan.problems(List.of(hit), List.of(hit.key(), gone), 2);

        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst()).contains("stale allow-list entry", gone);
    }

    @Test
    void problems_anExtraEntryOverTheCommittedCount_isReportedAsGrowth() throws IOException {
        var hit = FlakeScan.scan(plant("A.java", sleepLine())).hits().getFirst();

        var problems = FlakeScan.problems(List.of(hit), List.of(hit.key()), 0);

        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst()).contains("may only shrink", "1 entries against a committed 0");
    }

    @Test
    void problems_aShrinkWithoutLoweringTheCount_isReported() throws IOException {
        var problems = FlakeScan.problems(List.of(), List.of(), 3);

        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst()).contains("lower COMMITTED_ENTRIES from 3 to 0");
    }

    @Test
    void problems_twoIdenticalLinesInOneFile_needTwoEntries() throws IOException {
        var hits = FlakeScan.scan(plant("A.java", sleepLine() + "\n" + sleepLine())).hits();

        assertThat(FlakeScan.problems(hits, List.of(hits.getFirst().key()), 1)).hasSize(1);
        assertThat(FlakeScan.problems(hits, List.of(hits.getFirst().key(), hits.getLast().key()), 2)).isEmpty();
    }

    @Test
    void problems_anEntryFollowsTheLineText_notTheLineNumber() throws IOException {
        var before = FlakeScan.scan(plant("A.java", sleepLine())).hits();
        var after = FlakeScan.scan(plant("A.java", "int unrelated = 1;\n" + sleepLine())).hits();

        assertThat(after.getFirst().line()).isEqualTo(2);
        assertThat(FlakeScan.problems(after, List.of(before.getFirst().key()), 1)).isEmpty();
    }

    @Test
    void hash_ignoresSurroundingWhitespaceAndCollapsesInnerRuns() {
        assertThat(FlakeScan.hash("  a \t b  ")).isEqualTo(FlakeScan.hash("a b"));
    }

    @Test
    void scan_everyPattern_isFoundByItsOwnPlantedLine() throws IOException {
        var planted = new LinkedHashMap<String, FlakeScan.Kind>();

        planted.put(sleepLine(), FlakeScan.Kind.SLEEP);
        planted.put("java.util.concurrent.TimeUnit.SECONDS" + ".sleep(1);", FlakeScan.Kind.SLEEP);
        planted.put("private static final int BASE_" + "PORT = 28000;", FlakeScan.Kind.FIXED_PORT);
        planted.put("int httpPort" + " = 8080;", FlakeScan.Kind.FIXED_PORT);
        planted.put("long udpPort" + " = 9000;", FlakeScan.Kind.FIXED_PORT);
        planted.put("Integer serverPort" + " = 9100;", FlakeScan.Kind.FIXED_PORT);
        planted.put("var s = new ServerSocket(" + "9100);", FlakeScan.Kind.FIXED_PORT);
        planted.put("var d = new DatagramSocket(" + "9200);", FlakeScan.Kind.FIXED_PORT);
        planted.put("server.bind" + "(8081);", FlakeScan.Kind.FIXED_PORT);
        planted.put("server.listen" + "(8082);", FlakeScan.Kind.FIXED_PORT);
        planted.put("config.withPort" + "(8083);", FlakeScan.Kind.FIXED_PORT);
        planted.put("config.port" + "(8084);", FlakeScan.Kind.FIXED_PORT);
        planted.put("config.udpPort" + "(8085);", FlakeScan.Kind.FIXED_PORT);
        planted.put("config.tcpPort" + "(8086);", FlakeScan.Kind.FIXED_PORT);
        planted.put("config.httpPort" + "(8087);", FlakeScan.Kind.FIXED_PORT);
        planted.put("assertThat(elapsedMs).isLess" + "Than(500);", FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put("assertThat(elapsedMs).isLessThan" + "OrEqualTo(500);", FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put("assertThat(elapsedMs).is" + "Between(1, 500);", FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put("assertThat(elapsedMs).isGreater" + "Than(500);", FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put("assertThat(elapsedMs).isGreaterThan" + "OrEqualTo(500);", FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put("assertThat(elapsedMs).isClose" + "To(500, offset);", FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put("assertThat(returnedAfter).isLess" + "Than(500);", FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put("assertThat(callTook).isLess" + "Than(500);", FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put("assertThat(Elapsed()).isLess" + "Than(500);", FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put("assertTrue(" + "took < 100);", FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put("assertTrue(" + "elapsed > 100);", FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put("await().atMost(" + "Duration.ofSeconds(2)).until(ready);", FlakeScan.Kind.SHORT_AWAIT);
        planted.put("await().atMost(" + "Duration.ofMillis(500)).until(ready);", FlakeScan.Kind.SHORT_AWAIT);
        planted.put("await().atMost(" + "300, TimeUnit.MILLISECONDS).until(ready);", FlakeScan.Kind.SHORT_AWAIT);
        planted.put("await().atMost(" + "3, TimeUnit.SECONDS).until(ready);", FlakeScan.Kind.SHORT_AWAIT);
        planted.put("await().atMost(" + "4, SECONDS).until(ready);", FlakeScan.Kind.SHORT_AWAIT);

        var hits = FlakeScan.scan(plant("A.java", String.join("\n", planted.keySet()))).hits();

        assertThat(hits.stream().map(hit -> hit.kind() + " <- " + hit.text()).toList())
            .as("each planted line is found, as exactly its own kind")
            .containsExactlyElementsOf(planted.entrySet().stream().map(entry -> entry.getValue() + " <- " + entry.getKey()).toList());
    }

    @Test
    void scan_everyPattern_isFoundWhenTheFormatterWrapsIt() throws IOException {
        var planted = new LinkedHashMap<String, FlakeScan.Kind>();

        planted.put(wrapped("Thread", "    .sleep" + "(100);"), FlakeScan.Kind.SLEEP);
        planted.put(wrapped("java.util.concurrent.TimeUnit.SECONDS", "    .sleep" + "(1);"), FlakeScan.Kind.SLEEP);
        planted.put(wrapped("int httpPort", "    = 8080;"), FlakeScan.Kind.FIXED_PORT);
        planted.put(wrapped("var s = new ServerSocket(", "    9100);"), FlakeScan.Kind.FIXED_PORT);
        planted.put(wrapped("var d = new DatagramSocket(", "    9200);"), FlakeScan.Kind.FIXED_PORT);
        planted.put(wrapped("server.bind" + "(", "    8081);"), FlakeScan.Kind.FIXED_PORT);
        planted.put(wrapped("config.withPort" + "(", "    8083);"), FlakeScan.Kind.FIXED_PORT);
        planted.put(wrapped("assertThat(elapsedMs)", "    .isLess" + "Than(500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(elapsedMs)", "    .isLessThan" + "OrEqualTo(500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(elapsedMs)", "    .is" + "Between(1, 500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(elapsedMs)", "    .isGreater" + "Than(500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(elapsedMs)", "    .isGreaterThan" + "OrEqualTo(500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(elapsedMs)", "    .isClose" + "To(500, offset);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(callTook)", "    .isLess" + "Than(500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(elapsedMs).as(\"bounded %d\", elapsedMs)", "    .isLess" + "Than(500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(elapsedMs)", "    // the bound", "    .isLess" + "Than(500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(elapsedMs) // the bound", "    .isLess" + "Than(500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(elapsedMs).as(\"bounded; {ok}\", elapsedMs)", "    .isLess" + "Than(500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(elapsedMs).as(\"say \\\"hi;\\\" now\", elapsedMs)", "    .isLess" + "Than(500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(elapsedMs).as(\";\" + '\"' + \"x;\", elapsedMs)", "    .isLess" + "Than(500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertThat(elapsedMs).as(\"\"\"", "    bounded; {x}", "    \"\"\", elapsedMs)", "    .isLess" + "Than(500);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("assertTrue(", "    took < 100);"), FlakeScan.Kind.WALL_CLOCK_BOUND);
        planted.put(wrapped("await().atMost(", "    Duration.ofSeconds(2)).until(ready);"), FlakeScan.Kind.SHORT_AWAIT);
        planted.put(wrapped("await().atMost(Duration", "    .ofMillis(500)).until(ready);"), FlakeScan.Kind.SHORT_AWAIT);
        planted.put(wrapped("await().atMost(", "    300, TimeUnit.MILLISECONDS).until(ready);"), FlakeScan.Kind.SHORT_AWAIT);
        planted.put(wrapped("await().atMost(", "    3, TimeUnit.SECONDS).until(ready);"), FlakeScan.Kind.SHORT_AWAIT);

        var hits = FlakeScan.scan(plant("A.java", String.join("\n", planted.keySet()))).hits();
        var expected = new ArrayList<String>();
        var line = 1;

        for (var entry : planted.entrySet()) {
            expected.add(line + " " + entry.getValue() + " <- " + withoutComments(entry.getKey()));
            line += entry.getKey().split("\n").length;
        }
        assertThat(hits.stream().map(hit -> hit.line() + " " + hit.kind() + " <- " + hit.text()).toList())
            .as("each wrapped plant is found once, at its statement's first line, as exactly its own kind")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void scan_aWrappedHit_startsAtItsStatementNotAtTheBlockBeforeIt() throws IOException {
        var source = String.join("\n",
                                 "@Test",
                                 "void bounded() {",
                                 "    var text = \"{ ; }\";",
                                 "    int unrelated = 1;",
                                 "    assertThat(elapsedMs)",
                                 "        .isLess" + "Than(500);",
                                 "}");

        assertThat(FlakeScan.scan(plant("A.java", source)).hits()).extracting(FlakeScan.Hit::line).containsExactly(5);
    }

    @Test
    void scan_aWrappedHit_isFoundWhenTheFileEndsWithoutAStatementTerminator() throws IOException {
        var source = String.join("\n", "int unrelated = 1;", "assertThat(elapsedMs)", "    .isLess" + "Than(500)");

        assertThat(FlakeScan.scan(plant("A.java", source)).hits()).extracting(FlakeScan.Hit::line).containsExactly(2);
    }

    @Test
    void scan_aSingleLineHit_isReportedOnceAtItsLine_evenInsideAMultiLineStatement() throws IOException {
        var source = String.join("\n", "run(", "    " + sleepLine(), "    more);");

        assertThat(FlakeScan.scan(plant("A.java", source)).hits()).extracting(FlakeScan.Hit::line, FlakeScan.Hit::text)
                                                                    .containsExactly(tuple(2, sleepLine()));
    }

    @Test
    void scan_thePassingWrappedForms_areNotFlagged() throws IOException {
        var source = String.join("\n",
                                 "assertThat(attempts)",
                                 "    .isLessThan(5);",
                                 "await().atMost(",
                                 "    Duration.ofSeconds(10)).until(ready);",
                                 "var s = new ServerSocket(",
                                 "    0);",
                                 "var elapsed = 5;",
                                 "check.isLessThan(5);",
                                 "assertThat(elapsedMs);",
                                 "    .isLessThan(5);",
                                 "// assertThat(elapsedMs)",
                                 "//     .isLessThan(5);",
                                 "/* assertThat(elapsedMs)",
                                 "   .isLessThan(5); */");

        assertThat(FlakeScan.scan(plant("A.java", source)).hits()).isEmpty();
    }

    @Test
    void scan_aSleepUnderSrcItJava_isFlagged() throws IOException {
        write(tmp.resolve("m/src/it/java/SomeIT.java"), sleepLine());

        var scan = FlakeScan.scan(tmp);

        assertThat(scan.examinedFiles()).containsExactly("m/src/it/java/SomeIT.java");
        assertThat(scan.hits()).extracting(FlakeScan.Hit::kind).containsExactly(FlakeScan.Kind.SLEEP);
    }

    @Test
    void scan_aRootNamedLikeASkippedDirectory_isStillExamined() throws IOException {
        var root = tmp.resolve("node_modules");

        write(root.resolve("m/src/test/java/A.java"), sleepLine());

        assertThat(FlakeScan.scan(root).examinedFiles()).containsExactly("m/src/test/java/A.java");
    }

    @Test
    void scan_everySkippedDirectoryName_isNotDescendedInto() throws IOException {
        for (var skipped : List.of("target", ".git", ".m2-local", "node_modules")) {
            write(tmp.resolve("m/" + skipped + "/x/src/test/java/Gen.java"), sleepLine());
        }

        assertThat(FlakeScan.scan(tmp).examinedFiles()).isEmpty();
    }

    @Test
    void entries_ignoreBlankLinesCommentsAndSurroundingWhitespace() {
        assertThat(FlakeScan.entries("# header\n\n   \n\t\n  a|SLEEP|1  \n#   b|SLEEP|2\nc|SLEEP|3\n"))
            .containsExactly("a|SLEEP|1", "c|SLEEP|3");
    }

    @Test
    void scan_thePassingForms_areNotFlagged() throws IOException {
        var source = String.join("\n",
                                 "// " + sleepLine(),
                                 "/// the old " + sleepLine(),
                                 " * " + sleepLine(),
                                 "/* " + sleepLine() + " */",
                                 "/** " + sleepLine(),
                                 "await().atMost(" + "Duration.ofSeconds(5)).until(ready);",
                                 "await().atMost(" + "Duration.ofSeconds(10)).until(ready);",
                                 "var s = new ServerSocket(0);",
                                 "private static final int PORT_COUNT = 3;",
                                 "assertThat(attempts).isLessThan(5);");

        assertThat(FlakeScan.scan(plant("A.java", source)).hits()).isEmpty();
    }

    @Test
    void scan_buildOutputAndNonTestSources_areNotExamined() throws IOException {
        var root = tmp;

        write(root.resolve("m/target/generated/src/test/java/Gen.java"), sleepLine());
        write(root.resolve("m/src/main/java/Main.java"), sleepLine());
        write(root.resolve("m/src/test/resources/Data.java"), sleepLine());

        assertThat(FlakeScan.scan(root).examinedFiles()).isEmpty();
    }

    private static String wrapped(String... lines) {
        return String.join("\n", lines);
    }

    /// The statement text the scan reports: comment lines and trailing comments dropped, whitespace collapsed.
    private static String withoutComments(String statement) {
        return String.join(" ",
                           statement.lines()
                                    .filter(line -> !line.strip().startsWith("//"))
                                    .map(line -> line.replaceAll("\\s*//.*$", ""))
                                    .collect(Collectors.joining(" "))
                                    .strip()
                                    .split("\\s+"));
    }

    /// Built from pieces so this file never carries the pattern it scans for.
    private static String sleepLine() {
        return "Thread" + ".sleep(100);";
    }

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.isDirectory(dir.resolve("testing/src/main/java"))) {
            dir = dir.getParent();
        }
        return dir == null ? Path.of("").toAbsolutePath() : dir;
    }

    private Path plant(String name, String content) throws IOException {
        write(tmp.resolve("m/src/test/java").resolve(name), content);
        return tmp;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
