package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// #2525 P2c: `lint`, `check` and `score` resolve the configuration per argument path's source root and run each file
/// through the rules of its own root, tested through `CommandLine.execute` in a temp repository with its own `.git`
/// marker. Both modules carry the same two violations, ARCH-02 (`lift` in a `domain` package, business-only) and RET-03
/// (`return null`, every mode), so a finding that differs between the modules can only come from the module's own mode.
class CliPerRootLintTest {
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String ARCH_02 = "JBCT-ARCH-02";
    private static final String RET_03 = "JBCT-RET-03";
    private static final Pattern JSON_FINDING = Pattern.compile("\"ruleId\": \"([^\"]+)\",\\s+\"severity\": \"[^\"]+\",\\s+\"file\": \"([^\"]+)\"");

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private String savedUserHome;
    private PrintStream savedOut;
    private PrintStream savedErr;
    private Path business;
    private Path infrastructure;

    @BeforeEach
    void fixture() throws Exception {
        savedUserHome = System.getProperty(USER_HOME_PROPERTY);
        savedOut = System.out;
        savedErr = System.err;
        System.setProperty(USER_HOME_PROPERTY, tempDir.resolve("home").toString());
        System.setOut(new PrintStream(out, true, UTF_8));
        System.setErr(new PrintStream(err, true, UTF_8));

        var repo = Files.createDirectories(tempDir.resolve("repo"));

        Files.createDirectories(repo.resolve(".git"));
        business = module(repo, "biz", "business");
        infrastructure = module(repo, "infra", "infrastructure");
    }

    @AfterEach
    void restore() {
        System.setProperty(USER_HOME_PROPERTY, savedUserHome);
        System.setOut(savedOut);
        System.setErr(savedErr);
    }

    private static Path module(Path repo, String name, String mode) throws Exception {
        var module = Files.createDirectories(repo.resolve(name));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"),
                          "[lint]\nmode = \"" + mode + "\"\n[project]\nbasePackage = \"org.acme." + name + "\"\n");

        var source = Files.createDirectories(module.resolve("src/main/java/org/acme/" + name + "/domain/pricing"));

        Files.writeString(source.resolve(name + "Price.java"), violating(name, name + "Price"));

        return module.resolve("src/main/java");
    }

    private static String violating(String name, String type) {
        return """
               package org.acme.%s.domain.pricing;

               public class %s {
                   Object run() {
                       var attempt = Result.lift(Err::new, () -> 1);
                       return null;
                   }
               }
               """.formatted(name, type);
    }

    private int execute(Object command, String... args) {
        out.reset();
        err.reset();

        return new CommandLine(command).execute(args);
    }

    private List<String> findings(String jsonOut) {
        var found = new ArrayList<String>();
        var matcher = JSON_FINDING.matcher(jsonOut);

        while (matcher.find()) {
            found.add(matcher.group(1) + "@" + (matcher.group(2).contains("bizPrice")
                                                ? "biz"
                                                : "infra"));
        }

        return found;
    }

    /// The context for the module whose `basePackage` is `org.acme.<module>`, put in `mode`; the config parser still
    /// refuses `format-only` and `disabled` (until the last P2c lane), so a test reaches them by overriding the context.
    private static LintContext inMode(LintContext context, String module, JbctMode mode) {
        return context.basePackage().equals(Option.some("org.acme." + module))
               ? context.withProject(mode, context.basePackage())
               : context;
    }

    @Test
    void lint_twoPathsInTwoModules_eachFileGetsItsOwnModulesRules() {
        var exit = execute(new LintCommand(), "--format", "json", business.toString(), infrastructure.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(findings(out.toString())).containsExactlyInAnyOrder(ARCH_02 + "@biz", RET_03 + "@biz", RET_03 + "@infra");
    }

    @Test
    void lint_reversedArgumentOrder_givesTheSameFindings() {
        execute(new LintCommand(), "--format", "json", infrastructure.toString(), business.toString());

        assertThat(findings(out.toString())).containsExactlyInAnyOrder(ARCH_02 + "@biz", RET_03 + "@biz", RET_03 + "@infra");
    }

    @Test
    void lint_printsOneSummaryLinePerRootWithItsMode() {
        execute(new LintCommand(), business.toString(), infrastructure.toString());

        assertThat(out.toString()).contains("mode=business root=" + rootOf(business) + " 1 files\n")
                                  .contains("mode=infrastructure root=" + rootOf(infrastructure) + " 1 files: structure checks off");
    }

    @Test
    void lint_singleRoot_keepsItsExitCode() {
        assertThat(execute(new LintCommand(), "--format", "json", business.toString())).isEqualTo(1);
        assertThat(findings(out.toString())).containsExactlyInAnyOrder(ARCH_02 + "@biz", RET_03 + "@biz");
        assertThat(execute(new LintCommand(), "--format", "json", infrastructure.toString())).isEqualTo(1);
        assertThat(findings(out.toString())).containsExactly(RET_03 + "@infra");
    }

    @Test
    void lint_formatOnlyRootBesideBusinessRoot_runsNoRuleForIt() {
        var lint = new LintCommand() {
            @Override
            LintContext createContext(JbctConfig config) {
                return inMode(super.createContext(config), "infra", JbctMode.FORMAT_ONLY);
            }
        };

        execute(lint, "--format", "json", business.toString(), infrastructure.toString());

        assertThat(findings(out.toString())).containsExactlyInAnyOrder(ARCH_02 + "@biz", RET_03 + "@biz");
        assertThat(err.toString()).contains("mode=format-only root=" + rootOf(infrastructure) + " 1 files: format only");
    }

    @Test
    void check_twoPathsInTwoModules_eachFileGetsItsOwnModulesRulesAndSummaryLines() {
        var exit = execute(new CheckCommand(), business.toString(), infrastructure.toString());
        var text = out.toString();

        assertThat(exit).isEqualTo(1);
        assertThat(count(text, ARCH_02)).isEqualTo(1);
        assertThat(count(text, RET_03)).isEqualTo(2);
        assertThat(text).contains("mode=business root=" + rootOf(business) + " 1 files")
                        .contains("mode=infrastructure root=" + rootOf(infrastructure) + " 1 files");
    }

    @Test
    void score_formatOnlyRootBesideBusinessRoot_announcesNotScoredAndScoresTheOther() {
        var score = new ScoreCommand() {
            @Override
            LintContext createContext(JbctConfig config) {
                return inMode(super.createContext(config), "infra", JbctMode.FORMAT_ONLY);
            }
        };
        var exit = execute(score, business.toString(), infrastructure.toString());

        assertThat(err.toString()).contains("Not scored: mode=format-only root=" + rootOf(infrastructure));
        assertThat(err.toString()).doesNotContain("Not scored: mode=business");
        assertThat(out.toString()).isNotBlank();
        assertThat(exit).isZero();
    }

    @Test
    void score_everyRootDisabled_announcesNotScoredAndExitsClean() {
        var score = new ScoreCommand() {
            @Override
            LintContext createContext(JbctConfig config) {
                return inMode(super.createContext(config), "biz", JbctMode.DISABLED);
            }
        };
        var exit = execute(score, business.toString());

        assertThat(err.toString()).contains("Not scored: mode=disabled root=" + rootOf(business));
        assertThat(out.toString()).isBlank();
        assertThat(exit).isZero();
    }

    private static Path rootOf(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static int count(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }
}
