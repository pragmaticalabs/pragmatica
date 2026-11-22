package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// #2525, C7: `--mode` on `lint`, `check`, `score` and `format` overrides the mode `jbct.toml` resolves, and
/// `--config` too: it folds last. All four modes are accepted; an unknown value is refused.
class CliModeOverrideTest {
    private static final String USER_HOME_PROPERTY = "user.home";

    @TempDir
    Path tempDir;

    private String savedUserHome;
    private Path sourceRoot;
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void fixture() throws Exception {
        savedUserHome = System.getProperty(USER_HOME_PROPERTY);
        System.setProperty(USER_HOME_PROPERTY, tempDir.resolve("home").toString());

        var repo = Files.createDirectories(tempDir.resolve("repo"));

        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve("pom.xml"), "<project/>\n");
        Files.writeString(repo.resolve("jbct.toml"), "[lint]\nmode = \"infrastructure\"\n");
        sourceRoot = Files.createDirectories(repo.resolve("src/main/java"));
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty(USER_HOME_PROPERTY, savedUserHome);
    }

    private PrintStream errStream() {
        return new PrintStream(err, true, UTF_8);
    }

    @Test
    void load_modeOverridesTheModeJbctTomlSets() {
        var loaded = CliConfig.load(Option.none(), Option.some("business"), sourceRoot, errStream());

        assertThat(loaded.unwrap().mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(err.toString(UTF_8)).isEmpty();
    }

    @Test
    void load_modeOverridesTheModeAConfigFileSets() throws Exception {
        var explicit = Files.writeString(tempDir.resolve("explicit.toml"), "[lint]\nmode = \"business\"\n");
        var loaded = CliConfig.load(Option.some(explicit), Option.some("infrastructure"), sourceRoot, errStream());

        assertThat(loaded.unwrap().mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
    }

    @Test
    void load_configFileWinsOverJbctToml_whenNoModeIsGiven() throws Exception {
        var explicit = Files.writeString(tempDir.resolve("explicit.toml"), "[lint]\nmode = \"business\"\n");
        var loaded = CliConfig.load(Option.some(explicit), Option.none(), sourceRoot, errStream());

        assertThat(loaded.unwrap().mode()).isEqualTo(JbctMode.BUSINESS);
    }

    @Test
    void load_keepsTheTomlMode_whenNoModeIsGiven() {
        var loaded = CliConfig.load(Option.none(), Option.none(), sourceRoot, errStream());

        assertThat(loaded.unwrap().mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
    }

    @Test
    void load_refusesAnUnknownMode_namingItOnStderr() {
        var loaded = CliConfig.load(Option.none(), Option.some("strict"), sourceRoot, errStream());

        assertThat(loaded.isEmpty()).isTrue();
        assertThat(err.toString(UTF_8)).contains("strict", JbctMode.ALLOWED);
    }

    @Test
    void load_acceptsFormatOnlyAndDisabled() {
        var formatOnly = CliConfig.load(Option.none(), Option.some("format-only"), sourceRoot, errStream());
        var disabled = CliConfig.load(Option.none(), Option.some("disabled"), sourceRoot, errStream());

        assertThat(formatOnly.unwrap().mode()).isEqualTo(JbctMode.FORMAT_ONLY);
        assertThat(disabled.unwrap().mode()).isEqualTo(JbctMode.DISABLED);
        assertThat(err.toString(UTF_8)).isEmpty();
    }

    @Test
    void lint_acceptsModeOption() {
        assertThat(new CommandLine(new LintCommand()).parseArgs("--mode", "business", "x").matchedOption("--mode")
                                                       .<String> getValue()).isEqualTo("business");
    }

    @Test
    void check_acceptsModeOption() {
        assertThat(new CommandLine(new CheckCommand()).parseArgs("--mode", "business", "x").matchedOption("--mode")
                                                        .<String> getValue()).isEqualTo("business");
    }

    @Test
    void score_acceptsModeOption() {
        assertThat(new CommandLine(new ScoreCommand()).parseArgs("--mode", "business", "x").matchedOption("--mode")
                                                        .<String> getValue()).isEqualTo("business");
    }

    @Test
    void format_acceptsModeOption() {
        assertThat(new CommandLine(new FormatCommand()).parseArgs("--mode", "business", "x").matchedOption("--mode")
                                                         .<String> getValue()).isEqualTo("business");
    }

    @Test
    void lint_exitsWithConfigErrorStatus_whenModeIsUnknown() {
        var originalErr = System.err;

        System.setErr(errStream());

        try {
            var exit = new CommandLine(new LintCommand()).execute("--mode", "strict", sourceRoot.toString());

            assertThat(exit).isEqualTo(CliConfig.CONFIG_ERROR_EXIT);
            assertThat(err.toString(UTF_8)).contains("strict", JbctMode.ALLOWED);
        } finally {
            System.setErr(originalErr);
        }
    }
}
