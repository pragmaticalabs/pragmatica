package org.pragmatica.jbct.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.pragmatica.jbct.config.JbctMode;

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

/// #2525 acceptance, Maven front end: one fixture module per mode in an isolated temp repository with its own `.git`
/// marker, so no repository config leaks in. Every module holds a structure violation (ARCH-02: `lift` in a `domain`
/// package, business only), a code-level one (RET-03: `return null`, business and infrastructure) and one unformatted
/// file. What fires in each mode is read off the `lint`, `check`, `process`, `format`, `format-check` and `score` goals,
/// with the summary line asserted. The CLI has its own copy of this test in `jbct-cli`.
class JbctModeFixtureTest {
    private static final String USER_HOME = "user.home";
    private static final String MODE = "jbct.mode";
    private static final String ARCH_02 = "[JBCT-ARCH-02]";
    private static final String RET_03 = "[JBCT-RET-03]";

    private static final String UNFORMATTED = """
                                              package org.acme.%s.domain.pricing;

                                              public class Price {
                                              int run(){
                                              var x=1;
                                              return x;
                                              }
                                              }
                                              """;

    private static final String VIOLATING = """
                                            package org.acme.%s.domain.pricing;

                                            class Both {
                                                Object run() {
                                                    return Result.lift(Err::new, () -> compute());
                                                }
                                                String name() {
                                                    return null;
                                                }
                                            }
                                            """;

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

    /// The fixture module `name` in `mode`; its `basePackage` is set so the structure rules have a package to read.
    private Path module(String name, JbctMode mode) throws Exception {
        var module = Files.createDirectories(repo.resolve(name));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"),
                          "[project]\nbasePackage = \"org.acme." + name + "\"\n[lint]\nmode = \"" + mode.token() + "\"\n");

        var source = Files.createDirectories(module.resolve("src/main/java/org/acme/" + name + "/domain/pricing"));

        Files.writeString(source.resolve("Price.java"), UNFORMATTED.formatted(name));
        Files.writeString(source.resolve("Both.java"), VIOLATING.formatted(name));

