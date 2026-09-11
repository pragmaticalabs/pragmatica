package org.pragmatica.scriptgate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.scriptgate.ScriptGate.executed;
import static org.pragmatica.scriptgate.ScriptGate.gitFixture;
import static org.pragmatica.scriptgate.ScriptGate.given;
import static org.assertj.core.api.Assertions.assertThat;


/// #1000 - `scripts/changelog-check.sh` exited 0 when its base ref did not resolve, so the fragment
/// gate could pass HAVING EXAMINED NOTHING while printing something indistinguishable from a
/// genuinely clean result.
///
/// `git diff` exits 128 on an unresolvable range, but the call sat inside a process substitution,
/// whose exit status `set -euo pipefail` does not check - pipefail governs pipelines, not `< <(...)`.
/// The 128 was discarded, the array came back empty, and the empty-diff branch reported success.
///
/// Reading the script's TEXT could not catch this: the old and fixed versions both contain the
/// "no changes against" line, in the same place. What separates them is whether the script can still
/// print it when no diff was computed, so every test here RUNS the real script against a real git
/// repository.
///
/// The two refusals are asserted by DISTINCT exit codes, because that is the whole point of the
/// ticket. 2 means the gate could not look; 1 means it looked and refused. A bare `isNotZero` would
/// have accepted the defect's opposite - a gate that fails everything - as a fix.
class ChangelogCheckTest {
    private static final Path SCRIPT = Path.of("scripts", "changelog-check.sh");
    private static final Path SOURCE_FILE = Path.of("core", "src", "main", "java", "Thing.java");
    private static final Path FRAGMENT_FILE = Path.of("changelog.d", "1000-gate-says-what-it-examined.md");
    private static final String SOURCE_TEXT = "class Thing {}\n";

    private static final String FRAGMENT_TEXT = """
                                                ### Fixed (2026-09-11 — #1000: the gate says what it examined)
                                                - A bullet, because a well-formed fragment requires one.
                                                """;

    /// The CI shape, verbatim from `.github/workflows/changelog.yml`, and unresolvable in a fixture
    /// that has no remote - which is the ticket's condition rather than an invented one.
    private static final String ABSENT_BASE = "origin/main";
    private static final int COULD_NOT_LOOK = 2;
    private static final int LOOKED_AND_REFUSED = 1;

    private SyntheticGitRepo repo;
    private Path root;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        root = tempDir;
        repo = gitFixture(tempDir, SyntheticGitRepo.syntheticGitRepo(tempDir));
        given(ScriptRunner.copyExecutable(ScriptRunner.repoRoot().resolve(SCRIPT),
                                          root.resolve(SCRIPT)).mapToUnit());
        given(repo.commitAll("add the checker under test"));
    }

    /// The ticket's defect. Before the fix this exited 0 printing "no changes against origin/main",
    /// with no diff behind it at all.
    @Test
    void changelogCheck_refusesAndSaysItExaminedNothing_whenTheBaseRefDoesNotResolve() {
        given(changeSource());
        given(repo.commitAll("change a source file"));
        var execution = executed(runCheck(ABSENT_BASE));

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(COULD_NOT_LOOK);
        assertThat(execution.output()).contains("does not resolve to a commit")
                  .contains("EXAMINED NOTHING")
                  // The precise false success #1000 reported, with nothing examined behind it.
                  .doesNotContain("no changes against");
    }

    /// Positive control for the refusal above: the SAME unexamined-base condition over a tree with
    /// nothing to report. Without it, the refusal could be a script that has started failing on
    /// everything, and an unresolvable base is not a result whatever the tree holds.
    @Test
    void changelogCheck_refuses_whenTheBaseDoesNotResolveEvenWithNothingToReport() {
        var execution = executed(runCheck(ABSENT_BASE));

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(COULD_NOT_LOOK);
        assertThat(execution.output()).contains("EXAMINED NOTHING");
    }

    /// The honesty half of the ticket: on a pass the gate must report WHAT it examined, so a zero is
    /// visible rather than implied.
    @Test
    void changelogCheck_passesAndReportsTheChangedPathCount_whenAFragmentIsPresent() {
        given(changeSource());
        given(repo.writeFile(FRAGMENT_FILE, FRAGMENT_TEXT));
        given(repo.commitAll("change a source file with its fragment"));
        var execution = executed(runCheck("HEAD~1"));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("2 changed path(s)")
                  .contains("1 fragment(s)")
                  .contains("needs_fragment=true");
    }

    /// The gate must still refuse for its ORIGINAL reason, and with a different code than "could not
    /// look". This is what makes the pair above a discrimination rather than a smoke test.
    ///
    /// No changed-path count is asserted here, and that is the script's contract rather than an
    /// omission: #1000 asks for the count on the PASS line, where a zero would otherwise be implied.
    /// A refusal already names the specific thing it objected to, which is the property that matters
    /// on that path.
    @Test
    void changelogCheck_refusesWithADistinctCode_whenSourcesChangeWithoutAFragment() {
        given(changeSource());
        given(repo.commitAll("change a source file with no fragment"));
        var execution = executed(runCheck("HEAD~1"));

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(LOOKED_AND_REFUSED);
        assertThat(execution.output()).contains("no well-formed changelog.d/").doesNotContain("EXAMINED NOTHING");
    }

    /// Guards the over-correction. An empty diff against a base that DID resolve is a legitimate
    /// pass, and #1000 must not be cured by failing that case too. The pass line now distinguishes
    /// the two silences in its own words.
    @Test
    void changelogCheck_passes_whenTheDiffIsGenuinelyEmpty() {
        var execution = executed(runCheck("HEAD"));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("no changes against HEAD")
                  .contains("0 changed path(s)")
                  .contains("measured and not inferred");
    }

    private Result<Unit> changeSource() {
        return repo.writeFile(SOURCE_FILE, SOURCE_TEXT);
    }

    private Result<ScriptRunner.Execution> runCheck(String base) {
        return ScriptRunner.run(root,
                                Map.of("PR_LABELS", ""),
                                List.of("bash",
                                        root.resolve(SCRIPT).toString(),
                                        base));
    }
}
