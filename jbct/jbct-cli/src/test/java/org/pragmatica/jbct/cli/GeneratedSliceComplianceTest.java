package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.init.SliceProjectInitializer;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.JbctLinter;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Structural drift gate for the `jbct init` scaffold (issue #511).
///
/// The scaffold's own `pom.xml` binds `jbct:format-check` + `jbct:lint` to the build, so the very
/// first `./run-forge.sh` a cold user runs will FAIL if the generated sources drift from the current
/// formatter/linter. This test runs `jbct init` into a temp dir and drives the generated Java through
/// the SAME rc3 [JbctFormatter#isFormatted] and [JbctLinter#lint] the build gate uses — so any future
/// template↔tool drift breaks this fast test instead of the cold user. It is the same one-source-of-
/// truth doctrine as the codec-registry and retry-divergence gates.
///
/// Kept fast + hermetic (no Maven, no network): the heavy `run-forge.sh → hello` leg lives in
/// `aether/forge/forge-tests` (GeneratedSliceForgeE2ETest).
class GeneratedSliceComplianceTest {
    private static final String VERSION = "1.0.0-rc4";

    @TempDir
    Path tempDir;

    @Test
    void generatedSources_currentFormatter_reportNoFormatDrift() {
        var files = generateScaffold();

        for (var file : allJavaSources(files)) {
            assertThat(isFormatted(file))
                      .as("format-check must pass on generated %s (run 'mvn jbct:format' after editing templates)",
                          file.getFileName())
                      .isTrue();
        }
    }

    @Test
    void generatedMainSources_currentLinter_reportNoLintErrors() {
        var files = generateScaffold();

        for (var file : mainJavaSources(files)) {
            assertThat(lintDiagnostics(file, DiagnosticSeverity.ERROR))
                      .as("generated main source %s must have no lint errors", file.getFileName())
                      .isEmpty();
        }
    }

    @Test
    void generatedSliceInterface_currentLinter_isFullyCompliant() {
        var files = generateScaffold();
        var slice = mainJavaSources(files).stream()
                                          .filter(p -> p.getFileName().toString().equals("HelloWorld.java"))
                                          .findFirst()
                                          .orElseThrow();

        assertThat(lintDiagnostics(slice, DiagnosticSeverity.ERROR))
                  .as("scaffold slice interface must have zero lint errors")
                  .isEmpty();
        assertThat(lintDiagnostics(slice, DiagnosticSeverity.WARNING))
                  .as("scaffold slice interface is the JBCT exemplar and must have zero lint warnings")
                  .isEmpty();
    }

    private List<Path> generateScaffold() {
        var projectDir = tempDir.resolve("hello");

        return SliceProjectInitializer.sliceProjectInitializer(projectDir,
                                                               "org.example",
                                                               "hello",
                                                               "HelloWorld",
                                                               VERSION,
                                                               VERSION,
                                                               VERSION)
                                      .flatMap(SliceProjectInitializer::initialize)
                                      .onFailure(cause -> fail("Scaffold generation failed: " + cause.message()))
                                      .or(List.of());
    }

    /// Every generated `.java` — the scaffold binds `format-check` with `includeTests=true`.
    private List<Path> allJavaSources(List<Path> files) {
        return files.stream()
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
    }

    /// Only `src/main/java` sources — the scaffold binds `lint` with `includeTests=false`, so the
    /// build never lints test sources (void `@Test` methods are standard JUnit, not a JBCT slice).
    private List<Path> mainJavaSources(List<Path> files) {
        return allJavaSources(files).stream()
                                    .filter(p -> p.toString().contains(mainMarker()))
                                    .toList();
    }

    private static String mainMarker() {
        return Path.of("src", "main", "java").toString();
    }

    private boolean isFormatted(Path file) {
        var formatter = JbctFormatter.jbctFormatter(config().formatter());

        return SourceFile.sourceFile(file)
                         .flatMap(formatter::isFormatted)
                         .onFailure(cause -> fail("Format check errored on " + file + ": " + cause.message()))
                         .or(false);
    }

    private List<Diagnostic> lintDiagnostics(Path file, DiagnosticSeverity severity) {
        var linter = JbctLinter.jbctLinter(context(config()));

        return SourceFile.sourceFile(file)
                         .flatMap(linter::lint)
                         .onFailure(cause -> fail("Lint errored on " + file + ": " + cause.message()))
                         .or(List.<Diagnostic>of())
                         .stream()
                         .filter(d -> d.severity() == severity)
                         .toList();
    }

    private JbctConfig config() {
        return ConfigLoader.load(Option.some(tempDir.resolve("hello").resolve("jbct.toml")), Option.none());
    }

    private LintContext context(JbctConfig jbctConfig) {
        return LintContext.defaultContext()
                          .withConfig(jbctConfig.lint())
                          .withExcludePackages(jbctConfig.excludePackages())
                          .withLayers(jbctConfig.layers());
    }
}
