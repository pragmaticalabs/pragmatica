package org.pragmatica.scriptgate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Result;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.scriptgate.ScriptGate.executed;
import static org.pragmatica.scriptgate.ScriptGate.fixture;
import static org.pragmatica.scriptgate.ScriptGate.given;
import static org.assertj.core.api.Assertions.assertThat;


/// #865 - `tools/forge-freshness.py`, driven against synthetic trees whose mtimes are set exactly.
///
/// The subject is the REAL checker; only the tree is synthetic. Times are assigned rather than
/// produced by sleeping, so "stale" and "fresh" are facts about the fixture and not about how long
/// the suite took to run.
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
    void setUp(@TempDir Path tempDir) {
        root = tempDir;
        repo = fixture(tempDir,
                       SyntheticRepo.syntheticRepo(tempDir, List.of("runtime-a", "runtime-b")));
    }

    @Test
    void freshTree_passesAndReportsWhatItExamined() {
        var execution = executed(runChecker());

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        // The COUNT is the evidence. A pass with no number attached is the thing #865 is about.
        assertThat(execution.output()).contains("examined 2 module(s): 2 fresh, 0 stale, 0 with no installed artifact")
                  .contains("installed artifacts exercised");
    }

    /// The ticket's own acceptance criterion: touch a source file, and the run must fail with the
    /// module named.
    @Test
    void touchedSource_refusesAndNamesTheModule() {
        given(SyntheticRepo.touch(repo.sourceOf("runtime-a"), SyntheticRepo.EDIT_TIME));
        var execution = executed(runChecker());

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(1);
        assertThat(execution.output()).contains("STALE")
                  .contains("runtime-a")
                  .contains("Runtime.java")
                  .contains("examined 2 module(s): 1 fresh, 1 stale");
    }

    /// A resource that ships inside the jar is source for this purpose; only counting `.java` would
    /// miss it. `aether/aether-ttm-onnx` in the live tree is stale for exactly this reason.
    @Test
    void touchedResource_refusesJustLikeTouchedJava() {
        var resource = root.resolve("runtime-b/src/main/resources/service.properties");

        given(repo.writeResource(resource));
        given(SyntheticRepo.touch(resource, SyntheticRepo.EDIT_TIME));
        var execution = executed(runChecker());

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(1);
        assertThat(execution.output()).contains("runtime-b").contains("service.properties");
    }

    /// An artifact that is not installed cannot be evidence of freshness. Folding it into the fresh
    /// count is the precise shape of a gate reporting green having examined nothing.
    @Test
    void absentArtifact_isNamedAndNeverCountedAsFresh() {
        given(SyntheticRepo.delete(repo.jarOf("runtime-b")));
        var execution = executed(runChecker());

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("examined 1 module(s): 1 fresh, 0 stale, 1 with no installed artifact")
                  .contains("NOT INSTALLED")
                  .contains("runtime-b");
    }

    @Test
    void noArtifactAtAll_isIndeterminateRatherThanGreen() {
        given(SyntheticRepo.delete(repo.jarOf("runtime-a")));
        given(SyntheticRepo.delete(repo.jarOf("runtime-b")));
        var execution = executed(runChecker());

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(2);
        assertThat(execution.output()).contains("FRESHNESS CHECK EXAMINED ZERO MODULES").contains("says NOTHING");
    }

    @Test
    void missingLocalRepository_isIndeterminateRatherThanGreen() {
        given(repo.mavenConfig("no-such-repository"));
        var execution = executed(runChecker());

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(2);
        assertThat(execution.output()).contains("FRESHNESS CHECK EXAMINED ZERO MODULES")
                  .contains("local repository directory does not exist");
    }

    @Test
    void absentForgeModule_isIndeterminateRatherThanGreen() {
        given(SyntheticRepo.delete(repo.forgePom()));
        var execution = executed(runChecker());

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(2);
        assertThat(execution.output()).contains("FRESHNESS CHECK EXAMINED ZERO MODULES")
                  .contains("forge-tests module was not found");
    }

    /// Scope is forge-tests' dependency closure, and this is the test that makes that claim
    /// checkable. A checker that swept the whole tree would refuse here - and would then refuse so
    /// often, for edits the gate never executes, that the override became habitual.
    @Test
    void staleModuleOutsideTheClosure_doesNotRefuse() {
        given(repo.addModule("unrelated"));
        given(repo.install("unrelated"));
        given(SyntheticRepo.touch(repo.sourceOf("unrelated"), SyntheticRepo.EDIT_TIME));
        var execution = executed(runChecker());

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("examined 2 module(s): 2 fresh, 0 stale").doesNotContain("STALE");
    }

    private Result<ScriptRunner.Execution> runChecker() {
        var checker = ScriptRunner.repoRoot().resolve("tools/forge-freshness.py");

        return ScriptRunner.run(root,
                                Map.of(),
                                List.of("python3", checker.toString(), root.toString()));
    }
}