        return module;
    }

    private static Path root(Path module) {
        return module.resolve("src/main/java");
    }

    private static Path price(Path module, String name) {
        return root(module).resolve("org/acme/" + name + "/domain/pricing/Price.java");
    }

    private <M extends AbstractJbctMojo> M wire(M mojo, Path module, Properties userProperties) {
        var mavenProject = new MavenProject();
        var request = new DefaultMavenExecutionRequest();

        mavenProject.setFile(new File(module.toFile(), "pom.xml"));
        request.setUserProperties(userProperties);
        mojo.project = mavenProject;
        mojo.session = new MavenSession(null, null, request, new DefaultMavenExecutionResult());
        mojo.sourceDirectory = root(module).toFile();
        mojo.testSourceDirectory = module.resolve("src/test/java").toFile();
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

    /// Runs the goal and returns everything it logged plus the failure message, if it failed.
    private String run(Mojo mojo) {
        logs.clear();
        try {
            mojo.execute();

            return String.join("\n", logs);
        } catch (Exception e) {
            return String.join("\n", logs) + "\n" + e.getMessage();
        }
    }

    private boolean fails(Mojo mojo) {
        try {
            mojo.execute();

            return false;
        } catch (Exception e) {
            logs.add(e.getMessage());

            return true;
        }
    }

    private static int statementIndent(Path file) throws Exception {
        return Files.readAllLines(file)
                    .stream()
                    .filter(line -> line.strip().startsWith("var x"))
                    .map(line -> line.length() - line.stripLeading().length())
                    .findFirst()
                    .orElseThrow();
    }

    private String all() {
        return String.join("\n", logs);
    }

    // ---- business: all three fire

    @Test
    void business_lintCheckAndFormatCheck_reportStructureRuleCodeRuleAndFormatFinding() throws Exception {
        var module = module("biz", JbctMode.BUSINESS);

        assertThat(fails(wire(new LintMojo(), module, new Properties()))).isTrue();
        assertThat(all()).contains(ARCH_02, RET_03).contains("mode=business root=" + root(module) + " 2 files");
        logs.clear();
        assertThat(fails(wire(new CheckMojo(), module, new Properties()))).isTrue();
        assertThat(all()).contains(ARCH_02, RET_03, price(module, "biz").toString());
        logs.clear();
        assertThat(fails(wire(new FormatCheckMojo(), module, new Properties()))).isTrue();
        assertThat(all()).contains(price(module, "biz").toString());
    }

    @Test
    void business_formatAndProcess_writeTheFile_andScoreScoresIt() throws Exception {
        var module = module("biz", JbctMode.BUSINESS);

        assertThat(run(wire(new ProcessMojo(), module, new Properties()))).contains(ARCH_02, RET_03);
        assertThat(statementIndent(price(module, "biz"))).isEqualTo(8);
        assertThat(run(wire(new ScoreMojo(), module, new Properties()))).doesNotContain("Not scored");
    }

    // ---- infrastructure: RET-03 and the format finding, no ARCH-02

    @Test
    void infrastructure_lintAndCheck_reportCodeRuleAndFormatFindingButNoStructureRule() throws Exception {
        var module = module("infra", JbctMode.INFRASTRUCTURE);

        assertThat(fails(wire(new LintMojo(), module, new Properties()))).isTrue();
        assertThat(all()).contains(RET_03).doesNotContain(ARCH_02)
                         .contains("mode=infrastructure root=" + root(module) + " 2 files: structure checks off");
        logs.clear();
        assertThat(fails(wire(new CheckMojo(), module, new Properties()))).isTrue();
        assertThat(all()).contains(RET_03, price(module, "infra").toString()).doesNotContain(ARCH_02);
    }

    @Test
    void infrastructure_process_writesTheFileAndFlagsCodeRuleOnly() throws Exception {
        var module = module("infra", JbctMode.INFRASTRUCTURE);
        var text = run(wire(new ProcessMojo(), module, new Properties()));

        assertThat(text).contains(RET_03).doesNotContain(ARCH_02);
        assertThat(statementIndent(price(module, "infra"))).isEqualTo(8);
    }

    // ---- format-only: only the format finding

    @Test
    void formatOnly_lint_runsNoRuleAndSaysSo() throws Exception {
        var module = module("fmt", JbctMode.FORMAT_ONLY);
        var text = run(wire(new LintMojo(), module, new Properties()));

        assertThat(text).doesNotContain("[JBCT-")
                        .contains("mode=format-only root=" + root(module) + " 2 files: format only, no rule run");
    }

    @Test
    void formatOnly_check_keepsTheFormatFindingAndRunsNoRule() throws Exception {
        var module = module("fmt", JbctMode.FORMAT_ONLY);

        assertThat(fails(wire(new CheckMojo(), module, new Properties()))).isTrue();
        assertThat(all()).contains(price(module, "fmt").toString()).doesNotContain("[JBCT-")
                         .contains("mode=format-only root=" + root(module) + " 2 files: format only, no rule run");
    }

    @Test
    void formatOnly_formatCheckFlagsTheFile_processAndFormatWriteIt() throws Exception {
        var module = module("fmt", JbctMode.FORMAT_ONLY);

        assertThat(fails(wire(new FormatCheckMojo(), module, new Properties()))).isTrue();
        assertThat(all()).contains(price(module, "fmt").toString());
        assertThat(run(wire(new ProcessMojo(), module, new Properties()))).doesNotContain("[JBCT-");
        assertThat(statementIndent(price(module, "fmt"))).isEqualTo(8);
        assertThat(fails(wire(new FormatCheckMojo(), module, new Properties()))).isFalse();
    }

    @Test
    void formatOnly_format_writesTheFile() throws Exception {
        var module = module("fmt", JbctMode.FORMAT_ONLY);

        assertThat(run(wire(new FormatMojo(), module, new Properties()))).doesNotContain("Not formatted");
        assertThat(statementIndent(price(module, "fmt"))).isEqualTo(8);
    }

    @Test
    void formatOnly_score_saysNotScored() throws Exception {
        var module = module("fmt", JbctMode.FORMAT_ONLY);

        assertThat(run(wire(new ScoreMojo(), module, new Properties()))).contains("Not scored: mode=format-only root=" + root(module));
    }

    // ---- disabled: none; nothing is written

    @Test
    void disabled_lintAndCheck_reportNothingButTheSkipAnnouncement() throws Exception {
        var module = module("off", JbctMode.DISABLED);
        var lint = run(wire(new LintMojo(), module, new Properties()));

        assertThat(lint).doesNotContain("[JBCT-")
                        .contains("mode=disabled root=" + root(module) + " 2 files: skipped, no format, no rule run");
        var check = run(wire(new CheckMojo(), module, new Properties()));

        assertThat(check).doesNotContain("[JBCT-").doesNotContain(price(module, "off").toString())
                         .contains("mode=disabled root=" + root(module) + " 2 files: skipped, no format, no rule run")
                         .contains("Not formatted: mode=disabled root=" + root(module));
    }

    @Test
    void disabled_formatFormatCheckAndProcess_writeNothingAndFlagNothing() throws Exception {
        var module = module("off", JbctMode.DISABLED);
        var before = Files.readString(price(module, "off"));

        assertThat(fails(wire(new FormatCheckMojo(), module, new Properties()))).isFalse();
        assertThat(run(wire(new FormatMojo(), module, new Properties()))).contains("Not formatted: mode=disabled root=" + root(module));
        assertThat(run(wire(new ProcessMojo(), module, new Properties())))
            .doesNotContain("[JBCT-").contains("Not processed: mode=disabled root=" + root(module));
        assertThat(Files.readString(price(module, "off"))).isEqualTo(before);
    }

    @Test
    void disabled_score_saysNotScored() throws Exception {
        var module = module("off", JbctMode.DISABLED);

        assertThat(run(wire(new ScoreMojo(), module, new Properties()))).contains("Not scored: mode=disabled root=" + root(module));
    }

    // ---- override arm

    @Test
    void userPropertyDisabled_overABusinessModule_flagsNothingAndWritesNothing() throws Exception {
        var module = module("biz", JbctMode.BUSINESS);
        var before = Files.readString(price(module, "biz"));
        var properties = new Properties();

        properties.setProperty(MODE, "disabled");
        assertThat(fails(wire(new CheckMojo(), module, properties))).isFalse();
        assertThat(all()).doesNotContain("[JBCT-").doesNotContain(price(module, "biz").toString())
                         .contains("mode=disabled root=" + root(module));
        assertThat(run(wire(new FormatMojo(), module, properties))).contains("Not formatted: mode=disabled");
        assertThat(run(wire(new ProcessMojo(), module, properties))).contains("Not processed: mode=disabled");
        assertThat(Files.readString(price(module, "biz"))).isEqualTo(before);
    }

    @Test
    void unknownUserPropertyMode_failsTheGoalListingAllFour() throws Exception {
        var module = module("biz", JbctMode.BUSINESS);
        var properties = new Properties();

        properties.setProperty(MODE, "strict");
        assertThat(run(wire(new LintMojo(), module, properties))).contains("strict", "business", "infrastructure", "format-only", "disabled");
    }
}
