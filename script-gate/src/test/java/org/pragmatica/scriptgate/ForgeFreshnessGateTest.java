package org.pragmatica.scriptgate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #865 — `tools/forge-freshness.py`, driven against synthetic trees whose mtimes are set exactly.
///
/// The subject is the REAL checker; only the tree is synthetic. Times are assigned with
/// [Files#setLastModifiedTime] rather than by sleeping, so "stale" and "fresh" are facts about the
/// fixture and not about how long the suite took to run.
///
/// The matrix is built so that no single wrong implementation can satisfy all of it. A checker that
/// always refuses fails [#freshTree_passesAndReportsWhatItExamined]; one that always passes fails
/// [#touchedSource_refusesAndNamesTheModule]; one that treats an absent artifact as freshness fails
/// [#absentArtifact_isNamedAndNeverCountedAsFresh]; and one that simply scans every module in the
/// tree fails [#staleModuleOutsideTheClosure_doesNotRefuse].
class ForgeFreshnessGateTest {
    private SyntheticRepo repo;
    private Path root;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        repo = SyntheticRepo.create(tempDir, List.of("runtime-a", "runtime-b"));
        root = tempDir;
    }

    @Test
    void freshTree_passesAndReportsWhatItExamined() throws Exception {
        var execution = runChecker();

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        // The COUNT is the evidence. A pass with no number attached is the thing #865 is about.
        assertThat(execution.output()).contains("examined 2 module(s): 2 fresh, 0 stale, 0 with no installed artifact")
                                      .contains("installed artifacts exercised");
    }

    /// The ticket's own acceptance criterion: touch a source file, and the run must fail with the
    /// module named.
    @Test
    void touchedSource_refusesAndNamesTheModule() throws Exception {
        SyntheticRepo.touch(repo.sourceOf("runtime-a"), SyntheticRepo.EDIT_TIME);

        var execution = runChecker();

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(1);
        assertThat(execution.output()).contains("STALE")
                                      .contains("runtime-a")
                                      .contains("Runtime.java")
                                      .contains("examined 2 module(s): 1 fresh, 1 stale");
    }

    /// A resource that ships inside the jar is source for this purpose; only counting `.java` would
    /// miss it. `aether/aether-ttm-onnx` in the live tree is stale for exactly this reason.
    @Test
    void touchedResource_refusesJustLikeTouchedJava() throws Exception {
        var resource = root.resolve("runtime-b/src/main/resources/service.properties");

        Files.createDirectories(resource.getParent());
        Files.writeString(resource, "key=value\n");
        SyntheticRepo.touch(resource, SyntheticRepo.EDIT_TIME);

        var execution = runChecker();

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(1);
        assertThat(execution.output()).contains("runtime-b")
                                      .contains("service.properties");
    }

    /// An artifact that is not installed cannot be evidence of freshness. Folding it into the fresh
    /// count is the precise shape of a gate reporting green having examined nothing.
    @Test
    void absentArtifact_isNamedAndNeverCountedAsFresh() throws Exception {
        Files.delete(repo.jarOf("runtime-b"));

        var execution = runChecker();

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("examined 1 module(s): 1 fresh, 0 stale, 1 with no installed artifact")
                                      .contains("NOT INSTALLED")
                                      .contains("runtime-b");
    }

    @Test
    void noArtifactAtAll_isIndeterminateRatherThanGreen() throws Exception {
        Files.delete(repo.jarOf("runtime-a"));
        Files.delete(repo.jarOf("runtime-b"));

        var execution = runChecker();

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(2);
        assertThat(execution.output()).contains("FRESHNESS CHECK EXAMINED ZERO MODULES")
                                      .contains("says NOTHING");
    }

    @Test
    void missingLocalRepository_isIndeterminateRatherThanGreen() throws Exception {
        SyntheticRepo.deleteTree(root.resolve(".m2-local"));

        var execution = runChecker();

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(2);
        assertThat(execution.output()).contains("FRESHNESS CHECK EXAMINED ZERO MODULES")
                                      .contains("local repository directory does not exist");
    }

    @Test
    void absentForgeModule_isIndeterminateRatherThanGreen() throws Exception {
        SyntheticRepo.deleteTree(root.resolve("aether"));

        var execution = runChecker();

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(2);
        assertThat(execution.output()).contains("FRESHNESS CHECK EXAMINED ZERO MODULES")
                                      .contains("forge-tests module was not found");
    }

    /// Scope is forge-tests' dependency closure, and this is the test that makes that claim
    /// checkable. A checker that swept the whole tree would refuse here — and would then refuse so
    /// often, for edits the gate never executes, that the override became habitual.
    @Test
    void staleModuleOutsideTheClosure_doesNotRefuse() throws Exception {
        repo.addModule("unrelated");
        repo.install("unrelated");
        SyntheticRepo.touch(repo.sourceOf("unrelated"), SyntheticRepo.EDIT_TIME);

        var execution = runChecker();

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("examined 2 module(s): 2 fresh, 0 stale")
                                      .doesNotContain("STALE");
    }

    private ScriptRunner.Execution runChecker() throws IOException, InterruptedException {
        var checker = ScriptRunner.repoRoot().resolve("tools/forge-freshness.py");

        return ScriptRunner.run(root, Map.of(), List.of("python3", checker.toString(), root.toString()));
    }

}
