package org.pragmatica.jbct.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// Regression gate for #740: a goal that examined NOTHING reported it the same way as a goal that
/// examined everything and found it clean.
///
/// `forge-tests` has zero files under `src/main/java` and 41 under `src/test/java`. With the
/// project-wide `includeTests=false` default, `jbct:check` there collected nothing, logged
/// `No Java files found.` at INFO, and exited green — while the sentence was flatly untrue, since
/// the files exist and were excluded by policy. A reader could not distinguish "looked, found
/// nothing" from "never looked", and the ticket that surfaced it proposed two remedies that would
/// each have added a second CI step reporting success while examining zero files.
///
/// The pin is on the distinction, not on the wording: an empty file set caused by EXCLUSION must
/// announce itself as uncovered, and a genuinely empty source tree must stay quiet so aggregator
/// modules do not cry wolf.
class NothingToCheckReportingTest {

    /// Captures what the goal actually told the reader, which is the whole subject here.
    private static final class CapturingLog extends SystemStreamLog {
        private final List<String> warnings = new ArrayList<>();
        private final List<String> infos = new ArrayList<>();

        @Override
        public void warn(CharSequence content) {
            warnings.add(content.toString());
        }

        @Override
        public void info(CharSequence content) {
            infos.add(content.toString());
        }
    }

    private static CheckMojo mojoFor(Path mainDir, Path testDir) {
        var mojo = new CheckMojo();
        var project = new MavenProject();

        project.setArtifactId("forge-tests");
        mojo.project = project;
        mojo.sourceDirectory = mainDir.toFile();
        mojo.testSourceDirectory = testDir.toFile();
        mojo.setLog(new CapturingLog());

        return mojo;
    }

    private static CapturingLog logOf(CheckMojo mojo) {
        return (CapturingLog) mojo.getLog();
    }

    private static Path withJavaFiles(Path dir, int count) throws Exception {
        Files.createDirectories(dir);
        for (int i = 0; i < count; i++) {
            Files.writeString(dir.resolve("Sample" + i + ".java"), "class Sample" + i + " {}");
        }

        return dir;
    }

    /// The forge-tests shape: files exist, policy excluded them, so the goal covered nothing.
    @Test
    void reportNothingToCheck_warnsThatNothingWasExamined_whenTestsWereExcluded(@TempDir Path dir) throws Exception {
        var mojo = mojoFor(dir.resolve("main"), withJavaFiles(dir.resolve("test"), 41));

        mojo.reportNothingToCheck("check", false);

        var warnings = logOf(mojo).warnings;

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("examined NOTHING")
                                       .contains("forge-tests")
                                       .contains("41 test file(s)")
                                       .contains("jbct.includeTests=false")
                                       .contains("not evidence");
    }

    /// It must not claim there are no Java files when there are 41 of them — that was the actual
    /// falsehood, and the reason a reader trusted the green.
    @Test
    void reportNothingToCheck_doesNotClaimAbsence_whenFilesWereMerelyExcluded(@TempDir Path dir) throws Exception {
        var mojo = mojoFor(dir.resolve("main"), withJavaFiles(dir.resolve("test"), 41));

        mojo.reportNothingToCheck("check", false);

        assertThat(logOf(mojo).infos).noneMatch(line -> line.contains("No Java files found"));
    }

    /// A genuinely empty module — an aggregator pom, say — must stay quiet, or the warning becomes
    /// noise and stops being read.
    @Test
    void reportNothingToCheck_staysQuiet_whenThereIsGenuinelyNothing(@TempDir Path dir) {
        var mojo = mojoFor(dir.resolve("main"), dir.resolve("test"));

        mojo.reportNothingToCheck("check", false);

        assertThat(logOf(mojo).warnings).isEmpty();
        assertThat(logOf(mojo).infos).containsExactly("No Java files found.");
    }

    /// With tests already included, an empty set really does mean nothing was there — nothing was
    /// excluded, so there is nothing to disclose.
    @Test
    void reportNothingToCheck_staysQuiet_whenTestsWereIncludedAndStillEmpty(@TempDir Path dir) throws Exception {
        var mojo = mojoFor(dir.resolve("main"), withJavaFiles(dir.resolve("test"), 41));

        mojo.reportNothingToCheck("check", true);

        assertThat(logOf(mojo).warnings).isEmpty();
        assertThat(logOf(mojo).infos).containsExactly("No Java files found.");
    }

    /// Counting walks the tree, because sources sit in package directories rather than at the root.
    @Test
    void reportNothingToCheck_countsNestedSources(@TempDir Path dir) throws Exception {
        var nested = dir.resolve("test").resolve("org").resolve("example");
        var mojo = mojoFor(dir.resolve("main"), dir.resolve("test"));

        withJavaFiles(nested, 3);

        mojo.reportNothingToCheck("check", false);

        assertThat(logOf(mojo).warnings.getFirst()).contains("3 test file(s)");
    }
}
