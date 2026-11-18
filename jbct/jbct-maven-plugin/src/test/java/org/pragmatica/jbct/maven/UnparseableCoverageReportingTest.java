package org.pragmatica.jbct.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.jbct.lint.cst.RunOutcome.Level;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;


/// Regression gate for #977 on the Maven side: the goals counted files they could not parse among the
/// files they had checked, and named the wrong reason when they failed because of them.
///
/// Two defects, one cause. The summary line reported counts taken over the ANALYSED set against the
/// COLLECTED denominator — so `Check results: 0 lint error(s)` beside a file the parser rejected read
/// as clean when it meant unexamined. And the failure a parse error triggered announced itself as
/// `JBCT lint found 0 error(s)`: true, and the exact opposite of an explanation, which is what a
/// reader gets when a message reports the count of the thing that did NOT cause the failure.
///
/// This matters more here than on the CLI. The count line a Maven goal prints is what this repository
/// quotes as evidence that a gate examined something, on the standing rule that an exit status is not
/// a result. An inflated count makes that evidence overstate coverage in the one direction nobody
/// checks.
///
/// The assertions are on what must NOT appear — the bare collected count, `found 0 error(s)` — each
/// paired with a complete-coverage control that produces the very line the partial cases forbid, so
/// an absence can never pass because the line was unreachable, and with the parser's own
/// `Parse failed:` diagnostic, so a failing run is distinguishable from one that never read anything.
class UnparseableCoverageReportingTest {
    private static final String CLEAN = "public record Pure(String a) { public static Pure pure(String a){ return new Pure(a);} }\n";

    private static final String UNPARSEABLE = """
                                              public interface Ell {
                                                  ...
                                              }
                                              """;

    /// Captures what the goal told the reader, which is the whole subject here.
    private static final class CapturingLog extends SystemStreamLog {
        private final List<String> infos = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        @Override
        public void info(CharSequence content) {
            infos.add(content.toString());
        }

        @Override
        public void warn(CharSequence content) {
            warnings.add(content.toString());
        }

        @Override
        public void error(CharSequence content) {
            errors.add(content.toString());
        }

        private List<String> everything() {
            var all = new ArrayList<String>(infos);

            all.addAll(warnings);
            all.addAll(errors);

            return all;
        }
    }

    private static Path sourcesWith(Path base, String... nameThenContent) throws Exception {
        var main = base.resolve("src")
                       .resolve("main")
                       .resolve("java");

        Files.createDirectories(main);
        for (int i = 0; i < nameThenContent.length; i += 2) {
            Files.writeString(main.resolve(nameThenContent[i]), nameThenContent[i + 1]);
        }

        return main;
    }

    private static <M extends AbstractJbctMojo> M configure(M mojo, Path base, Path main) throws Exception {
        var project = new MavenProject();

        Files.writeString(base.resolve("pom.xml"), "<project/>");
        project.setArtifactId("fixture");
        project.setFile(base.resolve("pom.xml")
                            .toFile());
        mojo.project = project;
        mojo.sourceDirectory = main.toFile();
        mojo.testSourceDirectory = new File(base.toFile(), "src/test/java");
        mojo.setLog(new CapturingLog());

        return mojo;
    }

    private static CapturingLog logOf(AbstractJbctMojo mojo) {
        return (CapturingLog) mojo.getLog();
    }

    private static Throwable execute(AbstractJbctMojo mojo) {
        return catchThrowable(mojo::execute);
    }

    // ---- jbct:lint -------------------------------------------------------------------------------

    @Test
    void lintMojo_reportsTheAnalysedDenominator_whenAFileCannotBeParsed(@TempDir Path base) throws Exception {
        var mojo = configure(new LintMojo(), base, sourcesWith(base, "Pure.java", CLEAN, "Ell.java", UNPARSEABLE));

        var thrown = execute(mojo);
        var log = logOf(mojo);

        assertThat(log.errors).anyMatch(line -> line.contains("Parse failed:"));
        assertThat(log.infos).anyMatch(line -> line.equals("1 file(s) linted, 1 file(s) not parsed"));
        assertThat(log.errors).anyMatch(line -> line.contains("COVERAGE GAP"));
        assertThat(thrown).hasMessageContaining("could not be read or parsed and were NOT analysed")
                          .hasMessageNotContaining("found 0 error(s)");
    }

