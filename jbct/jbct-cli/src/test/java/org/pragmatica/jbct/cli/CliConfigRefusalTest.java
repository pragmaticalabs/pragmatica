package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;


/// #1455: every command that loads configuration stops with exit status 2 and names the file and the
/// key or line when a config layer is unusable, instead of running with that layer skipped.
class CliConfigRefusalTest {
    private static final String CLEAN = """
                                        public record Pure(String a) {
                                            public static Pure pure(String a) {
                                                return new Pure(a);
                                            }
                                        }
                                        """;

    @TempDir
    Path work;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureStreams() throws Exception {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, UTF_8));
        System.setErr(new PrintStream(err, true, UTF_8));
        Files.createDirectories(work.resolve("src"));
        Files.writeString(work.resolve("src").resolve("Pure.java"), CLEAN);
        Files.writeString(work.resolve("jacoco.xml"),
                          "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><report name=\"demo\"><package name=\"demo\"/></report>");
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private int run(String command, Path config) {
        var args = new ArrayList<String>();
        var source = work.resolve("src").toString();

        if (command.equals("obligations")) {
            args.add("--coverage=" + work.resolve("jacoco.xml"));
        }

        args.add("--config");
        args.add(config.toString());
        args.add(source);
        out.reset();
        err.reset();

        return new CommandLine(commandFor(command)).execute(args.toArray(String[]::new));
    }

    private static Object commandFor(String command) {
        return switch (command) {
            case "format" -> new FormatCommand();
            case "lint" -> new LintCommand();
            case "check" -> new CheckCommand();
            case "score" -> new ScoreCommand();
            case "shape-census" -> new ShapeCensusCommand();
            default -> new ObligationsCommand();
        };
    }

    private Path config(String content) throws Exception {
        var config = work.resolve("custom.toml");

        Files.writeString(config, content);

        return config;
    }

    @ParameterizedTest
    @ValueSource(strings = {"format", "lint", "check", "score", "shape-census", "obligations"})
    void command_stopsNamingFileKeyAndValue_whenConfigKeyHasWrongType(String command) throws Exception {
        var config = config("[format]\nmaxLineLength = \"120\"\n");

        assertThat(run(command, config)).isEqualTo(CliConfig.CONFIG_ERROR_EXIT);
        assertThat(err.toString(UTF_8)).contains(config.toString())
                                       .contains("format.maxLineLength")
                                       .contains("120");
    }

    @ParameterizedTest
    @ValueSource(strings = {"format", "lint", "check", "score", "shape-census", "obligations"})
    void command_stopsNamingFile_whenConfigHasSyntaxError(String command) throws Exception {
        var config = config("[format\nmaxLineLength = 100\n");

        assertThat(run(command, config)).isEqualTo(CliConfig.CONFIG_ERROR_EXIT);
        assertThat(err.toString(UTF_8)).contains("Invalid JBCT configuration")
                                       .contains(config.toString());
    }

    /// Positive control: the same invocation with a usable config does not print the refusal, so the
    /// assertions above cannot pass because the harness always fails.
    @ParameterizedTest
    @ValueSource(strings = {"format", "lint", "check", "score", "shape-census", "obligations"})
    void command_doesNotRefuse_whenConfigIsWellTyped(String command) throws Exception {
        var config = config("[format]\nmaxLineLength = 120\n");

        run(command, config);

        assertThat(err.toString(UTF_8)).doesNotContain("Invalid JBCT configuration");
    }
}
