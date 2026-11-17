package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.pragmatica.jbct.config.JbctMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// #2525 P2c: `format` and the format pass of `check` build one formatter per argument path's source root from that
/// root's own `[format]` settings, and leave a `disabled` root alone. Two modules differ only in `indentSize`, so the
/// indentation of a formatted file can only come from its own module's toml.
class CliPerRootFormatTest {
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String UNFORMATTED = """
                                              package org.acme.%s.domain.pricing;

                                              public class %sPrice {
                                              int run(){
                                              var x=1;
                                              return x;
                                              }
                                              }
                                              """;

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private String savedUserHome;
    private PrintStream savedOut;
    private PrintStream savedErr;
    private Path narrow;
    private Path wide;

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
        narrow = module(repo, "narrow", 2);
        wide = module(repo, "wide", 8);
    }

    @AfterEach
    void restore() {
        System.setProperty(USER_HOME_PROPERTY, savedUserHome);
        System.setOut(savedOut);
        System.setErr(savedErr);
    }

    private static Path module(Path repo, String name, int indent) throws Exception {
        var module = Files.createDirectories(repo.resolve(name));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"),
                          "[project]\nbasePackage = \"org.acme." + name + "\"\n[format]\nindentSize = " + indent + "\n");

        var source = Files.createDirectories(module.resolve("src/main/java/org/acme/" + name + "/domain/pricing"));

        Files.writeString(source.resolve(name + "Price.java"), UNFORMATTED.formatted(name, name));

        return module.resolve("src/main/java");
    }

    private static Path fileOf(Path sourceRoot, String name) {
        return sourceRoot.resolve("org/acme/" + name + "/domain/pricing/" + name + "Price.java");
    }

    private static String read(Path file) throws Exception {
        return Files.readString(file);
    }

    private static int indentOfStatement(Path file) throws Exception {
        return Files.readAllLines(file)
                    .stream()
                    .filter(line -> line.strip().startsWith("var x"))
                    .map(line -> line.length() - line.stripLeading().length())
                    .findFirst()
                    .orElseThrow();
    }

    private int execute(Object command, String... args) {
        out.reset();
        err.reset();

        return new CommandLine(command).execute(args);
    }

    /// Puts the module `name` in `mode` through its own `jbct.toml`, keeping its `indentSize`, the way a user would.
    private void setMode(String name, JbctMode mode) throws Exception {
        var toml = tempDir.resolve("repo").resolve(name).resolve("jbct.toml");

        Files.writeString(toml, Files.readString(toml) + "[lint]\nmode = \"" + mode.token() + "\"\n");
    }

    @Test
    void format_twoRootsWithDifferentIndent_eachFileIsFormattedWithItsOwnRoot() throws Exception {
        var exit = execute(new FormatCommand(), narrow.toString(), wide.toString());

        assertThat(exit).isZero();
        assertThat(indentOfStatement(fileOf(narrow, "narrow"))).isEqualTo(4);
        assertThat(indentOfStatement(fileOf(wide, "wide"))).isEqualTo(16);
    }

    @Test
    void format_reversedArgumentOrder_givesTheSameFiles() throws Exception {
        execute(new FormatCommand(), wide.toString(), narrow.toString());

        assertThat(indentOfStatement(fileOf(narrow, "narrow"))).isEqualTo(4);
        assertThat(indentOfStatement(fileOf(wide, "wide"))).isEqualTo(16);
    }

    @Test
    void formatCheck_afterPerRootFormat_isCleanForBothRoots() {
        execute(new FormatCommand(), narrow.toString(), wide.toString());

        var exit = execute(new FormatCommand(), "--check", narrow.toString(), wide.toString());

        assertThat(exit).isZero();
        assertThat(out.toString()).contains("2 file(s) format-checked")
                                  .doesNotContain("properly formatted");
    }

    @Test
    void formatCheck_onlyTheRootWhoseFileDriftedIsNamed() throws Exception {
        execute(new FormatCommand(), narrow.toString(), wide.toString());
        Files.writeString(fileOf(wide, "wide"), UNFORMATTED.formatted("wide", "wide"));

        var exit = execute(new FormatCommand(), "--check", narrow.toString(), wide.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(out.toString()).contains("needs formatting: " + fileOf(wide, "wide"))
                                  .doesNotContain("needs formatting: " + fileOf(narrow, "narrow"));
    }

    @Test
    void check_reportsTheFormatFindingOfTheRightRoot() throws Exception {
        execute(new FormatCommand(), narrow.toString(), wide.toString());
        Files.writeString(fileOf(wide, "wide"), UNFORMATTED.formatted("wide", "wide"));

        var exit = execute(new CheckCommand(), narrow.toString(), wide.toString());
        var report = out.toString();
        var formatSection = report.substring(report.indexOf("Files not properly formatted:"),
                                             report.indexOf("Files not properly formatted:") + 400);

        assertThat(exit).isEqualTo(1);
        assertThat(formatSection).contains(fileOf(wide, "wide").toString())
                                 .doesNotContain(fileOf(narrow, "narrow").toString());
        assertThat(report).contains("1 format issue(s)");
    }

    @Test
    void check_afterPerRootFormat_hasNoFormatFinding() {
        execute(new FormatCommand(), narrow.toString(), wide.toString());
        execute(new CheckCommand(), narrow.toString(), wide.toString());

        assertThat(out.toString()).contains("0 format issue(s)");
    }

    @Test
    void format_disabledRoot_isLeftByteIdenticalAndAnnounced() throws Exception {
        setMode("wide", JbctMode.DISABLED);
        var before = read(fileOf(wide, "wide"));
        var exit = execute(new FormatCommand(), narrow.toString(), wide.toString());

        assertThat(exit).isZero();
        assertThat(read(fileOf(wide, "wide"))).isEqualTo(before);
        assertThat(indentOfStatement(fileOf(narrow, "narrow"))).isEqualTo(4);
        assertThat(out.toString()).contains("mode=disabled")
                                  .contains("skipped, no format, no rule run")
                                  .contains("1 file(s) formatted");
        assertThat(count(out.toString(), "mode=disabled")).isEqualTo(1);
    }

    @Test
    void formatCheck_disabledRoot_hasNoFinding() throws Exception {
        setMode("wide", JbctMode.DISABLED);
        var exit = execute(new FormatCommand(), "--check", narrow.toString(), wide.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(out.toString()).contains("needs formatting: " + fileOf(narrow, "narrow"))
                                  .doesNotContain(fileOf(wide, "wide").toString());
    }

    @Test
    void check_disabledRoot_hasNoFormatFindingAndIsAnnounced() throws Exception {
        setMode("wide", JbctMode.DISABLED);
        execute(new CheckCommand(), narrow.toString(), wide.toString());

        assertThat(out.toString()).doesNotContain("needs formatting: " + fileOf(wide, "wide"));
        assertThat(out.toString()).contains("1 format issue(s)");
        assertThat(out.toString()).contains("mode=disabled")
                                  .contains("1 file(s) format-checked");
    }

    @Test
    void format_formatOnlyRoot_isFormattedWithItsOwnSettings() throws Exception {
        setMode("wide", JbctMode.FORMAT_ONLY);
        var exit = execute(new FormatCommand(), narrow.toString(), wide.toString());

        assertThat(exit).isZero();
        assertThat(indentOfStatement(fileOf(wide, "wide"))).isEqualTo(16);
        assertThat(err.toString()).doesNotContain("Not formatted");
    }

    @Test
    void format_singleRoot_keepsItsExitCodes() throws Exception {
        assertThat(execute(new FormatCommand(), "--check", wide.toString())).isEqualTo(1);
        assertThat(execute(new FormatCommand(), wide.toString())).isZero();
        assertThat(indentOfStatement(fileOf(wide, "wide"))).isEqualTo(16);
        assertThat(execute(new FormatCommand(), "--check", wide.toString())).isZero();
    }

    private static int count(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }
}
