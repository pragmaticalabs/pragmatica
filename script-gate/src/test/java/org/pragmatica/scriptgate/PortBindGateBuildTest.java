package org.pragmatica.scriptgate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.portbind.PortBindScan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// #3801 - the ENFORCER: in `enforce` mode a module whose compiled test classes hold a bind call site that is not allowed FAILS ITS
/// BUILD, and nothing in the gate's own code (the mode default, the mode check, the exit status, the `failonerror` of the
/// pom's `java` task, the scan's verdict) lets that pass silently.
///
/// Two layers, each from the side that fails differently:
///  - THE BUILD: [PortBindAnt] executes the root pom's own antrun `<target>` (same `java` task, same arguments, same
///    `failonerror`) over a fixture with a violation and over one without. A violation is a failed build that names the
///    `class:line`; the control passes and leaves its stamp.
///  - THE ENTRY POINT: [PortBindScan#run], the status the build's failure hangs on, in process: the default mode, a refused
///    argument list and mode, a missing directory, the enforce and report verdicts.
///
/// Every failing input here is the input a mutant of the gate's own code would turn into a pass; the fixtures are compiled from
/// source at test time (see [PortBindFixtures]) because this module's own test classes are scanned.
class PortBindGateBuildTest {
    private static final String MODULE = "fixture-module";
    private static final String ENFORCE = "enforce";
    private static final String REPORT = "report";

    @Test
    void aViolationInEnforceMode_failsTheBuild_namingTheSite(@TempDir Path root) {
        var outcome = PortBindAnt.build(root, MODULE, PortBindFixtures.compiled(root), ENFORCE);
        var line = PortBindFixtures.lineOf(PortBindFixtures.FORMS, "new ServerSocket(computedPort + 1)");

        assertThat(outcome.passed()).as(outcome.log()).isFalse();
        assertThat(outcome.log()).contains("[port-bind-gate] fx.Forms:" + line
                                          + " (computed) calls java.net.ServerSocket#<init>")
                  .contains("Java returned: 1");
    }

    @Test
    void aModuleWithoutABindCall_passesTheBuild_andLeavesItsStamp(@TempDir Path root) {
        var outcome = PortBindAnt.build(root, MODULE, PortBindFixtures.compiledClean(root), ENFORCE);

        assertThat(outcome.passed()).as(outcome.log()).isTrue();
        assertThat(outcome.log()).contains(MODULE + ": scanned 1 test class file(s), 0 bind call site(s), 0 not allowed");
        assertThat(stamp(root)).first().asString().endsWith(", mode=enforce");
    }

    @Test
    void reportMode_listsTheSitesAndPassesTheBuild(@TempDir Path root) {
        var outcome = PortBindAnt.build(root, MODULE, PortBindFixtures.compiled(root), REPORT);

        assertThat(outcome.passed()).as(outcome.log()).isTrue();
        assertThat(outcome.log()).contains("PORT-BIND-SITE " + MODULE + " fx.Forms:").contains(" VIOLATION");
        assertThat(stamp(root)).first().asString().endsWith(", mode=report");
    }

    @Test
    void anUnknownMode_failsTheBuild_itIsNotTreatedAsEnforce_orAsAPass(@TempDir Path root) {
        var outcome = PortBindAnt.build(root, MODULE, PortBindFixtures.compiledClean(root), "enfroce");

        assertThat(outcome.passed()).as(outcome.log()).isFalse();
        assertThat(outcome.log()).contains("mode must be enforce or report, was enfroce");
        assertThat(root.resolve(PortBindScan.STAMP)).doesNotExist();
    }

    @Test
    void aMissingClassesDirectory_failsTheBuild_theGateHasNothingToRead(@TempDir Path root) {
        var outcome = PortBindAnt.build(root, MODULE, root.resolve("no-such-test-classes"), ENFORCE);

        assertThat(outcome.passed()).as(outcome.log()).isFalse();
        assertThat(outcome.log()).contains("does not exist: the gate has nothing to read, which is not a pass");
    }

