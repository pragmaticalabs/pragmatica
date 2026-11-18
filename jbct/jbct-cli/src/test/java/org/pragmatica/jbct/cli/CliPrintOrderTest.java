package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// #2525 P3 (mutant e): what a command prints is the lines before the findings, then the findings, then the lines after
/// them, with the release's blank line in front of the lines after. A front end that printed the after-lines first, or
/// between, would still print every sentence; only the order tells.
class CliPrintOrderTest {
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String UNFORMATTED = """
                                              package org.acme.order.domain.pricing;

                                              public class OrderPrice {
                                              int run(){
                                              var x=1;
                                              return x;
                                              }
                                              }
                                              """;

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private String savedUserHome;
    private PrintStream savedOut;
    private PrintStream savedErr;
    private Path sourceRoot;
    private CapturedRun captured = new CapturedRun();

    @BeforeEach
    void fixture() throws Exception {
        savedUserHome = System.getProperty(USER_HOME_PROPERTY);
        savedOut = System.out;
        savedErr = System.err;
        System.setProperty(USER_HOME_PROPERTY, tempDir.resolve("home").toString());
        System.setOut(new PrintStream(out, true, UTF_8));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, UTF_8));

        var module = Files.createDirectories(tempDir.resolve("repo/order"));

        Files.createDirectories(tempDir.resolve("repo/.git"));
        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"), "[project]\nbasePackage = \"org.acme.order\"\n");
        sourceRoot = Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(Files.createDirectories(sourceRoot.resolve("org/acme/order/domain/pricing"))
                               .resolve("OrderPrice.java"),
                          UNFORMATTED);
    }

    @AfterEach
    void restore() {
        System.setProperty(USER_HOME_PROPERTY, savedUserHome);
        System.setOut(savedOut);
        System.setErr(savedErr);
    }

    @Test
    void check_printsBeforeLinesThenFindingsThenAfterLines() {
        var command = new CheckCommand();

        command.runSeam = captured;
        new CommandLine(command).execute(sourceRoot.toString());

        var stdout = out.toString(UTF_8);
        var finding = captured.result().findings();

        assertThat(finding).as("the fixture must produce a lint finding for the order to be observable").isNotEmpty();

        var head = stdout.indexOf("Files not properly formatted:");
        var found = stdout.indexOf(finding.getFirst().toHumanReadable());
        var summary = stdout.indexOf("Check results (");

        assertThat(head).as("before-line printed").isNotNegative();
        assertThat(found).as("finding printed").isGreaterThan(head);
        assertThat(summary).as("after-line comes after the findings").isGreaterThan(found);
        assertThat(stdout).as("blank line frames the after-lines").contains("\n\nCheck results (");
        assertThat(stdout).as("DETAIL lines stay hidden without verbose (C4)")
                          .isEqualTo(captured.expectedStdout(false))
                          .doesNotContain("Checking ");
    }

    @Test
    void format_printsBeforeLinesThenAfterLines() {
        var command = new FormatCommand();

        command.runSeam = captured;
        new CommandLine(command).execute(sourceRoot.toString());

        var lines = List.of(out.toString(UTF_8).split("\n"));
        var root = indexStarting(lines, "mode=");
        var summary = indexStarting(lines, "Formatted: ");

        assertThat(root).as("before-line printed").isNotNegative();
        assertThat(summary).as("after-line comes after the before-lines").isGreaterThan(root);
        assertThat(lines.get(summary - 1)).as("blank line frames the summary").isEmpty();
    }

    private static int indexStarting(List<String> lines, String prefix) {
        return lines.indexOf(lines.stream()
                                  .filter(line -> line.startsWith(prefix))
                                  .findFirst()
                                  .orElse("<absent>"));
    }
}
