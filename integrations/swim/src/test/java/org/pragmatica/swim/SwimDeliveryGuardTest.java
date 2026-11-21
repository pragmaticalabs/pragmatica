/*
 *  Copyright (c) 2020-2026 Sergiy Yevtushenko.
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
 */
package org.pragmatica.swim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #3560: fails the build when a test that drives a SWIM protocol calls `onMessage` directly.
///
/// Why: production `onMessage` never waits. While the probe tick drains, it only enqueues, so a test that asserts on
/// the next line reads the state before the message was applied. Tests deliver through `deliverForTest`, which waits.
///
/// Scope: every `src/test/java` file under the repository root that mentions `SwimProtocol` or `SwimMessageHandler`
/// (the other modules' tests that drive a protocol are included, not only this module's). Comment lines are skipped.
/// A deliberate bare call (a simulated network hop that runs inside a task and must not wait) carries the marker
/// `ASYNC-DELIVERY:` with its reason in one of the two lines above it; there is no other allow-list.
/// The scan is line based, like `FlakeScan`: a call split so that `.onMessage(` is not on one line is invisible.
class SwimDeliveryGuardTest {
    private static final String MARKER = "ASYNC-DELIVERY:";
    private static final Pattern BARE_CALL = Pattern.compile("\\.onMessage\\(");
    private static final Pattern DRIVES_SWIM = Pattern.compile("SwimProtocol|SwimMessageHandler");
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", ".git", ".m2-local", "node_modules");
    private static final String SEAM = "deliver" + "ForTest";
    private static final String SEAM_OWNER = "integrations/swim/src/main/java/org/pragmatica/swim/SwimProtocol.java";
    private static final int MARKER_REACH = 2;
    private static final int MINIMUM_SWIM_FILES = 30;

    @TempDir
    Path tmp;

    @Test
    void swimDrivingTests_neverCallOnMessageDirectly() throws IOException {
        var root = repositoryRoot();
        var scan = scan(root);

        assertThat(scan.examined()).as("positive control: the scan reached this module's tests")
                                   .hasSizeGreaterThan(MINIMUM_SWIM_FILES)
                                   .contains("integrations/swim/src/test/java/org/pragmatica/swim/SwimStateConfinementTest.java");
        assertThat(scan.marked()).as("positive control: the deliberate async sites are still seen, with their marker")
                                 .isNotEmpty();
        assertThat(scan.violations()).as("bare onMessage in a test that drives a SWIM protocol; use protocol.deliverForTest(sender, message)")
                                     .isEmpty();
    }

    @Test
    void scan_aPlantedBareCall_isReported() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\n" + bareLine()));

        assertThat(scan.violations()).containsExactly("m/src/test/java/A.java:2");
    }

    @Test
    void scan_aMarkedCall_isNotAViolationButIsCounted() throws IOException {
        var scan = scan(plant("A.java", "SwimMessageHandler h;\n// " + MARKER + " wire hop\n" + bareLine()));

        assertThat(scan.violations()).isEmpty();
        assertThat(scan.marked()).containsExactly("m/src/test/java/A.java:3");
    }

    @Test
    void scan_aMarkerTooFarAbove_doesNotExcuseTheCall() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\n// " + MARKER + " far\nint a;\nint b;\n" + bareLine()));

        assertThat(scan.violations()).containsExactly("m/src/test/java/A.java:5");
    }

    @Test
    void scan_aFileThatDoesNotDriveSwim_isNotExamined() throws IOException {
        var scan = scan(plant("A.java", bareLine()));

        assertThat(scan.examined()).isEmpty();
        assertThat(scan.violations()).isEmpty();
    }

    @Test
    void scan_aCommentedCall_isNotAViolation() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\n// " + bareLine() + "\n/// " + bareLine()));

        assertThat(scan.violations()).isEmpty();
    }

    @Test
    void scan_deliverForTest_isNotAViolation() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\np.deliverForTest(addr, message);"));

        assertThat(scan.violations()).isEmpty();
    }

    /// The seam is public only because tests in other packages and modules drive a started protocol (#3560); this is
    /// what keeps it test-only: no production source except its own declaration mentions it.
    @Test
    void productionSources_neverCallTheDeliverySeam() throws IOException {
        var root = repositoryRoot();

        assertThat(productionCallers(root)).as("production source referencing the delivery seam; it is a test-only seam")
                                           .isEmpty();
        assertThat(Files.readString(root.resolve(SEAM_OWNER))).as("positive control: the scan knows the seam's owner")
                                                              .contains(SEAM + "(");
    }

    @Test
    void productionScan_aPlantedCallInProductionCode_isReported() throws IOException {
        var main = tmp.resolve("m/src/main/java");

        Files.createDirectories(main);
        Files.writeString(main.resolve("A.java"), "class A { void f() { p." + SEAM + "(a, m); } }");

        assertThat(productionCallers(tmp)).containsExactly("m/src/main/java/A.java");
    }

    /// Built from pieces so this file never carries the pattern it scans for.
    private static String bareLine() {
        return "protocol" + ".onMes" + "sage(addr, message);";
    }

    private record Scan(List<String> examined, List<String> violations, List<String> marked) {}

    private static Scan scan(Path root) throws IOException {
        var examined = new ArrayList<String>();
        var violations = new ArrayList<String>();
        var marked = new ArrayList<String>();

        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var relative = root.relativize(file).toString().replace('\\', '/');

                if (isTestSource(relative)) {
                    scanFile(relative, file, examined, violations, marked);
                }
            }
        }
        return new Scan(examined, violations, marked);
    }

    private static List<String> productionCallers(Path root) throws IOException {
        var callers = new ArrayList<String>();

        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var relative = root.relativize(file).toString().replace('\\', '/');

                if (isProductionSource(relative) && !relative.equals(SEAM_OWNER) && Files.readString(file).contains(SEAM + "(")) {
                    callers.add(relative);
                }
            }
        }
        return callers;
    }

    private static boolean isProductionSource(String relative) {
        return relative.endsWith(".java")
               && ("/" + relative).contains("/src/main/java/")
               && Arrays.stream(relative.split("/")).noneMatch(SKIPPED_DIRECTORIES::contains);
    }

    private static boolean isTestSource(String relative) {
        return relative.endsWith(".java")
               && ("/" + relative).contains("/src/test/java/")
               && Arrays.stream(relative.split("/")).noneMatch(SKIPPED_DIRECTORIES::contains);
    }

    private static void scanFile(String relative, Path file, List<String> examined, List<String> violations, List<String> marked)
        throws IOException {
        var text = Files.readString(file);

        if (DRIVES_SWIM.matcher(text).find()) {
            examined.add(relative);
            var lines = text.split("\\R", -1);

            for (var i = 0; i < lines.length; i++) {
                if (!isComment(lines[i].strip()) && BARE_CALL.matcher(lines[i]).find()) {
                    (isMarked(lines, i) ? marked : violations).add(relative + ":" + (i + 1));
                }
            }
        }
    }

    private static boolean isMarked(String[] lines, int index) {
        for (var back = 1; back <= MARKER_REACH && index - back >= 0; back++) {
            if (lines[index - back].contains(MARKER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isComment(String text) {
        return text.startsWith("//") || text.startsWith("*") || text.startsWith("/*");
    }

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.isDirectory(dir.resolve("integrations/swim/src/main/java"))) {
            dir = dir.getParent();
        }
        return dir == null ? Path.of("").toAbsolutePath() : dir;
    }

    private Path plant(String name, String content) throws IOException {
        var file = tmp.resolve("m/src/test/java").resolve(name);

        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return tmp;
    }
}
