package org.pragmatica.jbct.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #2525 P2c: what the shared resolver and run decide (roots, modes, counts, summary, verdict, failure message) is what
/// every Maven goal logs and fails with.
///
/// The fixture tree below (module names, files, tomls, the `violating` source) is a COPY of `CliSharedRunTest`'s in
/// `jbct-cli`: the CLI fixture builder is private to its tests and no test-jar is published, so the two MUST stay
/// identical. A change to one without the other breaks the claim these two classes make together: the same tree, through
/// either front end, gives the same roots, modes and counts. Expected values side by side (CLI T1 / Maven T1):
/// findings `ARCH-02@biz, RET-03@biz, RET-03@infra` / the same; summary `mode=business (from <biz>/jbct.toml)` and
/// `mode=infrastructure (from <infra>/jbct.toml)` / the same; `check` prints ARCH-02 once and RET-03 twice / the same.
/// The Maven goals reach one directory holding two modules as `sourceDirectory` plus a declared `testSourceDirectory`
/// (a Maven run covers a module's main and test directories; the CLI takes both as arguments).
class MojoSharedRunTest {
    private static final String USER_HOME = "user.home";
    private static final String MODE = "jbct.mode";
    private static final String ARCH_02 = "JBCT-ARCH-02";
    private static final String RET_03 = "JBCT-RET-03";
    private static final String NO_RULE = "no JBCT rule ran in any source root, so nothing was linted";

    @TempDir
    Path tempDir;

    private final List<String> logs = new ArrayList<>();
    private String savedUserHome;
    private Path repo;

    @BeforeEach
    void fixture() throws Exception {
        savedUserHome = System.getProperty(USER_HOME);
        System.setProperty(USER_HOME, tempDir.resolve("home").toString());
        System.clearProperty(MODE);
        repo = Files.createDirectories(tempDir.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
    }

    @AfterEach
    void restore() {
        System.setProperty(USER_HOME, savedUserHome);
        System.clearProperty(MODE);
    }

    // ---- the fixture, identical to CliSharedRunTest

    private Path module(String name, String lintSection) throws Exception {
        var module = Files.createDirectories(repo.resolve(name));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"), "[project]\nbasePackage = \"org.acme." + name + "\"\n[lint]\n" + lintSection);

        var source = Files.createDirectories(module.resolve("src/main/java/org/acme/" + name + "/domain/pricing"));

        Files.writeString(source.resolve(name + "Price.java"), violating(name));

        return module.resolve("src/main/java");
    }

    private static String violating(String name) {
        return """
               package org.acme.%s.domain.pricing;

               public class %sPrice {
                   Object run() {
                       var attempt = Result.lift(Err::new, () -> 1);
                       return null;
                   }
               }
               """.formatted(name, name);
    }

    // ---- wiring

