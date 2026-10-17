package org.pragmatica.scriptgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// #1662 - the hierarchy selection check must run on EVERY pull request. #1545 deleted two selected classes and merged
/// without the `run-hierarchy` label; the only job that checked the selection was label-gated, reported skipped, and
/// every release push failed from then on. The `selection` job therefore carries no `if:`, needs no build, and reads
/// the same workflow-level list the runtime job runs.
///
/// The subject is the REAL workflow file.
class HierarchySelectionWorkflowTest {
    private static final Path WORKFLOW = Path.of(".github", "workflows", "hierarchy-runtime.yml");

    @Test
    void selectionJob_everyPullRequest_runsCheckerWithoutLabelCondition() {
        var lines = read(ScriptRunner.repoRoot().resolve(WORKFLOW));
        var selection = block(lines, "  selection:", "  ");
        var runtime = block(lines, "  runtime-acceptance:", "  ");
        var pullRequest = block(lines, "  pull_request:", "  ");
        // Control: the extractor sees a job-level `if:` where one exists, so its absence below is not a blind read.
        assertThat(runtime).as("control: runtime-acceptance block").anyMatch(line -> line.startsWith("    if:"));
        assertThat(selection).as("selection job block")
                  .isNotEmpty()
                  .noneMatch(line -> line.startsWith("    if:"))
                  .anyMatch(line -> line.contains("python3 tools/check-hierarchy-selection.py \"$HIERARCHY_TESTS\""))
                  .anyMatch(line -> line.contains("test_hierarchy_selection.py"))
                  .noneMatch(line -> line.contains("build.sh") || line.contains("forge.sh") || line.contains("mvn "));
        assertThat(pullRequest).as("pull_request trigger").isNotEmpty().noneMatch(line -> line.contains("paths"));
        assertThat(lines).as("the list is workflow-level, visible to both jobs")
                  .containsSubsequence("env:", "jobs:")
                  .anyMatch(line -> line.startsWith("  HIERARCHY_TESTS: "));
    }

    /// Lines after `header` up to the next line at the same indentation.
    private static List<String> block(List<String> lines, String header, String indent) {
        var start = lines.indexOf(header);

        if (start < 0) {
            return List.of();
        }

        var end = start + 1;

        while (end < lines.size() && (lines.get(end).isBlank() || lines.get(end).startsWith(indent + " ") || lines.get(end)
                                                                                                                  .startsWith(indent
                                                                                                                             + "#"))) {
            end++;
        }

        return lines.subList(start + 1, end);
    }

    private static List<String> read(Path path) {
        return Result.lift(() -> Files.readAllLines(path)).fold(cause -> fail("Cannot read " + path
                                                                             + ": " + cause.message()),
                                                                list -> list);
    }
}
