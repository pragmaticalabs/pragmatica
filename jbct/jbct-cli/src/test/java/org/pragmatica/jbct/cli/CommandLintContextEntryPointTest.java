package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// #2525, fix round 2: `check`, `lint` and `score` are tested through the entry point a user runs
/// (`CommandLine.execute` on the command, in a temp project), and the context they hand to the linter is observed at the one
/// place it is handed over, the command's `runFactory` (the plan it gives the run). A test that builds a context itself
/// instead cannot see a command build its own context, or drop `--mode`, inside `call()`.
class CommandLintContextEntryPointTest {
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String CHECK = "check";
    private static final String LINT = "lint";
    private static final String SCORE = "score";

    @TempDir
    Path tempDir;

    private String savedUserHome;
    private PrintStream savedOut;
    private PrintStream savedErr;
    private Path moduleA;
    private Path moduleB;

    @BeforeEach
    void fixture() throws Exception {
        savedUserHome = System.getProperty(USER_HOME_PROPERTY);
        savedOut = System.out;
        savedErr = System.err;
        System.setProperty(USER_HOME_PROPERTY, tempDir.resolve("home").toString());
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, UTF_8));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, UTF_8));

        var repo = Files.createDirectories(tempDir.resolve("repo"));

        Files.createDirectories(repo.resolve(".git"));
        moduleA = module(repo, "module-a", "business", "org.acme.alpha");
        moduleB = module(repo, "module-b", "infrastructure", "org.acme.beta");
    }

    @AfterEach
    void restore() {
        System.setProperty(USER_HOME_PROPERTY, savedUserHome);
        System.setOut(savedOut);
        System.setErr(savedErr);
    }

    private static Path module(Path repo, String name, String mode, String basePackage) throws Exception {
        var module = Files.createDirectories(repo.resolve(name));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"),
                          "[lint]\nmode = \"" + mode + "\"\n[project]\nbasePackage = \"" + basePackage + "\"\n");

        var source = Files.createDirectories(module.resolve("src/main/java/org/acme/" + name.replace("-", "")));

        Files.writeString(source.resolve("Marker.java"), "package org.acme." + name.replace("-", "") + ";\n\nclass Marker {}\n");

        return module.resolve("src/main/java");
    }

    /// Run the named command through `CommandLine.execute`; returns every context the command handed to the run, one per source root.
    private List<LintContext> handedOver(String command, String... args) {
        var recorded = new ArrayList<LintContext>();
        Function<RootPlan, LintRun> recorder = plan -> recordAndRun(recorded, plan);

        switch (command) {
            case CHECK -> {
                var check = new CheckCommand();

                check.runFactory = recorder;
                new CommandLine(check).execute(args);
            }
            case LINT -> {
                var lint = new LintCommand();

                lint.runFactory = recorder;
                new CommandLine(lint).execute(args);
            }
            default -> {
                var score = new ScoreCommand();

                score.runFactory = recorder;
                new CommandLine(score).execute(args);
            }
        }

        return recorded;
    }

    private static LintRun recordAndRun(List<LintContext> recorded, RootPlan plan) {
        plan.roots().forEach(root -> recorded.add(root.context()));

        return LintRun.lintRun(plan);
    }

    private LintContext single(String command, String... args) {
        var recorded = handedOver(command, args);

        assertThat(recorded).hasSize(1);

        return recorded.getFirst();
    }

    @ParameterizedTest
    @ValueSource(strings = {CHECK, LINT, SCORE})
    void command_tomlMode_reachesTheLinterContext(String command) {
        assertThat(single(command, moduleB.toString()).mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
    }

    @ParameterizedTest
    @ValueSource(strings = {CHECK, LINT, SCORE})
    void command_modeFlag_winsOverTheToml(String command) {
        var context = single(command, "--mode", "business", moduleB.toString());

        assertThat(context.mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(context.basePackage()).isEqualTo(Option.some("org.acme.beta"));
    }

    @ParameterizedTest
    @ValueSource(strings = {CHECK, LINT, SCORE})
    void command_tomlBasePackage_reachesTheLinterContext(String command) {
        assertThat(single(command, moduleB.toString()).basePackage()).isEqualTo(Option.some("org.acme.beta"));
    }

    @ParameterizedTest
    @ValueSource(strings = {CHECK, LINT, SCORE})
    void command_pathInAnotherModuleThanTheWorkingDirectory_takesThatModulesToml(String command) {
        var a = single(command, moduleA.toString());
        var b = single(command, moduleB.toString());

        assertThat(a.mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(a.basePackage()).isEqualTo(Option.some("org.acme.alpha"));
        assertThat(b.mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(b.basePackage()).isEqualTo(Option.some("org.acme.beta"));
    }
}