    @Test
    void aStaleAllowListEntryAlone_failsTheBuild(@TempDir Path root) {
        var staleModule = PortBindScan.bundledAllowList().entries().getFirst().module();
        var outcome = PortBindAnt.build(root, staleModule, PortBindFixtures.compiledClean(root), ENFORCE);

        assertThat(outcome.passed()).as(outcome.log()).isFalse();
        assertThat(outcome.log()).contains("stale allow-list entry in " + staleModule).contains("0 not allowed");
    }

    @Test
    void run_withoutAMode_enforces(@TempDir Path root) {
        var run = run(List.of(MODULE,
                              PortBindFixtures.compiled(root).toString()));

        assertThat(run.status()).as(run.err()).isEqualTo(1);
        assertThat(run.err()).contains("[port-bind-gate] fx.Forms:");
    }

    @Test
    void run_inEnforceMode_failsWithTheSitesOnTheErrorStream_andListsNoReportLines(@TempDir Path root) {
        var run = run(List.of(MODULE,
                              PortBindFixtures.compiled(root).toString(),
                              ENFORCE));

        assertThat(run.status()).isEqualTo(1);
        assertThat(run.err()).contains("calls java.net.ServerSocket#<init>")
                  .contains("fix: bind the port through org.pragmatica.portbind.HeldPort");
        assertThat(run.out()).contains("not allowed").doesNotContain("PORT-BIND-SITE");
    }

    @Test
    void run_inReportMode_passes_andListsEverySiteWithItsVerdict(@TempDir Path root) {
        var run = run(List.of(MODULE,
                              PortBindFixtures.compiled(root).toString(),
                              REPORT));

        assertThat(run.status()).isZero();
        assertThat(run.err()).isEmpty();
        assertThat(run.out()).contains("PORT-BIND-SITE " + MODULE + " fx.Forms:")
                  .contains("java.net.ServerSocket#<init> VIOLATION");
    }

    @Test
    void run_onACleanModule_passes_inBothModes(@TempDir Path root) {
        var classes = PortBindFixtures.compiledClean(root).toString();

        assertThat(run(List.of(MODULE, classes, ENFORCE)).status()).isZero();
        assertThat(run(List.of(MODULE, classes)).status()).isZero();
        assertThat(run(List.of(MODULE, classes, REPORT)).status()).isZero();
    }

    @Test
    void run_refusesAnArgumentListOfTheWrongSize(@TempDir Path root) {
        var classes = PortBindFixtures.compiledClean(root).toString();

        assertThat(run(List.of(MODULE)).status()).isEqualTo(1);
        assertThat(run(List.of(MODULE)).err()).contains("usage: PortBindScan");
        assertThat(run(List.of(MODULE, classes, ENFORCE, "extra")).status()).isEqualTo(1);
    }

    @Test
    void run_refusesAnUnknownMode_andAMissingDirectory(@TempDir Path root) {
        var classes = PortBindFixtures.compiledClean(root).toString();

        assertThat(run(List.of(MODULE, classes, "")).status()).as("an empty mode is not enforce").isEqualTo(1);
        assertThat(run(List.of(MODULE, classes, "Enforce")).err()).contains("mode must be enforce or report, was Enforce");
        assertThat(run(List.of(MODULE,
                               root.resolve("missing").toString())).status()).isEqualTo(1);
        assertThat(run(List.of(MODULE,
                               root.resolve("missing").toString(),
                               REPORT)).status()).as("report does not excuse a missing directory")
                  .isEqualTo(1);
    }

    private record Run(int status, String out, String err) {}

    private static Run run(List<String> arguments) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var status = Result.lift(() -> PortBindScan.run(arguments.toArray(String[]::new),
                                                        new PrintStream(out, true, StandardCharsets.UTF_8),
                                                        new PrintStream(err, true, StandardCharsets.UTF_8))).unwrap();

        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static List<String> stamp(Path root) {
        return Result.lift(() -> Files.readAllLines(root.resolve(PortBindScan.STAMP))).unwrap();
    }
}
