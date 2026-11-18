package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.jbct.lint.cst.RunOutcome.Line;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.jbct.lint.cst.RunOutcome.Kind;
import picocli.CommandLine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// #2525 P2c: what the shared resolver and run decide (roots, modes, counts, summary, verdict) is what every jbct command
/// prints, tested through `CommandLine.execute`. T1 builds a business and an infrastructure module and expects their findings, the
/// same tree and findings `jbct-maven-plugin`'s `MojoSharedRunTest` uses; the run's lines are pinned once, in `RunLinesTest`.
class CliSharedRunTest {
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String ARCH_02 = "JBCT-ARCH-02";
    private static final String NAM_07 = "JBCT-NAM-07";
    private static final String RET_03 = "JBCT-RET-03";
    private static final String PASSED_BANNER = "passed JBCT compliance check";
    private static final String NO_RULE_BANNER = "Nothing was linted: ";
    private static final Pattern JSON_FINDING = Pattern.compile("\"ruleId\": \"([^\"]+)\",\\s+\"severity\": \"[^\"]+\",\\s+\"file\": \"([^\"]+)\"");

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

    private static final List<String> BOTH_MODULES_FINDINGS = List.of("JBCT-ARCH-02@biz", "JBCT-RET-03@biz", "JBCT-NAM-07@biz", "JBCT-RET-03@infra");

    /// A business module `biz` and an infrastructure module `infra` below one repository root.
    private void bothModules() throws Exception {
        Files.createDirectories(repo.resolve(".git"));
        module("biz", "mode = \"business\"\n");
        module("infra", "mode = \"infrastructure\"\n");
    }

    private Path module(String name, String lintSection) throws Exception {
        var module = Files.createDirectories(repo.resolve(name));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"), "[project]\nbasePackage = \"org.acme." + name + "\"\n[lint]\n" + lintSection);

        var source = Files.createDirectories(module.resolve("src/main/java/org/acme/" + name + "/domain/pricing"));

        Files.writeString(source.resolve(name + "Price.java"), violating(name));

