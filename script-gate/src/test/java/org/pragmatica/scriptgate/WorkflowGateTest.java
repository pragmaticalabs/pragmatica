package org.pragmatica.scriptgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// #1180 - a job whose purpose is to produce integration reports must fail, not warn, when its upload step finds
/// none. `actions/upload-artifact` defaults `if-no-files-found` to `warn`, so a run that produced ZERO failsafe
/// reports (a reactor that halted before the tests ran) leaves the same empty artifact list as a green run.
///
/// The subject is the REAL workflow files. Every `upload-artifact` step whose path names `failsafe-reports` must set
/// `if-no-files-found: error`.
class WorkflowGateTest {
    private static final List<String> WORKFLOWS = List.of("ci.yml", "heavy-forge.yml", "release.yml");

    @Test
    void failsafeReportUploads_failWhenNoReportWasProduced() {
        var steps = new ArrayList<String>();

        WORKFLOWS.forEach(workflow -> steps.addAll(uploadSteps(workflow)));
        var failsafeUploads = steps.stream().filter(step -> step.contains("failsafe-reports")).toList();
        // The count is the evidence: a pass that examined no upload step would prove nothing.
        assertThat(failsafeUploads).as("control: failsafe-report upload steps found in %s", WORKFLOWS)
                  .hasSizeGreaterThanOrEqualTo(3);
        assertThat(failsafeUploads).allSatisfy(step -> assertThat(step).as("upload step:%n%s", step)
                                                                 .contains("if-no-files-found: error"));
    }

    /// #1684 - the standalone test blueprints are outside the reactor, so a reactor build never checks their format.
    /// CI must run `jbct:check` over every `aether/tests/blueprints/*/pom.xml`, refusing an empty glob.
    @Test
    void ci_jbctChecksEveryStandaloneBlueprint() {
        var ci = String.join("\n",
                             read(ScriptRunner.repoRoot().resolve(Path.of(".github", "workflows", "ci.yml"))));

        assertThat(ci).contains("poms=(aether/tests/blueprints/*/pom.xml ")
                  .contains("mvn -B jbct:check -f \"$pom\"")
                  .contains("refusing to continue");
    }

    /// #919 - every example with a `pom.xml` is either aggregated by `examples/pom.xml` (and so compiled, verified and
    /// JBCT-checked with the reactor) or JBCT-checked standalone in CI. An example in neither is built by nothing.
    @Test
    void everyExample_isAggregatedOrCheckedStandalone() {
        var examples = ScriptRunner.repoRoot().resolve("examples");
        var aggregator = String.join("\n", read(examples.resolve("pom.xml")));
        var ci = String.join("\n",
                             read(ScriptRunner.repoRoot().resolve(Path.of(".github", "workflows", "ci.yml"))));
        var dirs = Result.lift(() -> {
            try (var stream = Files.list(examples)) {
                return stream.filter(dir -> Files.exists(dir.resolve("pom.xml")))
                             .map(dir -> dir.getFileName()
                                            .toString())
                             .sorted()
                             .toList();
            }
        }).fold(cause -> fail("Cannot list " + examples + ": " + cause.message()),
                list -> list);

        assertThat(dirs).as("control: examples found").contains("banking", "step-composition", "url-shortener");
        assertThat(dirs).allSatisfy(dir -> assertThat(aggregator.contains("<module>" + dir + "</module>") || ci.contains("examples/" + dir
                                                                                                                        + "/pom.xml")).as("example '%s' is neither an examples/pom.xml module nor JBCT-checked standalone in ci.yml",
                                                                                                                                          dir)
                                                     .isTrue());
    }

    /// Each `actions/upload-artifact` step of `workflow`, as its text: from the step's `- ` line to the next step at
    /// the same indent.
    private static List<String> uploadSteps(String workflow) {
        var lines = read(ScriptRunner.repoRoot().resolve(Path.of(".github", "workflows", workflow)));
        var steps = new ArrayList<String>();

        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).contains("uses: actions/upload-artifact")) {
                continue;
            }

            var start = stepStart(lines, i);
            var indent = indentOf(lines.get(start));
            var end = start + 1;

            while (end < lines.size() && (lines.get(end).isBlank() || indentOf(lines.get(end)) > indent)) {
                end++;
            }

            steps.add(String.join("\n", lines.subList(start, end)));
        }

        return steps;
    }

    private static int stepStart(List<String> lines, int usesLine) {
        var start = usesLine;

        while (start > 0 && !lines.get(start).stripLeading().startsWith("- ")) {
            start--;
        }

        return start;
    }

    private static int indentOf(String line) {
        return line.length() - line.stripLeading()
                                   .length();
    }

    private static List<String> read(Path file) {
        assertThat(file).exists();

        return Result.lift(() -> Files.readAllLines(file)).fold(cause -> fail("Cannot read " + file
                                                                             + ": " + cause.message()),
                                                                lines -> lines);
    }
}