    private <M extends AbstractJbctMojo> M wire(M mojo, Path sourceDirectory, Path testDirectory, Properties userProperties) {
        var mavenProject = new MavenProject();
        var request = new DefaultMavenExecutionRequest();

        mavenProject.setFile(new File(repo.toFile(), "pom.xml"));
        request.setUserProperties(userProperties);
        mojo.project = mavenProject;
        mojo.session = new MavenSession(null, null, request, new DefaultMavenExecutionResult());
        mojo.sourceDirectory = sourceDirectory.toFile();
        mojo.testSourceDirectory = testDirectory.toFile();
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void info(CharSequence content) {
                logs.add(content.toString());
            }

            @Override
            public void warn(CharSequence content) {
                logs.add(content.toString());
            }

            @Override
            public void error(CharSequence content) {
                logs.add(content.toString());
            }
        });

        return mojo;
    }

    private static void includeTests(Object mojo, boolean value) throws Exception {
        var field = mojo.getClass().getDeclaredField("includeTests");

        field.setAccessible(true);
        field.setBoolean(mojo, value);
    }

    private Optional<Exception> run(AbstractJbctMojo mojo, boolean includeTests) throws Exception {
        includeTests(mojo, includeTests);
        logs.clear();

        return attempt(mojo);
    }

    private static Optional<Exception> attempt(Mojo mojo) {
        try {
            mojo.execute();

            return Optional.empty();
        } catch (Exception e) {
            return Optional.of(e);
        }
    }

    private String text(Optional<Exception> outcome) {
        return String.join("\n", logs) + outcome.map(e -> "\n" + e.getMessage()).orElse("");
    }

    private static String root(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static int count(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }

    /// The findings of a run as `rule@module`, read off the file name a diagnostic carries.
    private List<String> findings() {
        return logs.stream()
                   .filter(line -> line.startsWith("[JBCT-"))
                   .map(line -> line.substring(1, line.indexOf(']')) + "@" + (line.contains("bizPrice") ? "biz" : "infra"))
                   .toList();
    }

    private LintMojo lint(Path main, Path test) {
        return wire(new LintMojo(), main, test, new Properties());
    }

    // ---- T1 (Maven half): one run over a business and an infrastructure module

    @Test
    void t1_aBusinessAndAnInfrastructureModule_giveEachRootItsOwnMode_asTheCliDoes() throws Exception {
        var biz = module("biz", "mode = \"business\"\n");
        var infra = module("infra", "mode = \"infrastructure\"\n");

        run(lint(biz, infra), true);

        assertThat(findings()).containsExactlyInAnyOrder(ARCH_02 + "@biz", RET_03 + "@biz", RET_03 + "@infra");
        assertThat(logs).anyMatch(line -> line.contains("mode=business (from " + root(biz.getParent().getParent().getParent().resolve("jbct.toml")) + ") root=" + root(biz)));
        assertThat(logs).anyMatch(line -> line.contains("mode=infrastructure (from " + root(infra.getParent().getParent().getParent().resolve("jbct.toml")) + ") root=" + root(infra)));
        assertThat(logs).anyMatch(line -> line.contains("root=" + root(biz)) && line.contains("1 examined by rules"));
        assertThat(logs).anyMatch(line -> line.contains("root=" + root(infra)) && line.contains("1 examined by rules"));
        assertThat(logs).anyMatch(line -> line.equals("Linting 2 Java file(s)"));
    }

    @Test
    void t1_check_countsTheSameFindingsAsTheCliCheck() throws Exception {
        var biz = module("biz", "mode = \"business\"\n");
        var infra = module("infra", "mode = \"infrastructure\"\n");
        var text = text(run(wire(new CheckMojo(), biz, infra, new Properties()), true));

        assertThat(count(text, ARCH_02)).isEqualTo(1);
        assertThat(count(text, RET_03)).isEqualTo(2);
    }

    // ---- T2 / F6: identical declared roots are one root, each file once

    @Test
    void f6_sourceDirectoryEqualToTestSourceDirectory_isOneRoot_andEachFileIsExaminedOnce() throws Exception {
        var biz = module("biz", "mode = \"business\"\n");

        run(lint(biz, biz), true);

        assertThat(findings()).containsExactlyInAnyOrder(ARCH_02 + "@biz", RET_03 + "@biz");
        assertThat(count(String.join("\n", logs), "root=" + root(biz))).isEqualTo(1);
        assertThat(logs).anyMatch(line -> line.contains("1 examined by rules"));
    }

    // ---- T4: no compliance pass when no root ran a rule; counts exclude skipped files

    @Test
    void t4_noRootRanARule_lintCountsNothingExamined_andCheckSaysItIsNotAPass() throws Exception {
        var infra = module("infra", "mode = \"format-only\"\n");
        var off = module("off", "mode = \"disabled\"\n");

        var linted = text(run(lint(infra, off), true));

        assertThat(linted).contains("Linting 0 Java file(s)").contains("0 examined by rules").contains("1 skipped by mode");
        assertThat(linted).contains("root=" + root(off) + " basePackage=org.acme.off: 0 examined by rules, 0 formatted or format-checked, 1 skipped by mode");
        var disabledOnly = text(run(wire(new CheckMojo(), off, off, new Properties()), false));

        assertThat(disabledOnly).contains("Nothing was checked").contains("not a pass").doesNotContain("JBCT check passed.");
        run(wire(new FormatMojo(), infra, infra, new Properties()), false);
        var formatOnly = text(run(wire(new CheckMojo(), infra, infra, new Properties()), false));

        assertThat(formatOnly).contains(NO_RULE).doesNotContain("JBCT check passed.");
    }

    @Test
    void t4_oneRootRanARule_countsExcludeTheSkippedRootsFiles_inEveryGoal() throws Exception {
        var biz = module("biz", "mode = \"business\"\n");
        var off = module("off", "mode = \"disabled\"\n");
        var skipped = "root=" + root(off) + " basePackage=org.acme.off: 0 examined by rules, 0 formatted or format-checked, 1 skipped by mode";

        assertThat(text(run(lint(biz, off), true))).contains("Linting 1 Java file(s)").contains(skipped).doesNotContain(NO_RULE);
        assertThat(text(run(wire(new ProcessMojo(), biz, off, new Properties()), true))).contains("Processing 1 Java file(s)").contains(skipped);
        assertThat(text(run(wire(new FormatMojo(), biz, off, new Properties()), true))).contains("Formatting 1 Java file(s)").contains(skipped);
        assertThat(text(run(wire(new FormatCheckMojo(), biz, off, new Properties()), true))).contains("Checking format of 1 Java file(s)").contains(skipped);
        assertThat(text(run(wire(new CheckMojo(), biz, off, new Properties()), true))).contains("Running JBCT check on 1 Java file(s)").contains(skipped);
    }

    // ---- T5: the summary line per mode with where it came from, in every goal; one line per root

    @Test
    void t5_summaryLineNamesTheModeAndWhereItCameFrom_inEveryGoal() throws Exception {
        var biz = module("biz", "mode = \"business\"\n");
        var plain = Files.createDirectories(tempDir.resolve("plain/src/main/java/org/acme"));

        Files.writeString(plain.resolve("A.java"), "package org.acme;\n\npublic class A {}\n");
        var plainRoot = tempDir.resolve("plain/src/main/java");
        var toml = root(biz.getParent().getParent().getParent().resolve("jbct.toml"));

        for (var goal : goals(biz, new Properties())) {
            var text = text(run(goal, false));

            assertThat(text).as(goal.getClass().getSimpleName() + " toml").contains("mode=business (from " + toml + ") root=" + root(biz));
            assertThat(count(text, "root=" + root(biz))).as(goal.getClass().getSimpleName() + " one line").isEqualTo(1);
        }
        var override = new Properties();

        override.setProperty(MODE, "infrastructure");
        for (var goal : goals(biz, override)) {
            assertThat(text(run(goal, false))).as(goal.getClass().getSimpleName() + " override").contains("mode=infrastructure (from -Djbct.mode)");
        }
        for (var goal : goals(plainRoot, new Properties())) {
            assertThat(text(run(goal, false))).as(goal.getClass().getSimpleName() + " default").contains("mode=business (default) root=");
        }
    }

    private List<AbstractJbctMojo> goals(Path main, Properties userProperties) {
        return List.of(wire(new LintMojo(), main, main, userProperties),
                       wire(new CheckMojo(), main, main, userProperties),
                       wire(new ScoreMojo(), main, main, userProperties),
                       wire(new ProcessMojo(), main, main, userProperties),
                       wire(new FormatMojo(), main, main, userProperties),
                       wire(new FormatCheckMojo(), main, main, userProperties));
    }

    // ---- T6: failOnWarning in the root's toml fails the goal

    @Test
    void t6_failOnWarningInTheRootsToml_failsTheGoal() throws Exception {
        var strict = module("strict", "mode = \"infrastructure\"\nfailOnWarning = true\n[lint.rules]\nJBCT-RET-03 = \"warning\"\n");
        var lenient = module("lenient", "mode = \"infrastructure\"\n[lint.rules]\nJBCT-RET-03 = \"warning\"\n");

        assertThat(run(lint(lenient, lenient), false)).isEmpty();
        assertThat(run(lint(strict, strict), false)).isPresent();
    }

    // ---- T7: the failure message names the root and the count per root

    @Test
    void t7_theFailureMessageNamesEachFailingRootWithItsCount() throws Exception {
        var biz = module("biz", "mode = \"business\"\n");
        var infra = module("infra", "mode = \"infrastructure\"\n");

        for (var goal : List.of(lint(biz, infra),
                                  wire(new CheckMojo(), biz, infra, new Properties()),
                                  wire(new ProcessMojo(), biz, infra, new Properties()))) {
            var outcome = run(goal, true);

            assertThat(outcome).as(goal.getClass().getSimpleName()).isPresent();
            assertThat(outcome.get().getMessage()).contains(root(biz) + ": 1 error(s), 1 warning(s)")
                                                   .contains(root(infra) + ": 1 error(s), 0 warning(s)");
        }
    }
}