        return module.resolve("src/main/java");
    }

    private static String violating(String name) {
        return """
               package org.acme.%s.domain.pricing;

               public class %sPrice {
                   Object run() {
                       var attempt = Result.lift(Err::new, () -> 1);
                       return null;
                   }
               }
               """.formatted(name, name);
    }

    private CapturedRun captured = new CapturedRun();

    private int run(Object command, String... args) {
        out.reset();
        err.reset();
        captured = new CapturedRun();
        if (command instanceof FormatCommand format) {
            format.runSeam = captured;
        } else if (command instanceof CheckCommand check) {
            check.runSeam = captured;
        } else if (command instanceof LintCommand lint) {
            lint.runSeam = captured;
        } else if (command instanceof ScoreCommand score) {
            score.runSeam = captured;
        }

        return new CommandLine(command).execute(args);
    }

    /// What `format` or `check` printed is the report it received (never a second run over the tree): stdout is its lines and findings in order.
    private void assertPrintedTheReceivedReport() {
        assertThat(out.toString()).isEqualTo(captured.expectedStdout(false));
    }

    /// The run's own lines of the command just driven, joined: the sentences are the run's, never the test's.
    private String runText() {
        return captured.result().before().stream().map(Line::text).collect(Collectors.joining("\n")) + "\n"
               + captured.result().after().stream().map(Line::text).collect(Collectors.joining("\n"));
    }

    private String text() {
        return out + "\n" + err;
    }

    private static String root(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static List<String> lines(String text) {
        return text.lines().toList();
    }

    private static int count(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }

    private List<String> findings() {
        var found = new ArrayList<String>();
        var matcher = JSON_FINDING.matcher(out.toString());

        while (matcher.find()) {
            found.add(matcher.group(1) + "@" + (matcher.group(2).contains("bizPrice")
                                                ? "biz"
                                                : "infra"));
        }

        return found;
    }

    // ---- T1: one directory argument holding a business and an infrastructure module

    @Test
    void t1_oneDirectoryArgument_giveseachModuleItsOwnMode_andPrintsTheSharedText() throws Exception {
        bothModules();

        run(new LintCommand(), "--format", "json", repo.toString());

        assertThat(findings()).containsExactlyInAnyOrderElementsOf(BOTH_MODULES_FINDINGS);
        assertThat(lines(runText())).anyMatch(line -> line.startsWith("mode=business (from "))
                                    .anyMatch(line -> line.startsWith("mode=infrastructure (from "));
        assertThat(captured.result().examined()).isEqualTo(2);
        assertThat(lines(err.toString())).isEqualTo(captured.stderrLinesOfAll());
        assertThat(text()).doesNotContain("test-root files");
    }

    @Test
    void t1_oneDirectoryArgument_check_giveseachModuleItsOwnMode() throws Exception {
        bothModules();

        run(new CheckCommand(), repo.toString());

        assertThat(count(text(), ARCH_02)).isEqualTo(1);
        assertThat(count(text(), RET_03)).isEqualTo(2);
        assertPrintedTheReceivedReport();
    }

    // ---- T2: overlapping arguments examine each file once

    @Test
    void t2_overlappingArguments_examineEachFileOnce_andPrintOneSummaryLinePerRoot() throws Exception {
        var biz = module("biz", "mode = \"business\"\n");

        run(new LintCommand(), "--format", "json", biz.toString(), biz.resolve("org").toString(), biz.resolve("./org").toString());

        assertThat(findings()).containsExactlyInAnyOrder(ARCH_02 + "@biz", RET_03 + "@biz", NAM_07 + "@biz");
        assertThat(count(runText(), "root=" + root(biz))).isEqualTo(1);
        assertThat(captured.result().examined()).isOne();
    }

    // ---- T3: a format-only root with an unparseable file

    @Test
    void t3_formatOnlyRootWithUnparseableFile_lintReportsNothing_formatReportsItsFinding() throws Exception {
        var infra = module("infra", "mode = \"format-only\"\n");
        var broken = infra.resolve("org/acme/infra/domain/pricing/infraPrice.java");

        Files.writeString(broken, "class {{{ not java");

        assertThat(run(new LintCommand(), infra.toString())).isZero();
        assertThat(captured.result().findings()).isEmpty();
        assertThat(captured.result().examined()).isZero();
        assertThat(captured.result().skippedByMode()).isOne();
        run(new FormatCommand(), "--check", infra.toString());
        assertPrintedTheReceivedReport();
        assertThat(text()).contains(broken.getFileName().toString());
    }

    // ---- T4: no compliance pass when no root ran a rule; counts exclude skipped files

    @Test
    void t4_noRootRanARule_lintAndCheckSayNoComplianceAndCountNothingExamined() throws Exception {
        var infra = module("infra", "mode = \"format-only\"\n");
        var off = module("off", "mode = \"disabled\"\n");

        assertThat(run(new LintCommand(), infra.toString(), off.toString())).isZero();
        assertThat(runText()).contains(NO_RULE_BANNER).doesNotContain(PASSED_BANNER);
        assertThat(captured.result().examined()).isZero();
        assertThat(captured.result().skippedByMode()).isEqualTo(2);
        assertThat(run(new CheckCommand(), off.toString())).isZero();
        assertPrintedTheReceivedReport();
        assertThat(captured.stdoutLines(false)).anyMatch(line -> line.contains(root(off)));
        run(new FormatCommand(), infra.toString());
        assertThat(run(new CheckCommand(), infra.toString())).isZero();
        assertPrintedTheReceivedReport();
        assertThat(captured.result().verdict().kind()).isEqualTo(Kind.CLEAN);
        assertThat(captured.stdoutLines(false)).anyMatch(line -> line.contains(root(infra)));
    }

    @Test
    void t4_formatCheck_everyRootDisabled_printsTheRunsLine_andNoPassSentence() throws Exception {
        var off = module("off", "mode = \"disabled\"\n");

        assertThat(run(new FormatCommand(), "--check", off.toString())).isZero();
        assertPrintedTheReceivedReport();
        assertThat(captured.stdoutLines(false)).anyMatch(line -> line.contains(root(off)));
        assertThat(captured.result().examined()).isZero();
    }

    @Test
    void t4_score_everyRootUnscored_printsTheRunsLines() throws Exception {
        var infra = module("infra", "mode = \"format-only\"\n");
        var off = module("off", "mode = \"disabled\"\n");

        assertThat(run(new ScoreCommand(), infra.toString(), off.toString())).isZero();
        assertThat(lines(err.toString())).isEqualTo(captured.stderrLinesOfAll());
        assertThat(runText()).contains(root(infra), root(off));
        assertThat(captured.result().examined()).isZero();
        assertThat(captured.result().verdict().kind()).isEqualTo(Kind.CLEAN);
    }

    @Test
    void t4_machineFormats_allSkippedByMode_saySkippedByMode_notNoJavaFilesFound() throws Exception {
        var off = module("off", "mode = \"disabled\"\n");

        assertThat(run(new LintCommand(), "--format", "sarif", off.toString())).isZero();
        assertThat(out.toString()).contains("\"executionSuccessful\": " + captured.result().executionSuccessful())
                                  .doesNotContain("No Java files found");
        assertThat(captured.result().skippedByMode()).isOne();
        assertThat(run(new LintCommand(), "--format", "json", off.toString())).isZero();
        assertThat(out.toString()).contains("\"outcome\": [\"" + captured.afterBody().getFirst() + "\"]")
                                  .contains("\"skippedByMode\": 1")
                                  .doesNotContain("No Java files found");
    }

    @Test
    void t4_oneRootRanARule_countsExcludeTheSkippedRootsFiles() throws Exception {
        var biz = module("biz", "mode = \"business\"\n");
        var off = module("off", "mode = \"disabled\"\n");

        run(new LintCommand(), biz.toString(), off.toString());

        assertThat(runText()).contains("root=" + root(biz), "root=" + root(off));
        assertThat(captured.result().examined()).isOne();
        assertThat(captured.result().skippedByMode()).isOne();
        assertThat(runText()).doesNotContain(NO_RULE_BANNER);
    }

    @Test
    void t4_format_countsExcludeTheSkippedRoot() throws Exception {
        var biz = module("biz", "mode = \"business\"\n");
        var off = module("off", "mode = \"disabled\"\n");

        run(new FormatCommand(), "--check", biz.toString(), off.toString());

        assertPrintedTheReceivedReport();
        assertThat(captured.stdoutLines(false)).anyMatch(line -> line.contains("root=" + root(off)));
        assertThat(captured.result().skippedByMode()).isEqualTo(1);
    }

    // ---- T5: the summary line per mode, with where the mode came from, in every command; one line per root

    @Test
    void t5_summaryLineNamesTheModeAndWhereItCameFrom_inEveryCommand() throws Exception {
        var biz = module("biz", "mode = \"business\"\n");
        var plain = tempDir.resolve("plain/src/main/java/org/acme");

        Files.createDirectories(plain);
        Files.writeString(plain.resolve("A.java"), "package org.acme;\n\npublic class A {}\n");
        var plainRoot = tempDir.resolve("plain/src/main/java");

        for (Object command : List.of(new LintCommand(), new CheckCommand(), new ScoreCommand(), new FormatCommand())) {
            run(command, biz.toString(), biz.resolve("org").toString());
            assertThat(text()).as(command.getClass().getSimpleName() + " toml")
                              .contains("mode=business (from " + root(biz.getParent().getParent().getParent().resolve("jbct.toml")) + ") root=" + root(biz));
            assertThat(count(text(), "root=" + root(biz))).as(command.getClass().getSimpleName() + " one line").isEqualTo(1);
        }
        for (Object command : List.of(new LintCommand(), new CheckCommand(), new ScoreCommand(), new FormatCommand())) {
            run(command, "--mode", "infrastructure", biz.toString());
            assertThat(text()).as(command.getClass().getSimpleName() + " override").contains("mode=infrastructure (from --mode)");
        }
        for (Object command : List.of(new LintCommand(), new CheckCommand(), new ScoreCommand(), new FormatCommand())) {
            run(command, plainRoot.toString());
            assertThat(text()).as(command.getClass().getSimpleName() + " default").contains("mode=business (default) root=");
        }
    }

    // ---- T6: failOnWarning in the root's toml fails the run; --fail-on-warning overrides

    @Test
    void t6_failOnWarningInTheRootsToml_failsTheRun_andTheFlagOverridesAnUnsetToml() throws Exception {
        var strict = module("strict", "mode = \"infrastructure\"\nfailOnWarning = true\n[lint.rules]\nJBCT-RET-03 = \"warning\"\n");
        var lenient = module("lenient", "mode = \"infrastructure\"\n[lint.rules]\nJBCT-RET-03 = \"warning\"\n");

        assertThat(run(new LintCommand(), lenient.toString())).isZero();
        assertThat(run(new LintCommand(), strict.toString())).isEqualTo(1);
        assertThat(run(new LintCommand(), "--fail-on-warning", lenient.toString())).isEqualTo(1);
    }

    // ---- S1: every message listing modes names all four

    @Test
    void s1_modeHelpOfEveryCommandNamesAllFourModes() {
        for (Object command : List.of(new LintCommand(), new CheckCommand(), new ScoreCommand(), new FormatCommand())) {
            run(command, "--help");
            assertThat(text()).as(command.getClass().getSimpleName())
                              .contains("business", "infrastructure", "format-only", "disabled");
        }
    }

    // ---- S3: score with --max-density and every root unscored passes and is announced

    @Test
    void s3_scoreWithMaxDensityAndEveryRootUnscored_passesAndAnnouncesIt() throws Exception {
        var off = module("off", "mode = \"disabled\"\n");

        assertThat(run(new ScoreCommand(), "--max-density", "1", off.toString())).isZero();
        assertThat(lines(err.toString())).isEqualTo(captured.stderrLinesOfAll());
        assertThat(captured.result().verdict().kind()).isEqualTo(Kind.CLEAN);
    }

    @Test
    void s3_scoreWithoutMaxDensity_doesNotMentionMaxDensity() throws Exception {
        var off = module("off", "mode = \"disabled\"\n");

        assertThat(run(new ScoreCommand(), off.toString())).isZero();
        assertThat(text()).doesNotContain("maxDensity").doesNotContain("max-density");
    }
}
