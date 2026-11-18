package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/// The `skeleton` subcommand through the real `jbct` command line: wiring, output, exit codes and what goes to stderr.
class SkeletonCommandTest {
    @TempDir
    Path dir;

    private record Run(int exit, String out, String err) {}

    @Test
    void skeleton_isWiredIntoTheJbctCommand() {
        var run = run("skeleton", "--help");

        assertThat(run.exit()).isZero();
        assertThat(run.out()).contains("jbct skeleton").contains("--fields").contains("--json");
    }

    @Test
    void oneFile_printsTheGoldenWithoutAHeader_andExitsZero() throws IOException {
        var file = copy("Nested.java", "Nested.java");

        var run = run("skeleton", "--fields", file.toString());

        assertThat(run.exit()).isZero();
        assertThat(run.err()).isEmpty();
        assertThat(run.out()).isEqualTo(resource("Nested.skeleton.txt"));
    }

    @Test
    void withoutFields_noFieldLinesArePrinted() throws IOException {
        var run = run("skeleton", copy("Nested.java", "Nested.java").toString());

        assertThat(run.out()).contains("class Nested").doesNotContain("field ");
    }

    @Test
    void json_printsOneObjectPerFile() throws IOException {
        var run = run("skeleton", "--json", copy("Records.java", "Records.java").toString());

        assertThat(run.exit()).isZero();
        assertThat(run.out()).startsWith("[{\"file\": \"").contains("\"kind\": \"interface\", \"name\": \"Records\"").contains("\"startRow\": 4");
    }

    @Test
    void severalFiles_areEachPrecededByTheirPath() throws IOException {
        var one = copy("Docs.java", "Docs.java");
        var two = copy("Trivia.java", "Trivia.java");

        var run = run("skeleton", one.toString(), two.toString());

        assertThat(run.out()).contains(one + "\nclass Docs ").contains(two + "\nclass Trivia ");
    }

    @Test
    void aFileThatDoesNotParse_failsWithItsPosition_andPrintsNoSkeletonForIt_whileOthersStillPrint() throws IOException {
        var broken = copy("Broken.java.txt", "Broken.java");
        var good = copy("Docs.java", "Docs.java");

        var run = run("skeleton", broken.toString(), good.toString());

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.err()).containsPattern("Broken\\.java:\\d+:\\d+: \\S");
        assertThat(run.out()).contains("class Docs").doesNotContain("Broken").doesNotContain("fine");
    }

    @Test
    void aDirectoryWithoutJavaFiles_failsWithAMessage() {
        var run = run("skeleton", dir.toString());

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.err()).contains("No Java files found");
    }

    private Path copy(String resource, String name) throws IOException {
        return Files.writeString(dir.resolve(name), resource(resource));
    }

    private static String resource(String name) {
        try (var in = SkeletonCommandTest.class.getResourceAsStream("/skeleton/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("fixture unreadable: " + name, e);
        }
    }

    private static Run run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var oldOut = System.out;
        var oldErr = System.err;

        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            var exit = new CommandLine(new JbctCommand()).execute(args);

            return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }
}
