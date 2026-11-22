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

/// #3560, #3568: fails the build when a test that drives a SWIM protocol reaches it by a path that can return before
/// the protocol executor applied it. One invariant: a test drives a protocol only through a path that has completed
/// on the executor before it returns.
///
/// Why: production `onMessage` and the six entry points (`addSeedMember`, `recordTransportHint`, `announceJoin`,
/// `setBootTokens`, `setMembershipEligibility`, `reportSelfQuorum`) never wait. While the probe tick drains, they only
/// enqueue, so a test that asserts on the next line reads the state before the call was applied. Tests deliver
/// messages through `deliverForTest` and make entry-point calls inside `driveForTest(() -> ...)`, which wait.
///
/// Scope: every `src/test/java` file under the repository root that mentions `SwimProtocol` or `SwimMessageHandler`
/// (the other modules' tests that drive a protocol are included, not only this module's). Comment lines are skipped.
/// An entry-point call is accepted when `driveForTest(` precedes it on the same line. A deliberate bare call (a
/// simulated network hop that runs inside a task and must not wait, or a pin of the production contract) carries the
/// marker `ASYNC-DELIVERY:` with its reason on its own line or in one of the two lines above it; there is no other
/// allow-list. The scan is line based, like `FlakeScan`: a call split so that its name is not on one line is invisible,
/// and the entry-point names are matched on any receiver (a detector or mock in a file that mentions a protocol
/// needs the wrapper as well).
///
/// The scans prove their own reach: each asserts a minimum file count and that it examined aether sources, and a
/// planted tree that reaches almost nothing must fail that check.
class SwimDeliveryGuardTest {
    private static final String MARKER = "ASYNC-DELIVERY:";
    private static final String ENTRY_POINTS = "addSeedMember|recordTransportHint|announceJoin|setBootTokens|setMembershipEligibility|reportSelfQuorum";
    private static final Pattern BARE_CALL = Pattern.compile("\\.onMessage\\(|(?:\\.|::)(?:" + ENTRY_POINTS + ")\\b");
    private static final Pattern ENTRY_CALL = Pattern.compile("(?:\\.|::)(?:" + ENTRY_POINTS + ")\\b");
    private static final String DRIVE = "drive" + "ForTest(";
    private static final Pattern DRIVES_SWIM = Pattern.compile("SwimProtocol|SwimMessageHandler");
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", ".git", ".m2-local", "node_modules");
    private static final String SEAM = "deliver" + "ForTest";
    private static final String SEAM_OWNER = "integrations/swim/src/main/java/org/pragmatica/swim/SwimProtocol.java";
    private static final int MARKER_REACH = 2;
    private static final int MINIMUM_SWIM_FILES = 30;
    private static final int MINIMUM_AETHER_TEST_FILES = 20;
    private static final int MINIMUM_PRODUCTION_FILES = 1_000;
    private static final int MINIMUM_AETHER_PRODUCTION_FILES = 500;
    private static final String AETHER_PRODUCTION_WITNESS = "aether/node/src/main/java/org/pragmatica/aether/node/health/CoreSwimHealthDetector.java";
    private static final String AETHER_TEST_WITNESS = "aether/node/src/test/java/org/pragmatica/aether/node/PhantomSeedMembershipTest.java";

    @TempDir
    Path tmp;

    @Test
    void swimDrivingTests_neverCallOnMessageDirectly() throws IOException {
        var root = repositoryRoot();
        var scan = scan(root);

        assertThat(scan.examined()).as("positive control: the scan reached this module's tests")
                                   .contains("integrations/swim/src/test/java/org/pragmatica/swim/SwimStateConfinementTest.java");
        assertThat(testScanShortfalls(scan)).as("positive control: the scan reached the swim and the aether tests, in number")
                                            .isEmpty();
        assertThat(scan.marked()).as("positive control: the deliberate async sites are still seen, with their marker")
                                 .isNotEmpty();
        assertThat(scan.violations()).as("bare onMessage or entry-point call in a test that drives a SWIM protocol; use protocol.deliverForTest(sender, message) or protocol.driveForTest(() -> ...)")
                                     .isEmpty();
    }

    @Test
    void scan_aTreeThatReachedAlmostNothing_failsTheBreadthControl() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\n" + bareLine()));

