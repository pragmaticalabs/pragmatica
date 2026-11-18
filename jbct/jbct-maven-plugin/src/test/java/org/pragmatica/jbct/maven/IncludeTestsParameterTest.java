package org.pragmatica.jbct.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/// Regression gate for #624: `jbct.includeTests` was inert for the format-family goals.
///
/// `ProcessMojo`, `FormatMojo` and `FormatCheckMojo` each declared their own `includeTests` field
/// with `defaultValue = "true"`, shadowing an `AbstractJbctMojo` field of the same name defaulting
/// to `"false"`. Collection ran in the parent and read the parent's field, and a field read is
/// statically bound — so `jbct:process` and `jbct:format` never saw `src/test/java`, and
/// `-Djbct.includeTests=true` did nothing for them. Both halves were invisible: the parameter
/// existed, was documented, appeared in the generated `plugin.xml` with `default-value=true`, and a
/// prior handover recorded the support as already working on the strength of that.
///
/// The defect class is SHADOWING, not one wrong default, so the first test targets the class rather
/// than the instance: no goal may inherit a second field of this name for its own to hide. The
/// second pins the consequence that made it matter — that the value a goal declares reaches the shared resolver, the
/// one place that decides which roots a run covers.
class IncludeTestsParameterTest {
    private static final String USER_HOME = "user.home";

    @TempDir
    Path dir;

    private String savedUserHome;

    @BeforeEach
    void redirectUserHome() {
        savedUserHome = System.getProperty(USER_HOME);
        System.setProperty(USER_HOME, dir.resolve("home").toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty(USER_HOME, savedUserHome);
    }

    private static List<Class<?>> collectingMojos() {
        return List.of(ProcessMojo.class,
                       FormatMojo.class,
                       FormatCheckMojo.class,
                       CheckMojo.class,
                       LintMojo.class,
                       ScoreMojo.class);
    }

    @ParameterizedTest
    @MethodSource("collectingMojos")
    void includeTests_isDeclaredExactlyOnce_perMojoHierarchy(Class<?> mojo) {
        var declarations = 0;

        for (Class<?> type = mojo; type != null; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (field.getName().equals("includeTests")) {
                    declarations++;
                }
            }
        }

        assertThat(declarations)
                  .as("%s: a second inherited 'includeTests' would be shadowed and silently ignored",
                      mojo.getSimpleName())
                  .isEqualTo(1);
    }

    /// The consequence that made #624 matter, through the live wiring: the value a goal declares reaches the shared resolver,
    /// so the test root shows up in the run's summary exactly when `includeTests` is set, in every goal that collects.
    @ParameterizedTest
    @MethodSource("collectingMojos")
    void includeTests_reachesTheResolver_inEveryGoal(Class<?> goal) throws Exception {
        var main = Files.createDirectories(dir.resolve("src/main/java"));
        var test = Files.createDirectories(dir.resolve("src/test/java"));

        Files.writeString(main.resolve("Main.java"), "package p;\n\nclass Main {}\n");
        Files.writeString(test.resolve("MainTest.java"), "package p;\n\nclass MainTest {}\n");

        assertThat(summaryOf(goal, main, test, false)).noneMatch(line -> line.contains("root=" + test));
        assertThat(summaryOf(goal, main, test, true)).anyMatch(line -> line.contains("root=" + test)
                                                                       && (line.contains("1 test-root files") || line.contains(": 1 files;")));
    }

    private List<String> summaryOf(Class<?> goal, Path main, Path test, boolean includeTests) throws Exception {
        var logs = new ArrayList<String>();
        var mojo = (AbstractJbctMojo) goal.getDeclaredConstructor().newInstance();
        var field = goal.getDeclaredField("includeTests");
        var mavenProject = new MavenProject();

        mavenProject.setFile(new File(dir.toFile(), "pom.xml"));
        mojo.project = mavenProject;
        mojo.sourceDirectory = main.toFile();
        mojo.testSourceDirectory = test.toFile();
        field.setAccessible(true);
        field.setBoolean(mojo, includeTests);
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void info(CharSequence content) {
                logs.add(content.toString());
            }
        });
        try {
            mojo.execute();
        } catch (Exception _) {
            // A goal may fail on its findings; what this test reads is the roots the run was built over.
        }

        return logs;
    }
}
