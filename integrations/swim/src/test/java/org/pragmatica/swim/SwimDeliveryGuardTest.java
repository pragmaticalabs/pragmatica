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

/// #3568 (the class of #3560): fails the build when a test builds a SWIM protocol other than through
/// `SwimTestProtocols`, and when production code reaches a test seam. One invariant: the executor a test's protocol runs
/// on is chosen at construction, by the one test factory.
///
/// Why: production `onMessage` and the six entry points (`addSeedMember`, `recordTransportHint`, `announceJoin`,
/// `setBootTokens`, `setMembershipEligibility`, `reportSelfQuorum`) never wait. While the probe tick drains, they only
/// enqueue, so a test that asserts on the next line reads the state before the call was applied. A protocol built by
/// `SwimTestProtocols.swimProtocol` runs on a quiescing executor, so every call into it returns after it was applied;
/// a protocol built by the production factory (`SwimProtocol.swimProtocol`, `new SwimProtocol(`) would silently lose
/// that, for every entry point present and future. No per-entry-point scan is needed, or kept.
///
/// Construction scope: every `src/test/java` file under the repository root that mentions `SwimProtocol`, except the
/// factory itself. Comment lines are skipped. The scan is line based, like `FlakeScan`.
/// Production scope: every `src/main/java` file. `quiescingSerialExecutor` may be named only by `SerialExecutor`, and
/// `protocolFactoryForTest` only by its declaring `CoreSwimHealthDetector`; the test factory and its opt-out may be named
/// by no production file.
///
/// The scans prove their own reach: each asserts a minimum file count and that it examined aether sources, and a
/// planted tree that reaches almost nothing must fail that check.
class SwimDeliveryGuardTest {
    private static final Pattern CONSTRUCTION = Pattern.compile("SwimProtocol\\.swimProtocol\\b|SwimProtocol" + "::swimProtocol\\b|new\\s+" + "SwimProtocol\\(");
    private static final Pattern DRIVES_SWIM = Pattern.compile("SwimProtocol");
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", ".git", ".m2-local", "node_modules");
    private static final String FACTORY_FILE = "integrations/swim/src/test/java/org/pragmatica/swim/SwimTestProtocols.java";
    private static final String QUIESCING = "quiescing" + "SerialExecutor";
    private static final String QUIESCING_OWNER = "integrations/swim/src/main/java/org/pragmatica/swim/SerialExecutor.java";
    private static final String FACTORY_SEAM = "protocolFactory" + "ForTest";
    private static final String FACTORY_SEAM_OWNER = "aether/node/src/main/java/org/pragmatica/aether/node/health/CoreSwimHealthDetector.java";
    private static final List<String> TEST_FACTORY_NAMES = List.of("SwimTest" + "Protocols", "swimProtocolWith" + "HeldExecutor");
    private static final int MINIMUM_SWIM_FILES = 30;
    private static final int MINIMUM_AETHER_TEST_FILES = 20;
    private static final int MINIMUM_PRODUCTION_FILES = 1_000;
    private static final int MINIMUM_AETHER_PRODUCTION_FILES = 500;
    private static final String AETHER_TEST_WITNESS = "aether/node/src/test/java/org/pragmatica/aether/node/PhantomSeedMembershipTest.java";

    @TempDir
    Path tmp;

    @Test
    void tests_buildAProtocolOnlyThroughTheTestFactory() throws IOException {
        var scan = scan(repositoryRoot());

        assertThat(scan.examined()).as("positive control: the scan reached this module's tests")
                                   .contains("integrations/swim/src/test/java/org/pragmatica/swim/SwimStateConfinementTest.java");
        assertThat(scan.examined()).as("positive control: the factory itself was reached (and so exempted by name)")
                                   .contains(FACTORY_FILE);
        assertThat(testScanShortfalls(scan)).as("positive control: the scan reached the swim and the aether tests, in number")
                                            .isEmpty();
        assertThat(scan.violations()).as("a test builds a SWIM protocol by the production factory; use SwimTestProtocols.swimProtocol")
                                     .isEmpty();
    }