        assertThat(testScanShortfalls(scan)).hasSize(3);
    }

    @Test
    void scan_aBareEntryPointCall_isReported() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\n" + entryLine("")));

        assertThat(scan.violations()).containsExactly("m/src/test/java/A.java:2");
    }

    @Test
    void scan_anEntryPointCallInsideDriveForTest_isNotAViolation() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\n" + entryLine("p." + DRIVE + "() -> ")));

        assertThat(scan.violations()).isEmpty();
    }

    @Test
    void scan_anEntryPointCallAfterTheWrapperOnAnEarlierLine_isReported() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\np." + DRIVE + "() -> q.x());\n" + entryLine("")));

        assertThat(scan.violations()).containsExactly("m/src/test/java/A.java:3");
    }

    @Test
    void scan_anEntryPointMethodReference_isReported() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\nseeds.forEach(p::addSeed" + "Member);"));

        assertThat(scan.violations()).containsExactly("m/src/test/java/A.java:2");
    }

    @Test
    void scan_aMarkerOnTheSameLine_excusesAnEntryPointCall() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\n" + entryLine("") + " // " + MARKER + " pin"));

        assertThat(scan.violations()).isEmpty();
        assertThat(scan.marked()).containsExactly("m/src/test/java/A.java:2");
    }

    @Test
    void scan_aWrapperThatNamesNoEntryPoint_isNotAViolation() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\np." + DRIVE + "() -> p.members());"));

        assertThat(scan.violations()).isEmpty();
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
        var production = productionScan(root);

        assertThat(production.callers()).as("production source referencing a test seam; they are test-only seams")
                                        .isEmpty();
        assertThat(Files.readString(root.resolve(SEAM_OWNER))).as("positive control: the scan knows the seam's owner")
                                                              .contains(SEAM + "(");
        assertThat(productionScanShortfalls(production)).as("positive control: the scan reached the production sources of the repository, aether included")
                                                        .isEmpty();
    }

    @Test
    void productionScan_aPlantedCallInProductionCode_isReported() throws IOException {
        var main = tmp.resolve("m/src/main/java");

        Files.createDirectories(main);
        Files.writeString(main.resolve("A.java"), "class A { void f() { p." + SEAM + "(a, m); } }");

        assertThat(productionScan(tmp).callers()).containsExactly("m/src/main/java/A.java");
    }

    @Test
    void productionScan_aTreeThatReachedAlmostNothing_failsTheBreadthControl() throws IOException {
        var main = tmp.resolve("m/src/main/java");

        Files.createDirectories(main);
        Files.writeString(main.resolve("A.java"), "class A {}");

        assertThat(productionScanShortfalls(productionScan(tmp))).hasSize(2);
    }

    @Test
    void productionScan_aPlantedDriveForTestCall_isReported() throws IOException {
        var main = tmp.resolve("m/src/main/java");

        Files.createDirectories(main);
        Files.writeString(main.resolve("A.java"), "class A { void f() { p." + DRIVE + "() -> q.x()); } }");

        assertThat(productionScan(tmp).callers()).containsExactly("m/src/main/java/A.java");
    }

    /// Built from pieces so this file never carries the pattern it scans for.
    private static String bareLine() {
        return "protocol" + ".onMes" + "sage(addr, message);";
    }

    /// An entry-point call, optionally behind a prefix (the wrapper); the name is built from pieces for the same reason.
    private static String entryLine(String prefix) {
        return prefix + "protocol" + ".addSeed" + "Member(id, address);";
    }

    private static List<String> testScanShortfalls(Scan scan) {
        var shortfalls = new ArrayList<String>();

        if (scan.examined().size() <= MINIMUM_SWIM_FILES) {
            shortfalls.add("fewer than " + MINIMUM_SWIM_FILES + " files examined: " + scan.examined().size());
        }

        if (scan.examined().stream().noneMatch(AETHER_TEST_WITNESS::equals)) {
            shortfalls.add("the aether witness was not examined: " + AETHER_TEST_WITNESS);
        }

        if (scan.examined().stream().filter(file -> file.startsWith("aether/")).count() < MINIMUM_AETHER_TEST_FILES) {
            shortfalls.add("fewer than " + MINIMUM_AETHER_TEST_FILES + " aether test files examined");
        }

        return shortfalls;
    }

    private static List<String> productionScanShortfalls(ProductionScan scan) {
        var shortfalls = new ArrayList<String>();

        if (scan.examined().size() < MINIMUM_PRODUCTION_FILES) {
            shortfalls.add("fewer than " + MINIMUM_PRODUCTION_FILES + " production files examined: " + scan.examined().size());
        }

        if (scan.examined().stream().filter(file -> file.startsWith("aether/")).count() < MINIMUM_AETHER_PRODUCTION_FILES
            || !scan.examined().contains(AETHER_PRODUCTION_WITNESS)) {
            shortfalls.add("the aether production sources were not reached");
        }

        return shortfalls;
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

    private record ProductionScan(List<String> examined, List<String> callers) {}

    private static ProductionScan productionScan(Path root) throws IOException {
        var examined = new ArrayList<String>();
        var callers = new ArrayList<String>();

        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var relative = root.relativize(file).toString().replace('\\', '/');

                if (isProductionSource(relative)) {
                    examined.add(relative);
                    if (!relative.equals(SEAM_OWNER) && referencesATestSeam(Files.readString(file))) {
                        callers.add(relative);
                    }
                }
            }
        }
        return new ProductionScan(examined, callers);
    }

    private static boolean referencesATestSeam(String text) {
        return text.contains(SEAM + "(") || text.contains(DRIVE);
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
                if (!isComment(lines[i].strip()) && isBareCall(lines[i])) {
                    (isMarked(lines, i) ? marked : violations).add(relative + ":" + (i + 1));
                }
            }
        }
    }

    /// A bare `onMessage`, or an entry-point call with no `driveForTest(` before it on the line.
    private static boolean isBareCall(String line) {
        var onMessage = BARE_CALL.matcher(line);

        while (onMessage.find()) {
            if (!ENTRY_CALL.matcher(onMessage.group()).find() || line.indexOf(DRIVE) < 0 || line.indexOf(DRIVE) > onMessage.start()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMarked(String[] lines, int index) {
        if (lines[index].contains(MARKER)) {
            return true;
        }
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
