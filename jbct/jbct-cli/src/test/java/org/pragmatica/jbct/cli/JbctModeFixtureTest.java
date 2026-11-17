package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.jbct.config.JbctMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// #2525 acceptance, CLI front end: one fixture module per mode in an isolated temp repository with its own `.git`
/// marker, so no repository config leaks in. Every module holds a structure violation (ARCH-02: `lift` in a `domain`
/// package, business only), a code-level one (RET-03: `return null`, business and infrastructure) and one unformatted
/// file. What fires in each mode is read off `lint`, `check`, `format`, `format --check` and `score`, with the summary
/// line asserted. The Maven goals have their own copy of this test in the plugin module.
class JbctModeFixtureTest {
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String ARCH_02 = "JBCT-ARCH-02";
    private static final String RET_03 = "JBCT-RET-03";
    private static final String NEEDS_FORMAT = "Files not properly formatted:";

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

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private String savedUserHome;
    private PrintStream savedOut;
    private PrintStream savedErr;
    private Path repo;

    @BeforeEach
    void fixture() throws Exception {
        savedUserHome = System.getProperty(USER_HOME_PROPERTY);
        savedOut = System.out;
        savedErr = System.err;
        System.setProperty(USER_HOME_PROPERTY, tempDir.resolve("home").toString());
        System.setOut(new PrintStream(out, true, UTF_8));
        System.setErr(new PrintStream(err, true, UTF_8));
        repo = Files.createDirectories(tempDir.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
    }

    @AfterEach
    void restore() {
        System.setProperty(USER_HOME_PROPERTY, savedUserHome);
        System.setOut(savedOut);
        System.setErr(savedErr);
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

        return module.resolve("src/main/java");
    }

    private static Path price(Path root, String name) {
        return root.resolve("org/acme/" + name + "/domain/pricing/Price.java");
    }

    private static String root(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private int run(Object command, String... args) {
        out.reset();
        err.reset();

        return new CommandLine(command).execute(args);
    }

    private String text() {
        return out + "\n" + err;
    }

    private static int statementIndent(Path file) throws Exception {
        return Files.readAllLines(file)
                    .stream()
                    .filter(line -> line.strip().startsWith("var x"))
                    .map(line -> line.length() - line.stripLeading().length())
                    .findFirst()
                    .orElseThrow();
    }

    // ---- business: all three fire

    @Test
    void business_lintAndCheck_reportStructureRuleCodeRuleAndFormatFinding() throws Exception {
        var root = module("biz", JbctMode.BUSINESS);

        assertThat(run(new LintCommand(), root.toString())).isEqualTo(1);
        assertThat(text()).contains(ARCH_02, RET_03).contains("mode=business (from ", ") root=" + root(root), "; test-root rule: ");
        assertThat(run(new CheckCommand(), root.toString())).isEqualTo(1);
        assertThat(text()).contains(ARCH_02, RET_03, NEEDS_FORMAT, price(root, "biz").toString())
                          .contains("mode=business (from ", ") root=" + root(root), "; test-root rule: ");
    }

    @Test
    void business_format_writesTheUnformattedFile_andScoreScoresIt() throws Exception {
        var root = module("biz", JbctMode.BUSINESS);

        assertThat(run(new FormatCommand(), "--check", root.toString())).isEqualTo(1);
        assertThat(text()).contains(price(root, "biz").toString());
        assertThat(run(new FormatCommand(), root.toString())).isZero();
        assertThat(statementIndent(price(root, "biz"))).isEqualTo(8);
        run(new ScoreCommand(), root.toString());
        assertThat(text()).doesNotContain("Not scored");
        assertThat(out.toString()).isNotBlank();
    }

    // ---- infrastructure: RET-03 and the format finding, no ARCH-02

    @Test
    void infrastructure_lintAndCheck_reportCodeRuleAndFormatFindingButNoStructureRule() throws Exception {
        var root = module("infra", JbctMode.INFRASTRUCTURE);

        assertThat(run(new LintCommand(), root.toString())).isEqualTo(1);
        assertThat(text()).contains(RET_03).doesNotContain(ARCH_02)
                          .contains("mode=infrastructure (from ", ") root=" + root(root), "; test-root rule: ").contains("structure checks off");
        assertThat(run(new CheckCommand(), root.toString())).isEqualTo(1);
        assertThat(text()).contains(RET_03, NEEDS_FORMAT, price(root, "infra").toString()).doesNotContain(ARCH_02)
                          .contains("mode=infrastructure (from ", ") root=" + root(root), "; test-root rule: ").contains("structure checks off");
    }

    @Test
    void infrastructure_format_writesTheUnformattedFile() throws Exception {
        var root = module("infra", JbctMode.INFRASTRUCTURE);

        assertThat(run(new FormatCommand(), root.toString())).isZero();
        assertThat(statementIndent(price(root, "infra"))).isEqualTo(8);
    }

    // ---- format-only: only the format finding

    @Test
    void formatOnly_lint_runsNoRuleAndSaysSo() throws Exception {
        var root = module("fmt", JbctMode.FORMAT_ONLY);

        assertThat(run(new LintCommand(), root.toString())).isZero();
        assertThat(text()).doesNotContain("JBCT-").contains("mode=format-only (from ", ") root=" + root(root), "; test-root rule: ").contains("format only, no rule run");
    }

    @Test
    void formatOnly_check_keepsTheFormatFindingAndRunsNoRule() throws Exception {
        var root = module("fmt", JbctMode.FORMAT_ONLY);

        assertThat(run(new CheckCommand(), root.toString())).isEqualTo(1);
        assertThat(text()).contains(NEEDS_FORMAT, price(root, "fmt").toString()).doesNotContain("JBCT-")
                          .contains("mode=format-only (from ", ") root=" + root(root), "; test-root rule: ").contains("format only, no rule run");
    }

    @Test
    void formatOnly_format_writesTheFile_andFormatCheckFlagsIt() throws Exception {
        var root = module("fmt", JbctMode.FORMAT_ONLY);

        assertThat(run(new FormatCommand(), "--check", root.toString())).isEqualTo(1);
        assertThat(text()).contains(price(root, "fmt").toString());
        assertThat(run(new FormatCommand(), root.toString())).isZero();
        assertThat(statementIndent(price(root, "fmt"))).isEqualTo(8);
        assertThat(run(new FormatCommand(), "--check", root.toString())).isZero();
    }

    @Test
    void formatOnly_score_saysNotScored() throws Exception {
        var root = module("fmt", JbctMode.FORMAT_ONLY);

        assertThat(run(new ScoreCommand(), root.toString())).isZero();
        assertThat(err.toString()).contains("Not scored: " + root(root) + " (mode format-only skips it)");
        assertThat(out.toString()).isBlank();
    }

    // ---- disabled: none; nothing is written

    @Test
    void disabled_lintAndCheck_reportNothingButTheSkipAnnouncement() throws Exception {
        var root = module("off", JbctMode.DISABLED);

        assertThat(run(new LintCommand(), root.toString())).isZero();
        assertThat(text()).doesNotContain("JBCT-").contains("mode=disabled (from ", ") root=" + root(root), "; test-root rule: ").contains("skipped, no format, no rule run");
        assertThat(run(new CheckCommand(), root.toString())).isZero();
        assertThat(text()).doesNotContain("JBCT-").doesNotContain(NEEDS_FORMAT)
                          .contains("mode=disabled (from ", ") root=" + root(root), "; test-root rule: ").contains("skipped, no format, no rule run")
                          .contains("Nothing was format-checked: " + root(root) + " (mode disabled skips it)");
    }

    @Test
    void disabled_formatAndFormatCheck_writeNothingAndFlagNothing() throws Exception {
        var root = module("off", JbctMode.DISABLED);
        var before = Files.readString(price(root, "off"));

        assertThat(run(new FormatCommand(), "--check", root.toString())).isZero();
        assertThat(text()).doesNotContain(price(root, "off").toString()).contains("Nothing was format-checked: " + root(root) + " (mode disabled skips it)");
        assertThat(run(new FormatCommand(), root.toString())).isZero();
        assertThat(text()).contains("Nothing was formatted: " + root(root) + " (mode disabled skips it)");
        assertThat(Files.readString(price(root, "off"))).isEqualTo(before);
    }

    @Test
    void disabled_score_saysNotScored() throws Exception {
        var root = module("off", JbctMode.DISABLED);

        assertThat(run(new ScoreCommand(), root.toString())).isZero();
        assertThat(err.toString()).contains("Not scored: " + root(root) + " (mode disabled skips it)");
        assertThat(out.toString()).isBlank();
    }

    // ---- mixed-mode arm

    @Test
    void mixedParent_disabledModuleStaysByteIdentical_formatOnlyModuleIsWrittenOrFlagged() throws Exception {
        var off = module("off", JbctMode.DISABLED);
        var fmt = module("fmt", JbctMode.FORMAT_ONLY);
        var before = Files.readString(price(off, "off"));

        assertThat(run(new FormatCommand(), "--check", off.toString(), fmt.toString())).isEqualTo(1);
        assertThat(text()).contains(price(fmt, "fmt").toString()).doesNotContain(price(off, "off").toString());
        assertThat(run(new CheckCommand(), off.toString(), fmt.toString())).isEqualTo(1);
        assertThat(text()).contains(NEEDS_FORMAT, price(fmt, "fmt").toString()).doesNotContain(price(off, "off").toString())
                          .doesNotContain("JBCT-");
        assertThat(run(new FormatCommand(), off.toString(), fmt.toString())).isZero();
        assertThat(Files.readString(price(off, "off"))).isEqualTo(before);
        assertThat(statementIndent(price(fmt, "fmt"))).isEqualTo(8);
    }

    // ---- override arm

    @Test
    void modeOptionDisabled_overABusinessModule_flagsNothingAndWritesNothing() throws Exception {
        var root = module("biz", JbctMode.BUSINESS);
        var before = Files.readString(price(root, "biz"));

        assertThat(run(new CheckCommand(), "--mode", "disabled", root.toString())).isZero();
        assertThat(text()).doesNotContain("JBCT-").doesNotContain(NEEDS_FORMAT).contains("mode=disabled (from --mode) root=" + root(root), "Nothing was linted: " + root(root) + " (mode disabled skips it)");
        assertThat(run(new FormatCommand(), "--mode", "disabled", root.toString())).isZero();
        assertThat(Files.readString(price(root, "biz"))).isEqualTo(before);
    }

    @Test
    void unknownMode_isRefusedListingAllFour() throws Exception {
        var root = module("biz", JbctMode.BUSINESS);

        assertThat(run(new LintCommand(), "--mode", "strict", root.toString())).isEqualTo(CliConfig.CONFIG_ERROR_EXIT);
        assertThat(err.toString()).contains("strict", "business", "infrastructure", "format-only", "disabled");
    }
}