    /// Positive control: the complete-coverage line the case above forbids is reachable, byte for byte
    /// as this goal has always printed its counts, and a clean complete run still passes.
    @Test
    void lintMojo_reportsTheBareCountAndPasses_whenEveryFileParses(@TempDir Path base) throws Exception {
        var mojo = configure(new LintMojo(), base, sourcesWith(base, "Pure.java", CLEAN));

        var thrown = execute(mojo);

        assertThat(logOf(mojo).infos).anyMatch(line -> line.equals("1 file(s) linted"))
                                     .anyMatch(line -> line.contains("Findings: 0 error(s)"));
        assertThat(logOf(mojo).everything()).noneMatch(line -> line.contains("UNPARSEABLE"));
        assertThat(thrown).isNull();
    }

    // ---- jbct:check ------------------------------------------------------------------------------

    @Test
    void checkMojo_reportsTheAnalysedDenominator_whenAFileCannotBeParsed(@TempDir Path base) throws Exception {
        var mojo = configure(new CheckMojo(), base, sourcesWith(base, "Pure.java", CLEAN, "Ell.java", UNPARSEABLE));

        var thrown = execute(mojo);
        var log = logOf(mojo);

        assertThat(log.errors).anyMatch(line -> line.contains("Parse failed:"));
        assertThat(log.infos).anyMatch(line -> line.equals("1 file(s) linted, 1 file(s) not parsed"));
        assertThat(thrown).hasMessageContaining("could not be read or parsed and were NOT analysed");
    }

    /// The goal must never announce a pass over a set it could not read. `JBCT check passed.` is the
    /// string a reader takes as the gate's verdict, so its absence here is the property, not the
    /// exception that happens to accompany it.
    @Test
    void checkMojo_neverAnnouncesAPass_whenAFileCannotBeParsed(@TempDir Path base) throws Exception {
        var mojo = configure(new CheckMojo(), base, sourcesWith(base, "Ell.java", UNPARSEABLE));

        execute(mojo);

        assertThat(logOf(mojo).everything()).noneMatch(line -> line.contains("JBCT check passed."));
        assertThat(logOf(mojo).infos).anyMatch(line -> line.startsWith("Nothing was linted: ") && line.contains("1 file(s) not parsed"));
    }

    // ---- jbct:process ----------------------------------------------------------------------------

    /// `jbct:process` is the goal the lifecycle actually binds, so its `Processing N Java file(s)`
    /// announcement is the count this repository quotes from a reactor log. That line reports what was
    /// COLLECTED and cannot know better; both summary lines below it now carry what was ANALYSED.
    @Test
    void processMojo_reportsTheAnalysedDenominatorOnBothSummaryLines(@TempDir Path base) throws Exception {
        var capture = new RunCapture();
        var mojo = capture.on(configure(new ProcessMojo(), base, sourcesWith(base, "Pure.java", CLEAN, "Ell.java", UNPARSEABLE)));

        var thrown = execute(mojo);
        var log = logOf(mojo);

        assertThat(log.errors).containsAll(capture.texts(Level.ERROR));
        assertThat(log.infos).containsAll(capture.texts(Level.INFO))
                             .anyMatch(line -> line.contains("1 formatted, 0 unchanged, 0 errors"));
        assertThat(thrown).isInstanceOf(MojoFailureException.class).hasMessage(capture.failure());
    }

    // ---- jbct:score --------------------------------------------------------------------------

    /// Added after adversarial verification: `jbct:score` was a SIXTH entry point with the same
    /// defect and a worse consequence — it announced `Measuring 2 Java file(s)`, reported a density
    /// over one of them, and returned **BUILD SUCCESS**. A ratio measured over a fragment is not the
    /// project's ratio, and the goal exists to gate on that number.
    @Test
    void scoreMojo_failsAndStatesTheCoverage_whenAFileCannotBeParsed(@TempDir Path base) throws Exception {
        var mojo = configure(new ScoreMojo(), base, sourcesWith(base, "Pure.java", CLEAN, "Ell.java", UNPARSEABLE));

        var thrown = execute(mojo);
        var log = logOf(mojo);

        assertThat(log.errors).anyMatch(line -> line.contains("Parse failed:"));
        assertThat(log.everything()).anyMatch(line -> line.contains("1 of 2 files, 1 UNPARSEABLE"));
        assertThat(thrown).hasMessageContaining("could not be read or parsed and were NOT analysed");
    }

