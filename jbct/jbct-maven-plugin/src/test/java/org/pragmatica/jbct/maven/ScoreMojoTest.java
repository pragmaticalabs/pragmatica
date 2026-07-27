package org.pragmatica.jbct.maven;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

import org.pragmatica.jbct.score.DensityGate;
import org.pragmatica.jbct.score.ScoreReport;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/// Gate for the `jbct:score` goal: the report reaches the Maven log, and `jbct.density.maxPerKloc`
/// fails the build in one direction only — ABOVE the threshold, since density is lower-is-better.
///
/// The goal is the half of the reporting surface that can break a build, and it shares its renderer
/// with the CLI — so it is checked against the same [ScoreReport] output the CLI emits, with the
/// threshold derived from the reported density instead of hard-coded.
class ScoreMojoTest {
    private static final Pattern TOTAL_DENSITY = Pattern.compile(ScoreReport.TOTAL_LABEL
                                                                 + "\\s+([\\d.]+)"
                                                                 + Pattern.quote(ScoreReport.DENSITY_UNIT));

    @TempDir
    Path projectDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private Path sourceDirectory;

    @BeforeEach
    void captureLog() throws IOException {
        originalOut = System.out;
        System.setOut(new PrintStream(out, true, UTF_8));
        sourceDirectory = Files.createDirectories(projectDir.resolve("src/main/java/sample"));
        Files.writeString(sourceDirectory.resolve("Sample.java"),
                          """
                          package sample;

                          public class Sample {
                              public String find(String key) {
                                  if (key == null) {
                                      return null;
                                  }

                                  return key.trim();
                              }
                          }
                          """);
    }

    @AfterEach
    void restoreLog() {
        System.setOut(originalOut);
    }

    private ScoreMojo scoreMojo(Double maxDensity) {
        var project = new MavenProject();

        project.setFile(projectDir.resolve("pom.xml")
                                  .toFile());
        var mojo = new ScoreMojo();

        mojo.project = project;
        mojo.sourceDirectory = projectDir.resolve("src/main/java")
                                         .toFile();
        mojo.maxDensity = maxDensity;
        mojo.setLog(new SystemStreamLog());

        return mojo;
    }

    private String log() {
        return out.toString(UTF_8);
    }

    /// Total density of the fixture as the goal itself reports it, read back from the logged box.
    private double reportedDensity() throws Exception {
        out.reset();
        scoreMojo(null).execute();
        var matcher = TOTAL_DENSITY.matcher(log());

        assertThat(matcher.find()).as("TOTAL row in the Maven log")
                                  .isTrue();

        return Double.parseDouble(matcher.group(1));
    }

    private static double threshold(double density) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.1f", density));
    }

    @Test
    void execute_report_reachesTheMavenLog() throws Exception {
        var density = reportedDensity();

        assertThat(log()).contains(ScoreReport.HEADER_LABEL)
                         .contains(ScoreReport.ADVISORY_LEGEND)
                         .contains(ScoreReport.TOTAL_LABEL)
                         .contains(String.format(Locale.ROOT, "%.1f", density) + ScoreReport.DENSITY_UNIT);
        assertThat(density).isPositive();
    }

    @Test
    void execute_noMaxDensity_succeeds() {
        assertThatCode(scoreMojo(null)::execute).doesNotThrowAnyException();
    }

    @Test
    void execute_maxDensityAtDensity_succeeds() throws Exception {
        var maximum = threshold(reportedDensity());

        assertThatCode(scoreMojo(maximum)::execute).doesNotThrowAnyException();
    }

    @Test
    void execute_maxDensityAboveDensity_succeeds() throws Exception {
        var maximum = threshold(reportedDensity()) + 10.0;

        assertThatCode(scoreMojo(maximum)::execute).doesNotThrowAnyException();
    }

    /// Lower is better, so the build fails ABOVE the threshold — the opposite of the removed
    /// `jbct.score.baseline`.
    @Test
    void execute_maxDensityBelowDensity_failsTheBuild() throws Exception {
        var maximum = threshold(reportedDensity() - 0.1);

        assertThatThrownBy(scoreMojo(maximum)::execute).isInstanceOf(MojoFailureException.class)
                                                       .hasMessageContaining("exceeds maximum");
    }

    @Test
    void execute_zeroMaxDensity_failsForAnyViolation() {
        assertThatThrownBy(scoreMojo(0.0)::execute).isInstanceOf(MojoFailureException.class)
                                                   .hasMessageContaining("exceeds maximum");
    }

    /// A stale `baseline` must not be silently ignored: it meant "fail below", so a build that
    /// still carries one is asserting the opposite of what the density gate would do.
    @Test
    void execute_removedBaselineProperty_failsWithMigrationGuidance() {
        var mojo = scoreMojo(null);

        mojo.baseline = 70;

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class)
                                         .hasMessageContaining(DensityGate.MAX_DENSITY_PROPERTY)
                                         .hasMessageContaining("inverted");
    }

    @Test
    void execute_skip_producesNoReport() {
        var mojo = scoreMojo(0.0);

        mojo.skip = true;
        out.reset();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
        assertThat(log()).doesNotContain(ScoreReport.HEADER_LABEL);
    }
}
