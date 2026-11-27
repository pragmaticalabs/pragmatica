package org.pragmatica.scriptgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.testlogging.SourceWalk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #3801 - what the gate's configuration may and may not say, pinned from where the enforcer ([PortBindGateBuildTest]) cannot reach:
/// the antrun plugin's own `skip`, and every file outside the root pom that could carry a different mode or a second copy of the
/// execution. The invariant is that nothing a module (or a command line the repository checks in) can change lets a violation pass.
class PortBindGatePinsTest {
    private static final String MODE_PROPERTY = "port.bind.gate.mode";

    @Test
    void theRootPom_setsTheModeToEnforce() {
        assertThat(PortBindAnt.rootPomText("<" + MODE_PROPERTY + ">", "</" + MODE_PROPERTY + ">")).isEqualTo("enforce");
    }

    @Test
    void theExecution_isSkippedByMavenTestSkipAlone() {
        assertThat(PortBindAnt.executionText("<skip>", "</skip>")).as("no compiled test class means nothing to read; -Dmaven.antrun.skip is deliberately not an off-switch")
                  .isEqualTo("${maven.test.skip}");
    }

    @Test
    void onlyTheRootPom_setsTheMode() {
        var root = ScriptRunner.repoRoot();

        assertThat(mentioning(root, MODE_PROPERTY)).as(".mvn/maven.config, a module pom, a workflow or a script that sets the gate's mode")
                  .containsExactly(root.resolve("pom.xml"));
    }

    @Test
    void theExecutionIsDeclared_byTheRootPomAndTheTwoExamplesWithoutARootParent_only() {
        var root = ScriptRunner.repoRoot();

        assertThat(mentioning(root, PortBindAnt.EXECUTION_ID)).as("a module pom that re-declares the execution can configure it away")
                  .containsExactlyInAnyOrder(root.resolve("examples/url-shortener-v2/pom.xml"),
                                             root.resolve("examples/url-shortener/pom.xml"),
                                             root.resolve("pom.xml"));
    }

    @Test
    void theExamplesOwnCopies_enforce_failTheBuild_andSkipOnlyWithoutTestClasses() {
        var root = ScriptRunner.repoRoot();

        assertThat(List.of("examples/url-shortener/pom.xml", "examples/url-shortener-v2/pom.xml")).allSatisfy(pom -> assertThat(text(root.resolve(pom))).contains("<arg value=\"enforce\"/>")
                                                                                                                               .contains("failonerror=\"true\"")
                                                                                                                               .contains("<skip>${maven.test.skip}</skip>"));
    }

    @Test
    void theCarrierScan_seesTheFilesItGuards() {
        var root = ScriptRunner.repoRoot();
        var carriers = carriers(root);

        assertThat(carriers).contains(root.resolve("pom.xml"),
                                      root.resolve("script-gate/pom.xml"),
                                      root.resolve(".mvn/maven.config"));
        assertThat(carriers).anyMatch(path -> path.startsWith(root.resolve(".github/workflows")));
    }

    private static List<Path> mentioning(Path root, String text) {
        return carriers(root).stream()
                       .filter(path -> text(path).contains(text))
                       .toList();
    }

    private static String text(Path path) {
        return Result.lift(() -> Files.readString(path)).unwrap();
    }

    private static List<Path> carriers(Path root) {
        return Result.lift(() -> SourceWalk.files(root))
                     .map(files -> files.stream()
                                        .filter(PortBindGatePinsTest::carrier)
                                        .toList())
                     .unwrap();
    }

    private static boolean carrier(Path path) {
        var name = path.getFileName().toString();

        return name.equals("pom.xml") || name.equals("maven.config") || name.endsWith(".sh") || workflow(path);
    }

    private static boolean workflow(Path path) {
        var name = path.getFileName().toString();

        return path.toString()
                   .contains("/.github/") && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }
}
