package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.jbct.config.JbctMode;

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

    private CapturedRun run = new CapturedRun();

    private int execute(Object command, String... args) {
        out.reset();
        err.reset();
        run = new CapturedRun();
        if (command instanceof LintCommand lint) {
            lint.runSeam = run;
        } else if (command instanceof ScoreCommand score) {
            score.runSeam = run;
        }

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

    /// Puts the module `name` in `mode` through its own `jbct.toml`, the way a user would.
    private void setMode(String name, JbctMode mode) throws Exception {
        Files.writeString(tempDir.resolve("repo").resolve(name).resolve("jbct.toml"),
                          "[lint]\nmode = \"" + mode.token() + "\"\n[project]\nbasePackage = \"org.acme." + name + "\"\n");
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

        assertThat(out.toString()).isEqualTo(run.expectedStdout(false));
        assertThat(run.stdoutLines(false)).anyMatch(line -> line.contains("root=" + rootOf(business)) && line.contains("mode=business"))
                                          .anyMatch(line -> line.contains("root=" + rootOf(infrastructure)) && line.contains("mode=infrastructure"));
        assertThat(run.result().examined()).isEqualTo(2);
    }

    @Test
    void lint_singleRoot_keepsItsExitCode() {
        assertThat(execute(new LintCommand(), "--format", "json", business.toString())).isEqualTo(1);
        assertThat(findings(out.toString())).containsExactlyInAnyOrder(ARCH_02 + "@biz", RET_03 + "@biz");
        assertThat(execute(new LintCommand(), "--format", "json", infrastructure.toString())).isEqualTo(1);
        assertThat(findings(out.toString())).containsExactly(RET_03 + "@infra");
    }

    @Test
    void lint_formatOnlyRootBesideBusinessRoot_runsNoRuleForIt() throws Exception {
        setMode("infra", JbctMode.FORMAT_ONLY);

        execute(new LintCommand(), "--format", "json", business.toString(), infrastructure.toString());

        assertThat(findings(out.toString())).containsExactlyInAnyOrder(ARCH_02 + "@biz", RET_03 + "@biz");
        assertThat(err.toString()).contains(rootOf(infrastructure).toString());
        assertThat(run.result().examined()).isOne();
        assertThat(run.result().skippedByMode()).isOne();
    }

    @Test
    void check_twoPathsInTwoModules_eachFileGetsItsOwnModulesRulesAndSummaryLines() {
        var captured = new CapturedRun();
        var command = new CheckCommand();

        command.runSeam = captured;

        var exit = execute(command, business.toString(), infrastructure.toString());
        var text = out.toString();

        assertThat(exit).isEqualTo(1);
        assertThat(count(text, ARCH_02)).isEqualTo(1);
        assertThat(count(text, RET_03)).isEqualTo(2);
        assertThat(text).isEqualTo(captured.expectedStdout(false));
        assertThat(captured.stdoutLines(false)).anyMatch(line -> line.contains("root=" + rootOf(business)))
                                               .anyMatch(line -> line.contains("root=" + rootOf(infrastructure)));
        assertThat(captured.result().examined()).isEqualTo(2);
    }

    @Test
    void score_formatOnlyRootBesideBusinessRoot_announcesNotScoredAndScoresTheOther() throws Exception {
        setMode("infra", JbctMode.FORMAT_ONLY);
        var exit = execute(new ScoreCommand(), business.toString(), infrastructure.toString());

        assertThat(err.toString()).contains(rootOf(infrastructure).toString());
        assertThat(run.result().examined()).isOne();
        assertThat(run.result().skippedByMode()).isOne();
        assertThat(run.result().score().isPresent()).isTrue();
        assertThat(out.toString()).isNotBlank();
        assertThat(exit).isZero();
    }

    @Test
    void score_everyRootDisabled_announcesNotScoredAndExitsClean() throws Exception {
        setMode("biz", JbctMode.DISABLED);
        var exit = execute(new ScoreCommand(), business.toString());

        assertThat(err.toString()).contains(run.result().after().getFirst().text());
        assertThat(run.result().examined()).isZero();
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
