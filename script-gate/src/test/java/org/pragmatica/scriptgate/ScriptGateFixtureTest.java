package org.pragmatica.scriptgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Positive control for this module's own instrument.
///
/// [ScriptRunner#repoRoot] is total: when it finds nothing it returns the working directory. That
/// makes every other test in the module report a missing file rather than a wrong verdict, but only
/// if something checks that the located root really holds the subjects. This is that check. Without
/// it, a module layout change would turn the whole suite into tests of an empty directory, which
/// pass or fail for reasons unrelated to the scripts.
class ScriptGateFixtureTest {
    private static final List<String> EXAMPLES = List.of("ecommerce",
                                                         "pricing-engine",
                                                         "step-composition",
                                                         "url-shortener");

    @Test
    void repoRoot_holdsEverySubjectThisModuleGates() {
        var root = ScriptRunner.repoRoot();

        assertThat(root.resolve("forge.sh")).as("forge.sh - subject of #865").isRegularFile();
        assertThat(root.resolve("tools/forge-freshness.py")).as("the freshness checker forge.sh delegates to")
                  .isRegularFile();
        EXAMPLES.forEach(example -> assertThat(root.resolve("examples/" + example + "/start-postgres.sh")).as("subject of #952")
                                              .isRegularFile());
    }

    /// The four example scripts are copies of one another, and they had already drifted once - the
    /// examples moved to postgres:18 while the scaffold template that generates the same script
    /// stayed on 17. Byte identity is what lets the behavioural tests run ONE of them and still
    /// speak about all four; without it, running one proves one.
    @Test
    void startPostgresScripts_areIdenticalAcrossEveryExample() {
        var reference = scriptText("pricing-engine");

        EXAMPLES.forEach(example -> assertThat(scriptText(example)).as("%s must not drift from pricing-engine", example)
                                              .isEqualTo(reference));
    }

    @Test
    void startPostgresScripts_areExecutable() {
        EXAMPLES.forEach(example -> assertThat(Files.isExecutable(scriptPath(example))).as("%s script must be executable",
                                                                                           example)
                                              .isTrue());
    }

    @Test
    void freshnessChecker_isExecutable() {
        assertThat(Files.isExecutable(ScriptRunner.repoRoot().resolve("tools/forge-freshness.py"))).isTrue();
    }

    private static Path scriptPath(String example) {
        return ScriptRunner.repoRoot().resolve("examples/" + example + "/start-postgres.sh");
    }

    private static String scriptText(String example) {
        return Result.lift(() -> Files.readString(scriptPath(example))).or("<unreadable: " + example + ">");
    }
}