    @Test
    void scan_aTreeThatReachedAlmostNothing_failsTheBreadthControl() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\n" + constructionLine()));

        assertThat(testScanShortfalls(scan)).hasSize(3);
    }

    @Test
    void scan_aPlantedProductionFactoryCall_isReported() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\n" + constructionLine()));

        assertThat(scan.violations()).containsExactly("m/src/test/java/A.java:2");
    }

    @Test
    void scan_aPlantedFactoryMethodReference_isReported() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\ndetector.x(SwimProtocol::swim" + "Protocol);"));

        assertThat(scan.violations()).containsExactly("m/src/test/java/A.java:2");
    }

    @Test
    void scan_aPlantedDirectConstructor_isReported() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p = new Swim" + "Protocol(c, t, l, i, a, b, f, e);"));

        assertThat(scan.violations()).containsExactly("m/src/test/java/A.java:1");
    }

    @Test
    void scan_theTestFactoryCall_isNotAViolation() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p = SwimTestProtocols.swimProtocol(c, t, l, i, a);"));

        assertThat(scan.violations()).isEmpty();
    }

    @Test
    void scan_aCommentedConstruction_isNotAViolation() throws IOException {
        var scan = scan(plant("A.java", "SwimProtocol p;\n// " + constructionLine() + "\n/// " + constructionLine()));

        assertThat(scan.violations()).isEmpty();
    }

    @Test
    void scan_theFactoryFileItself_isExemptByPath() throws IOException {
        var file = tmp.resolve(FACTORY_FILE);

        Files.createDirectories(file.getParent());
        Files.writeString(file, "SwimProtocol p;\n" + constructionLine());

        assertThat(scan(tmp).violations()).isEmpty();
    }

    /// Condition (a): the quiescing executor and the detector's factory seam are test-only because no main source
    /// reaches them. The owners declare them; nobody else in main names them.
    @Test
    void productionSources_neverCallTheTestSeams() throws IOException {
        var root = repositoryRoot();
        var production = productionScan(root);

        assertThat(production.quiescingCallers()).as("main source other than SerialExecutor names the quiescing factory")
                                                 .isEmpty();
        assertThat(production.factorySeamCallers()).as("main source other than CoreSwimHealthDetector names its test factory seam")
                                                   .isEmpty();
        assertThat(production.testFactoryCallers()).as("main source naming the test protocol factory or its opt-out")
                                                   .isEmpty();
        assertThat(Files.readString(root.resolve(QUIESCING_OWNER))).as("positive control: the scan knows the quiescing owner")
                                                                  .contains(QUIESCING + "(");
        assertThat(Files.readString(root.resolve(FACTORY_SEAM_OWNER))).as("positive control: the scan knows the factory seam's owner")
                                                                      .contains(FACTORY_SEAM + "(");
        assertThat(productionScanShortfalls(production)).as("positive control: the scan reached the production sources of the repository, aether included")
                                                        .isEmpty();
    }

    @Test
    void productionScan_aPlantedQuiescingCall_isReported() throws IOException {
        plantMain("A.java", "class A { void f() { SerialExecutor." + QUIESCING + "(); } }");

        assertThat(productionScan(tmp).quiescingCallers()).containsExactly("m/src/main/java/A.java");
    }

    @Test
    void productionScan_aPlantedFactorySeamCall_isReported() throws IOException {
        plantMain("A.java", "class A { void f() { d." + FACTORY_SEAM + "(f); } }");

        assertThat(productionScan(tmp).factorySeamCallers()).containsExactly("m/src/main/java/A.java");
    }

    @Test
    void productionScan_aPlantedTestFactoryCall_isReported() throws IOException {
        plantMain("A.java", "class A { void f() { " + TEST_FACTORY_NAMES.getFirst() + ".swimProtocol(c); } }");

        assertThat(productionScan(tmp).testFactoryCallers()).containsExactly("m/src/main/java/A.java");
    }

    @Test
    void productionScan_theOwnersDeclarations_areNotCallers() throws IOException {
        plantAt(QUIESCING_OWNER, "class SerialExecutor { static SerialExecutor " + QUIESCING + "() {} }");
        plantAt(FACTORY_SEAM_OWNER, "class D { void " + FACTORY_SEAM + "(F f) {} }");

        var production = productionScan(tmp);

        assertThat(production.quiescingCallers()).isEmpty();
        assertThat(production.factorySeamCallers()).isEmpty();
    }

    @Test
    void productionScan_aTreeThatReachedAlmostNothing_failsTheBreadthControl() throws IOException {
        plantMain("A.java", "class A {}");

        assertThat(productionScanShortfalls(productionScan(tmp))).hasSize(2);
    }

    /// Built from pieces so this file never carries the pattern it scans for.
    private static String constructionLine() {
        return "var p = Swim" + "Protocol.swim" + "Protocol(config, transport, listener, id, address).unwrap();";
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
            || !scan.examined().contains(FACTORY_SEAM_OWNER)) {
            shortfalls.add("the aether production sources were not reached");
        }

        return shortfalls;
    }

    private record Scan(List<String> examined, List<String> violations) {}

    private static Scan scan(Path root) throws IOException {
        var examined = new ArrayList<String>();
        var violations = new ArrayList<String>();

        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var relative = root.relativize(file).toString().replace('\\', '/');

                if (isTestSource(relative)) {
                    scanFile(relative, file, examined, violations);
                }
            }
        }
        return new Scan(examined, violations);
    }

    private record ProductionScan(List<String> examined,
                                  List<String> quiescingCallers,
                                  List<String> factorySeamCallers,
                                  List<String> testFactoryCallers) {}

    private static ProductionScan productionScan(Path root) throws IOException {
        var examined = new ArrayList<String>();
        var quiescing = new ArrayList<String>();
        var factorySeam = new ArrayList<String>();
        var testFactory = new ArrayList<String>();

        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var relative = root.relativize(file).toString().replace('\\', '/');

                if (isProductionSource(relative)) {
                    var text = Files.readString(file);

                    examined.add(relative);
                    addIf(!relative.equals(QUIESCING_OWNER) && text.contains(QUIESCING), relative, quiescing);
                    addIf(!relative.equals(FACTORY_SEAM_OWNER) && text.contains(FACTORY_SEAM), relative, factorySeam);
                    addIf(TEST_FACTORY_NAMES.stream().anyMatch(text::contains), relative, testFactory);
                }
            }
        }
        return new ProductionScan(examined, quiescing, factorySeam, testFactory);
    }

    private static void addIf(boolean condition, String relative, List<String> into) {
        if (condition) {
            into.add(relative);
        }
    }

    private static boolean isProductionSource(String relative) {
        return isJavaUnder(relative, "/src/main/java/");
    }

    private static boolean isTestSource(String relative) {
        return isJavaUnder(relative, "/src/test/java/");
    }

    private static boolean isJavaUnder(String relative, String marker) {
        return relative.endsWith(".java")
               && ("/" + relative).contains(marker)
               && Arrays.stream(relative.split("/")).noneMatch(SKIPPED_DIRECTORIES::contains);
    }

    private static void scanFile(String relative, Path file, List<String> examined, List<String> violations)
        throws IOException {
        var text = Files.readString(file);

        if (DRIVES_SWIM.matcher(text).find()) {
            examined.add(relative);
            var lines = text.split("\\R", -1);

            for (var i = 0; i < lines.length && !relative.equals(FACTORY_FILE); i++) {
                if (!isComment(lines[i].strip()) && CONSTRUCTION.matcher(lines[i]).find()) {
                    violations.add(relative + ":" + (i + 1));
                }
            }
        }
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

    private void plantMain(String name, String content) throws IOException {
        plantAt("m/src/main/java/" + name, content);
    }

    private void plantAt(String relative, String content) throws IOException {
        var file = tmp.resolve(relative);

        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
