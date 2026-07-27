package org.pragmatica.jbct.maven;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.pragmatica.jbct.score.ScoreReport;

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


/// Gate for the `jbct:score` goal: the report reaches the Maven log, and `jbct.score.baseline`
/// fails the build in one direction only.
///
/// The goal is the half of the scoring surface that can break a build, and it shares its renderer
/// with the CLI — so it is checked against the same [ScoreReport] output the CLI emits, with the
/// baseline derived from the reported score instead of hard-coded.
class ScoreMojoTest {
    private static final Pattern SCORE = Pattern.compile("JBCT COMPLIANCE SCORE: (\\d+)/100");

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

    private ScoreMojo scoreMojo(Integer baseline) {
        var project = new MavenProject();

        project.setFile(projectDir.resolve("pom.xml")
                                  .toFile());
        var mojo = new ScoreMojo();

        mojo.project = project;
        mojo.sourceDirectory = projectDir.resolve("src/main/java")
                                         .toFile();
        mojo.baseline = baseline;
        mojo.setLog(new SystemStreamLog());

        return mojo;
    }

    private String log() {
        return out.toString(UTF_8);
    }

    /// Score of the fixture as the goal itself reports it, read back from the logged report box.
    private int reportedScore() throws Exception {
        out.reset();
        scoreMojo(null).execute();
        var matcher = SCORE.matcher(log());

        assertThat(matcher.find()).as("score header in the Maven log")
                                  .isTrue();

        return Integer.parseInt(matcher.group(1));
    }

    @Test
    void execute_report_reachesTheMavenLog() throws Exception {
        var score = reportedScore();

        assertThat(log()).contains(ScoreReport.TOP_BORDER)
                         .contains(ScoreReport.BOTTOM_BORDER)
                         .contains(ScoreReport.ADVISORY_LEGEND)
                         .contains("JBCT COMPLIANCE SCORE: " + score + "/100");
    }

    @Test
    void execute_noBaseline_succeeds() {
        assertThatCode(scoreMojo(null)::execute).doesNotThrowAnyException();
    }

    @Test
    void execute_baselineAtScore_succeeds() throws Exception {
        var baseline = reportedScore();

        assertThatCode(scoreMojo(baseline)::execute).doesNotThrowAnyException();
    }

    @Test
    void execute_baselineBelowScore_succeeds() {
        assertThatCode(scoreMojo(0)::execute).doesNotThrowAnyException();
    }

    @Test
    void execute_baselineAboveScore_failsTheBuild() throws Exception {
        var baseline = reportedScore() + 1;

        assertThatThrownBy(scoreMojo(baseline)::execute).isInstanceOf(MojoFailureException.class)
                                                        .hasMessageContaining("below baseline " + baseline);
    }

    @Test
    void execute_skip_producesNoReport() {
        var mojo = scoreMojo(1000);

        mojo.skip = true;
        out.reset();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
        assertThat(log()).doesNotContain(ScoreReport.TOP_BORDER);
    }
}
