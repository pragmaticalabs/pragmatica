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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// Fails the build when a test source carries a line pattern that past flakes were fixed by removing (a fixed sleep, a literal
/// port, an elapsed-time bound, an await deadline under 5 s) and the line is not in the shrink-only allow-list.
///
/// The allow-list (`flake-scan-allowlist.txt`, beside this test's resources) is the census of what existed when the scan
/// landed. It may only shrink: an entry that no longer matches fails the build (delete it), and a list whose size differs
/// from [#COMMITTED_ENTRIES] fails it (growth is refused; a shrink must lower the constant in the same change).
/// The wait to use instead is [PollUntil].
class FlakeScanTest {
    /// The allow-list size as last committed. Lower it when entries are deleted; raising it is the change a reviewer must refuse.
    static final int COMMITTED_ENTRIES = 1345;

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
    void scan_everyPattern_isFoundByItsOwnPlantedLine() throws IOException {
        var source = String.join("\n",
                                 sleepLine(),
                                 "java.util.concurrent.TimeUnit.SECONDS" + ".sleep(1);",
                                 "private static final int BASE_" + "PORT = 28000;",
                                 "var s = new ServerSocket(" + "9100);",
                                 "assertThat(elapsedMs).isLess" + "Than(500);",
                                 "assertTrue(" + "took < 100);",
                                 "await().atMost(" + "Duration.ofSeconds(2)).until(ready);",
                                 "await().atMost(" + "300, TimeUnit.MILLISECONDS).until(ready);");

        var kinds = FlakeScan.scan(plant("A.java", source)).hits().stream().map(FlakeScan.Hit::kind).toList();

        assertThat(kinds).containsExactly(FlakeScan.Kind.SLEEP,
                                          FlakeScan.Kind.SLEEP,
                                          FlakeScan.Kind.FIXED_PORT,
                                          FlakeScan.Kind.FIXED_PORT,
                                          FlakeScan.Kind.WALL_CLOCK_BOUND,
                                          FlakeScan.Kind.WALL_CLOCK_BOUND,
                                          FlakeScan.Kind.SHORT_AWAIT,
                                          FlakeScan.Kind.SHORT_AWAIT);
    }

    @Test
    void scan_thePassingForms_areNotFlagged() throws IOException {
        var source = String.join("\n",
                                 "// " + sleepLine(),
                                 "/// the old " + sleepLine(),
                                 " * " + sleepLine(),
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
