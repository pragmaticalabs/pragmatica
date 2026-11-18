package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

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

/// #2525, F1: the context `lint`, `check` and `score` hand to the linter carries the source root's `[lint] mode`, a
/// `--mode` override and `[project] basePackage`. They used to build it without the project settings, so the mode was
/// always business and the base package always absent.
class CliLintContextTest {
    private static final String USER_HOME_PROPERTY = "user.home";

    @TempDir
    Path tempDir;

    private String savedUserHome;
    private Path sourceRoot;

    @BeforeEach
    void fixture() throws Exception {
        savedUserHome = System.getProperty(USER_HOME_PROPERTY);
        System.setProperty(USER_HOME_PROPERTY, tempDir.resolve("home").toString());

        var repo = Files.createDirectories(tempDir.resolve("repo"));

        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve("pom.xml"), "<project/>\n");
        Files.writeString(repo.resolve("jbct.toml"),
                          "[lint]\nmode = \"infrastructure\"\n[project]\nbasePackage = \"org.acme.billing\"\n");
        sourceRoot = Files.createDirectories(repo.resolve("src/main/java"));
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty(USER_HOME_PROPERTY, savedUserHome);
    }

    private LintContext contextOf(Object command, String modeFlag, Function<JbctConfig, LintContext> create) {
        var args = modeFlag == null
                   ? new String[] {sourceRoot.toString()}
                   : new String[] {"--mode", modeFlag, sourceRoot.toString()};

        new CommandLine(command).parseArgs(args);

        var mode = command instanceof LintCommand lint
                   ? lint.mode
                   : command instanceof CheckCommand check
                     ? check.mode
                     : ((ScoreCommand) command).mode;
        var err = new PrintStream(new ByteArrayOutputStream(), true, UTF_8);
        var config = CliConfig.load(Option.none(), Option.option(mode), sourceRoot, err).unwrap();

        return create.apply(config);
    }

    private LintContext lint(String modeFlag) {
        var command = new LintCommand();

        return contextOf(command, modeFlag, command::createContext);
    }

    private LintContext check(String modeFlag) {
        var command = new CheckCommand();

        return contextOf(command, modeFlag, command::createContext);
    }

    private LintContext score(String modeFlag) {
        var command = new ScoreCommand();

        return contextOf(command, modeFlag, command::createContext);
    }

    @Test
    void lint_contextCarriesTheTomlModeAndBasePackage() {
        var context = lint(null);

        assertThat(context.mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(context.basePackage()).isEqualTo(Option.some("org.acme.billing"));
    }

    @Test
    void check_contextCarriesTheTomlModeAndBasePackage() {
        var context = check(null);

        assertThat(context.mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(context.basePackage()).isEqualTo(Option.some("org.acme.billing"));
    }

    @Test
    void score_contextCarriesTheTomlModeAndBasePackage() {
        var context = score(null);

        assertThat(context.mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(context.basePackage()).isEqualTo(Option.some("org.acme.billing"));
    }

    @Test
    void lint_contextCarriesTheModeOverride_andKeepsBasePackage() {
        var context = lint("business");

        assertThat(context.mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(context.basePackage()).isEqualTo(Option.some("org.acme.billing"));
    }

    @Test
    void check_contextCarriesTheModeOverride_andKeepsBasePackage() {
        var context = check("business");

        assertThat(context.mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(context.basePackage()).isEqualTo(Option.some("org.acme.billing"));
    }

    @Test
    void score_contextCarriesTheModeOverride_andKeepsBasePackage() {
        var context = score("business");

        assertThat(context.mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(context.basePackage()).isEqualTo(Option.some("org.acme.billing"));
    }

    @Test
    void lintAndCheck_failOnWarningTightensTheLintSettings() {
        var lint = new LintCommand();
        var check = new CheckCommand();

        new CommandLine(lint).parseArgs("--fail-on-warning", sourceRoot.toString());
        new CommandLine(check).parseArgs("--fail-on-warning", sourceRoot.toString());

        assertThat(lint.createContext(JbctConfig.DEFAULT).config().failOnWarning()).isTrue();
        assertThat(check.createContext(JbctConfig.DEFAULT).config().failOnWarning()).isTrue();
    }
}
