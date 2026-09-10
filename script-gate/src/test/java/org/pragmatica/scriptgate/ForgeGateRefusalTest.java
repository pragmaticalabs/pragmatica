package org.pragmatica.scriptgate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #865 — the wiring, which the checker's own tests cannot speak to.
///
/// [ForgeFreshnessGateTest] proves `tools/forge-freshness.py` reaches the right verdict. It says
/// nothing about whether `forge.sh` CALLS it, or whether it does so before Maven starts, and a fix
/// that computes a correct verdict and then runs the suite anyway would leave that suite green.
///
/// So `mvn` is stubbed on PATH and records its own invocation. A refusal is only a refusal if that
/// marker is ABSENT, and the marker is what separates "the gate refused" from "the fixture was
/// broken and nothing ran" - which is why [#freshRuntime_reachesMaven] drives the same fixture to
/// the opposite outcome and asserts the marker IS written.
class ForgeGateRefusalTest {
    private static final String STUB_MVN = """
                                           #!/bin/sh
                                           echo "invoked: $*" >> "$MVN_MARKER"
                                           exit 0
                                           """;

    private SyntheticRepo repo;
    private Path root;
    private Path marker;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        repo = SyntheticRepo.create(tempDir, List.of("runtime-a"));
        root = tempDir;
        marker = root.resolve("mvn-was-invoked.txt");

        var source = ScriptRunner.repoRoot();

        ScriptRunner.copyExecutable(source.resolve("forge.sh"), root.resolve("forge.sh"));
        ScriptRunner.copyExecutable(source.resolve("tools/forge-freshness.py"), root.resolve("tools/forge-freshness.py"));
        ScriptRunner.writeExecutable(root.resolve("stub/mvn"), STUB_MVN);
    }

    /// The refusal has to happen BEFORE the suite runs, or it is advice rather than a gate.
    @Test
    void staleRuntime_refusesBeforeMavenRuns() throws Exception {
        SyntheticRepo.touch(repo.sourceOf("runtime-a"), SyntheticRepo.EDIT_TIME);

        var execution = runForge(Map.of());

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(1);
        assertThat(execution.output()).contains("FORGE GATE REFUSED TO RUN")
                                      .contains("STALE")
                                      .contains("runtime-a")
                                      .contains("Run ./build.sh");
        assertThat(marker).as("Maven must not have started: %s", execution.output()).doesNotExist();
    }

    /// An absent checker is not a checker that passed. Both silences look identical from the exit
    /// status alone, so this asserts the message that tells them apart.
    @Test
    void missingChecker_refusesRatherThanAssumingFreshness() throws Exception {
        Files.delete(root.resolve("tools/forge-freshness.py"));

        var execution = runForge(Map.of());

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(2);
        assertThat(execution.output()).contains("FRESHNESS CHECK DID NOT RUN")
                                      .contains("not a checker that passed");
        assertThat(marker).as("Maven must not have started: %s", execution.output()).doesNotExist();
    }

    /// Positive control for the two refusals above. Same fixture, same stub, opposite outcome: if
    /// this did not reach Maven, "it refused" and "it never worked" would be indistinguishable.
    @Test
    void freshRuntime_reachesMaven() throws Exception {
        var execution = runForge(Map.of());

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("examined 1 module(s): 1 fresh, 0 stale")
                                      .contains("FORGE GATE PASSED");
        assertThat(marker).as("Maven should have run: %s", execution.output()).exists();
        assertThat(Files.readString(marker)).contains("aether/forge/forge-tests");
    }

    /// The override exists so the gate cannot become a wall, but a run that skipped the check must
    /// say the run is not evidence - otherwise its green is indistinguishable from a verified one.
    @Test
    void explicitOverride_runsAnywayAndSaysTheRunProvesNothing() throws Exception {
        SyntheticRepo.touch(repo.sourceOf("runtime-a"), SyntheticRepo.EDIT_TIME);

        var execution = runForge(Map.of("FORGE_SKIP_FRESHNESS", "1"));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("SKIPPED")
                                      .contains("NOT evidence about this tree");
        assertThat(marker).as("the override must actually run the suite: %s", execution.output()).exists();
    }

    private ScriptRunner.Execution runForge(Map<String, String> extraEnvironment) throws IOException, InterruptedException {
        var environment = new HashMap<String, String>();

        environment.put("PATH", root.resolve("stub") + ":" + System.getenv("PATH"));
        environment.put("MVN_MARKER", marker.toString());
        environment.putAll(extraEnvironment);

        return ScriptRunner.run(root, environment, List.of("./forge.sh", "smoke"));
    }
}