    /// Positive control: the density report and BUILD SUCCESS are reachable, and the clean-run header
    /// still renders the bare file count.
    @Test
    void scoreMojo_reportsTheBareFileCountAndPasses_whenEveryFileParses(@TempDir Path base) throws Exception {
        var mojo = configure(new ScoreMojo(), base, sourcesWith(base, "Pure.java", CLEAN));

        var thrown = execute(mojo);

        assertThat(logOf(mojo).everything()).anyMatch(line -> line.contains("1 files"));
        assertThat(logOf(mojo).everything()).noneMatch(line -> line.contains("UNPARSEABLE"));
        assertThat(thrown).isNull();
    }

    @Test
    void processMojo_reportsTheBareCount_whenEveryFileParses(@TempDir Path base) throws Exception {
        var capture = new RunCapture();
        var mojo = capture.on(configure(new ProcessMojo(), base, sourcesWith(base, "Pure.java", CLEAN)));

        var thrown = execute(mojo);

        assertThat(logOf(mojo).infos).containsAll(capture.texts(Level.INFO))
                                     .contains("Processing 1 Java file(s)");
        assertThat(logOf(mojo).everything()).noneMatch(line -> line.contains("UNPARSEABLE"));
        assertThat(thrown).isNull();
    }

    // ---- the build result of the run-driven goals: the run's verdict, verbatim ------------------------------

    @Test
    void processFormatAndFormatCheck_cleanSource_buildSucceeds(@TempDir Path base) throws Exception {
        var processDir = Files.createDirectories(base.resolve("process"));
        var process = new RunCapture();
        var formatDir = Files.createDirectories(base.resolve("format"));
        var format = new RunCapture();
        var check = new RunCapture();
        var formatMojo = format.on(configure(new FormatMojo(), formatDir, sourcesWith(formatDir, "Pure.java", CLEAN)));
        var checkMojo = check.on(configure(new FormatCheckMojo(), formatDir, formatDir.resolve("src/main/java")));

        assertThat(execute(process.on(configure(new ProcessMojo(), processDir, sourcesWith(processDir, "Pure.java", CLEAN))))).isNull();
        assertThat(process.failure()).isEmpty();
        assertThat(execute(formatMojo)).isNull();
        assertThat(format.failure()).isEmpty();
        assertThat(execute(checkMojo)).isNull();
        assertThat(check.failure()).isEmpty();
    }

    @Test
    void formatCheck_unformattedSource_failsWithTheRunsFailureText(@TempDir Path base) throws Exception {
        var capture = new RunCapture();
        var mojo = capture.on(configure(new FormatCheckMojo(), base, sourcesWith(base, "Pure.java", CLEAN)));
        var thrown = execute(mojo);

        assertThat(capture.failure()).contains("not properly formatted");
        assertThat(thrown).isInstanceOf(MojoFailureException.class).hasMessage(capture.failure());
    }

    @Test
    void processFormatAndFormatCheck_unparseableSource_failsWithTheRunsFailureText(@TempDir Path base) throws Exception {
        for (var goal : List.of(new ProcessMojo(), new FormatMojo(), new FormatCheckMojo())) {
            var capture = new RunCapture();
            var dir = Files.createDirectories(base.resolve(goal.getClass().getSimpleName()));
            var mojo = capture.on(configure(goal, dir, sourcesWith(dir, "Pure.java", CLEAN, "Ell.java", UNPARSEABLE)));
            var thrown = execute(mojo);

            assertThat(capture.failure()).as(goal.getClass().getSimpleName()).isNotEmpty();
            assertThat(thrown).isInstanceOf(MojoFailureException.class).hasMessage(capture.failure());
        }
    }
}
