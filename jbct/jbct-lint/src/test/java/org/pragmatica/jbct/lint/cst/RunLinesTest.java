package org.pragmatica.jbct.lint.cst;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.cst.RootResolver.ModeSource;
import org.pragmatica.jbct.lint.cst.RunOutcome.DensityLimit;
import org.pragmatica.jbct.lint.cst.RunOutcome.FileResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.FormatAction;
import org.pragmatica.jbct.lint.cst.RunOutcome.FormatOutcome;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Level;
import org.pragmatica.jbct.lint.cst.RunOutcome.Line;
import org.pragmatica.jbct.lint.cst.RunOutcome.LintOutcome;
import org.pragmatica.jbct.lint.cst.RunOutcome.MavenModule;
import org.pragmatica.jbct.lint.cst.RunOutcome.RootResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.Surface;
import org.pragmatica.jbct.lint.cst.RunOutcome.Tally;
import org.pragmatica.jbct.lint.cst.RunOutcome.Verdict;
import org.pragmatica.jbct.score.DensityGate;
import org.pragmatica.jbct.score.ScoreCalculator;
import org.pragmatica.jbct.score.ScoreReport;
import org.pragmatica.jbct.score.ScoreResult;
import org.pragmatica.jbct.score.SourceScan;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Every sentence a performed run prints, per command and surface (#2525, step 2b). The literal texts live here and
/// only here; they are the release tip's wording (`ec9744dd0`), except the lines the redesign memo marks as new or
/// changed (T1, T14, T15, T18 from tallies, T19, T22, T26), which hold the memo's text.
///
/// Score lines (`Measuring N Java file(s)`, `Not scored:`, the not-evaluated density line and the score failure text) are
/// pinned in the `score_` tests. The root line (T1) is dropped from the lists compared as a whole and
/// is pinned by its own tests.
class RunLinesTest {
    private static final Path ROOT = Path.of("src/main/java");
    private static final Path TEST_ROOT = Path.of("src/test/java");
    private static final String A = "src/main/java/A.java";
    private static final String B = "src/main/java/B.java";
    private static final String C = "src/main/java/C.java";
    private static final String T = "src/test/java/T.java";
    private static final String GAP_LINT = "Parse errors: 1 — 1 file(s) could not be read or parsed and were NOT analysed by JBCT lint. "
                                           + "This is a COVERAGE GAP, not a pass: this run is not evidence about those file(s).";
    private static final String GAP_FORMAT = GAP_LINT.replace("JBCT lint", "JBCT format");
    private static final String REFUSAL = "Formatter refused A.java: comment lost: `// c` (input line 3); the file was left untouched";
    private static final String GAP_FORMAT_CHECK = GAP_LINT.replace("JBCT lint", "JBCT format-check");
    private static final Verdict CLEAN = Verdict.clean();
    private static final Verdict FINDINGS = Verdict.findings("findings");

    // ---------------------------------------------------------------- nothing collected (T2, T3, T4)

    @Test
    void nothingCollected_cliLint_saysCoverageGap() {
        assertEquals(List.of(line(Level.INFO,
                                  "No Java files found: nothing was examined. This is a COVERAGE GAP, not a pass.")),
                     run(Invocation.lint(Surface.CLI), Verdict.nothingCollected()).before());
    }

    @Test
    void nothingCollected_cliCheckFormatAndFormatCheck_sayNoJavaFiles() {
        Stream.of(Invocation.check(Surface.CLI),
                  format(Surface.CLI, FormatAction.WRITE),
                  Invocation.formatCheck(Surface.CLI))
              .forEach(invocation -> assertEquals(List.of(line(Level.INFO, "No Java files found.")),
                                                  run(invocation, Verdict.nothingCollected()).before()));
    }

    @Test
    void nothingCollected_mavenWithExcludedTests_namesTheModuleAndTheOption() {
        var invocation = Invocation.lint(Surface.MAVEN)
                                   .withModule(MavenModule.mavenModule("app", ROOT, TEST_ROOT, "jbct.includeTests"));
        var lines = RunLines.runLines(invocation, List.of(), List.of(), Verdict.nothingCollected(), List.of(), 3)
                            .lines();

        assertEquals(List.of(line(Level.WARN,
                                  "JBCT lint examined NOTHING in app: no Java files under src/main/java, and 3 test file(s) "
                                  + "under src/test/java were excluded because jbct.includeTests=false. This module is NOT "
                                  + "covered by this goal — a green result here is not evidence about those files. "
                                  + "Pass -Djbct.includeTests=true to examine them.")),
                     lines.before());
    }

    @Test
    void nothingCollected_mavenWithoutExcludedTests_saysNoJavaFiles() {
        assertEquals(List.of(line(Level.INFO, "No Java files found.")),
                     run(Invocation.process(Surface.MAVEN, false), Verdict.nothingCollected()).before());
    }

    // ---------------------------------------------------------------- the root line (T1, T19)

    @Test
    void rootLine_clausesPartitionTheFiles_andNameNotReadApartFromNotParsed() {
        var invocation = Invocation.lint(Surface.CLI);
        var files = List.of(file(A, linted(), none()),
                            file(B, parseFailed(), none()),
                            file(C, readFailed(), none()));
        var root = RootResult.rootResult(ROOT,
                                         JbctMode.BUSINESS,
                                         ModeSource.FromDefault.fromDefault(),
                                         Option.some("com.example"),
                                         false,
                                         "production root",
                                         3,
                                         Option.some(Tally.tally(1, 0, 0, 0, 0, 1, 1, 0)),
                                         Option.none(),
                                         0,
                                         0,
                                         0,
                                         false);
        var lines = RunLines.runLines(invocation, files, List.of(root), CLEAN, List.of(), 0).lines();

        assertEquals(line(Level.INFO,
                          "mode=business (default) root=src/main/java basePackage=com.example: 3 files; "
                          + "lint: 1 linted, 1 not parsed, 1 not read; test-root rule: production root"),
                     rootLineOf(lines));
    }

    @Test
    void rootLine_formatClauseReadsTheFormatTally() {
        var invocation = Invocation.formatCheck(Surface.MAVEN);
        var files = List.of(file(A, none(), wouldChange()), file(B, none(), unchanged()));
        var lines = run(invocation, FINDINGS, files);

        assertEquals(line(Level.INFO,
                          "mode=business (default) root=src/main/java: 2 files; format: 1 unchanged, 1 would change; "
                          + "test-root rule: production root"),
                     rootLineOf(lines));
    }

    // ---------------------------------------------------------------- counts of the header (T5, T6)

    @Test
    void header_mavenProcessCountsFilesOnWhichAnAskedActivityWasAttempted_notFilesHandedOver() {
        var invocation = Invocation.process(Surface.MAVEN, false);
        var files = List.of(file(A, linted(), unchanged()),
                            file(B, skipped(), rewritten()),
                            file(C, skipped(), skipped()));

        assertEquals(line(Level.INFO, "Processing 2 Java file(s)"), bodyBefore(run(invocation, CLEAN, files)).get(0));
    }

    @Test
    void header_mavenCheckCountsAttemptedFiles() {
        var invocation = Invocation.check(Surface.MAVEN);
        var files = List.of(file(A, linted(), unchanged()), file(B, skipped(), skipped()), file(C, skipped(), skipped()));

        assertEquals(line(Level.INFO, "Running JBCT check on 1 Java file(s)"), bodyBefore(run(invocation, CLEAN, files)).get(0));
    }

    @Test
    void header_mavenLintFormatAndFormatCheckCountOnlyFilesWhoseModeRunsTheActivity() {
        var lint = run(Invocation.lint(Surface.MAVEN), CLEAN, List.of(file(A, linted(), none()), file(B, skipped(), none())));
        var format = run(format(Surface.MAVEN, FormatAction.WRITE),
                         CLEAN,
                         List.of(file(A, none(), rewritten()), file(B, none(), skipped()), file(C, none(), unchanged())));
        var check = run(Invocation.formatCheck(Surface.MAVEN),
                        CLEAN,
                        List.of(file(A, none(), unchanged()), file(B, none(), skipped())));

        assertEquals(line(Level.INFO, "Linting 1 Java file(s)"), bodyBefore(lint).get(0));
        assertEquals(line(Level.INFO, "Formatting 2 Java file(s)"), bodyBefore(format).get(0));
        assertEquals(line(Level.INFO, "Checking format of 1 Java file(s)"), bodyBefore(check).get(0));
    }

    @Test
    void header_isOmittedWhenNoFileHadTheActivityAttempted() {
        var files = List.of(file(A, skipped(), skipped()));

        Stream.of(Invocation.lint(Surface.MAVEN),
                  Invocation.check(Surface.MAVEN),
                  Invocation.process(Surface.MAVEN, false),
                  Invocation.formatCheck(Surface.MAVEN),
                  format(Surface.MAVEN, FormatAction.WRITE))
              .forEach(invocation -> assertEquals(List.of(),
                                                  bodyBefore(run(invocation, CLEAN, files)),
                                                  invocation.command() + " printed a count over zero attempted files"));
    }

    @Test
    void header_cliPrintsTheDetailLineWithTheHandedOverCount() {
        var files = List.of(file(A, linted(), none()), file(B, linted(), none()));

        assertEquals(line(Level.DETAIL, "Found 2 Java file(s) to lint."),
                     bodyBefore(run(Invocation.lint(Surface.CLI), CLEAN, files)).get(0));
        assertEquals(line(Level.DETAIL, "Checking 2 Java file(s)..."),
                     bodyBefore(run(Invocation.check(Surface.CLI),
                                    CLEAN,
                                    List.of(file(A, linted(), unchanged()), file(B, linted(), unchanged())))).get(0));
        assertEquals(line(Level.DETAIL, "Found 2 Java file(s) to process."),
                     bodyBefore(run(format(Surface.CLI, FormatAction.WRITE),
                                    CLEAN,
                                    List.of(file(A, none(), unchanged()), file(B, none(), unchanged())))).get(0));
    }

    // ---------------------------------------------------------------- summaries and the order per command (T7 .. T13, C7)

    @Test
    void cliLint_findings_printsCheckedLineWithSeverities() {
        var lines = run(Invocation.lint(Surface.CLI), FINDINGS, 1, 0, 0, List.of(file(A, linted(), none()), file(B, linted(), none())));

        assertEquals(List.of(line(Level.INFO, "Checked 2 file(s): 1 error(s), 0 warning(s), 0 info(s)")), bodyAfter(lines));
    }

    @Test
    void score_cli_parseFailureLineIsTheReleasesCrossLine_notTheFormatCommandsErrorSentence() {
        var invocation = Invocation.score(Surface.CLI, Option.none());
        var lines = run(invocation, Verdict.incomplete("gap"), List.of(file(A, linted(), none()), file(B, parseFailed(), none())));

        assertTrue(bodyBefore(lines).contains(line(Level.ERROR, "  ✗ " + B + ": boom")), bodyBefore(lines).toString());
    }

    @Test
    void cliLint_partial_printsGapThenTheFailedCheckedLine_andOneErrorLinePerUnparsedFile() {
        var lines = run(Invocation.lint(Surface.CLI),
                        Verdict.incomplete("gap"),
                        List.of(file(A, linted(), none()), file(B, parseFailed(), none())));

        assertEquals(List.of(line(Level.DETAIL, "Found 2 Java file(s) to lint."),
                             line(Level.ERROR, "  ✗ " + B + ": boom")),
                     bodyBefore(lines));
        assertEquals(List.of(lineOut(Level.ERROR, GAP_LINT),
                             line(Level.INFO, "✗ Checked 1 of 2 file(s), 1 UNPARSEABLE: 0 error(s), 0 warning(s), 0 info(s)")),
                     bodyAfter(lines));
    }

    @Test
    void mavenLint_printsLintResultsWithoutAPassSentence() {
        var lines = run(Invocation.lint(Surface.MAVEN), CLEAN, 1, 2, 3, List.of(file(A, linted(), none()), file(B, linted(), none())));

        assertEquals(List.of(line(Level.INFO, "Lint results (checked 2 file(s)): 1 error(s), 2 warning(s), 3 info(s)")),
                     bodyAfter(lines));
    }

    @Test
    void cliCheck_withAnUnformattedFile_listsItBeforeTheFindingsThenSummarises() {
        var lines = run(Invocation.check(Surface.CLI),
                        FINDINGS,
                        List.of(file(A, linted(), wouldChange()), file(B, linted(), unchanged())));

        assertEquals(List.of(line(Level.DETAIL, "Checking 2 Java file(s)..."),
                             line(Level.DETAIL, "  ✓ lint: A.java"),
                             line(Level.DETAIL, "  ✓ format: B.java"),
                             line(Level.DETAIL, "  ✓ lint: B.java"),
                             lineOut(Level.ERROR, "Files not properly formatted:"),
                             lineOut(Level.ERROR, "  " + A)),
                     bodyBefore(lines));
        assertEquals(List.of(line(Level.INFO, "Check results (checked 2 file(s)): 1 format issue(s), 0 lint error(s), 0 warning(s)")),
                     bodyAfter(lines));
    }

    @Test
    void mavenCheck_withAnUnformattedFile_listsItBeforeTheFindingsThenSummarises() {
        var lines = run(Invocation.check(Surface.MAVEN),
                        FINDINGS,
                        0,
                        1,
                        0,
                        List.of(file(A, linted(), wouldChange()), file(B, linted(), unchanged())));

        assertEquals(List.of(line(Level.INFO, "Running JBCT check on 2 Java file(s)"),
                             line(Level.ERROR, "Files not properly formatted:"),
                             line(Level.ERROR, "  " + A)),
                     bodyBefore(lines));
        assertEquals(List.of(line(Level.INFO, "Check results (checked 2 file(s)): 1 format issue(s), 0 lint error(s), 1 warning(s)")),
                     bodyAfter(lines));
    }

    @Test
    void mavenProcess_formatSummaryComesBeforeLintSummary() {
        var lines = run(Invocation.process(Surface.MAVEN, false),
                        CLEAN,
                        0,
                        1,
                        0,
                        List.of(file(A, linted(), rewritten()), file(B, linted(), unchanged())));

        assertEquals(List.of(line(Level.INFO, "Processing 2 Java file(s)"),
                             line(Level.WARN, "Formatted: " + A)),
                     bodyBefore(lines));
        assertEquals(List.of(line(Level.INFO, "Format (checked 2 file(s)): 1 formatted, 1 unchanged, 0 errors"),
                             line(Level.INFO, "Lint (checked 2 file(s)): 0 error(s), 1 warning(s), 0 info(s)")),
                     bodyAfter(lines));
    }

    @Test
    void mavenProcess_writeFailedWithLintErrors_printsBothSummariesAndTheFormatFailure() {
        var invocation = Invocation.process(Surface.MAVEN, false);
        var files = List.of(file(A, linted(), writeFailed()), file(B, linted(), unchanged()));
        var lines = run(invocation, FINDINGS, 2, 0, 0, true, files);

        assertEquals(List.of(line(Level.INFO, "Processing 2 Java file(s)"),
                             line(Level.ERROR, "Error writing " + A + ": disk")),
                     bodyBefore(lines));
        assertEquals(List.of(line(Level.INFO, "Format (checked 2 file(s)): 0 formatted, 1 unchanged, 1 errors"),
                             line(Level.INFO, "Lint (checked 2 file(s)): 2 error(s), 0 warning(s), 0 info(s)")),
                     bodyAfter(lines));
        assertEquals("Formatting failed for 1 file(s)", failure(invocation, files, 2, true));
    }

    @Test
    void mavenProcess_withheldFiles_areCountedBeforeTheSummaries() {
        var lines = run(Invocation.process(Surface.MAVEN, true),
                        FINDINGS,
                        1,
                        0,
                        0,
                        List.of(file(A, linted(), withheld()), file(B, linted(), unchanged())));

        assertEquals(List.of(line(Level.WARN, "1 file(s) not formatted: lint errors (failBeforeFormat)"),
                             line(Level.INFO, "Format (checked 2 file(s)): 0 formatted, 1 unchanged, 0 errors"),
                             line(Level.INFO, "Lint (checked 2 file(s)): 1 error(s), 0 warning(s), 0 info(s)")),
                     bodyAfter(lines));
    }

    @Test
    void format_write_printsTallyLine_cliAndMaven() {
        var files = List.of(file(A, none(), rewritten()), file(B, none(), unchanged()));
        var cli = run(format(Surface.CLI, FormatAction.WRITE), CLEAN, files);
        var maven = run(format(Surface.MAVEN, FormatAction.WRITE), CLEAN, files);

        assertEquals(List.of(line(Level.DETAIL, "Found 2 Java file(s) to process."),
                             line(Level.DETAIL, "  formatted: " + A),
                             line(Level.DETAIL, "  unchanged: " + B)),
                     bodyBefore(cli));
        assertEquals(List.of(line(Level.INFO, "Formatted: 1, Unchanged: 1, Errors: 0")), bodyAfter(cli));
        assertEquals(List.of(line(Level.INFO, "Formatting 2 Java file(s)"), line(Level.WARN, "Formatted: " + A)),
                     bodyBefore(maven));
        assertEquals(List.of(line(Level.INFO, "Formatted: 1, Unchanged: 1, Errors: 0")), bodyAfter(maven));
    }

    @Test
    void format_write_errorsAreNotReadPlusNotParsedPlusWriteFailed() {
        var lines = run(format(Surface.CLI, FormatAction.WRITE),
                        Verdict.incomplete("gap"),
                        List.of(file(A, none(), rewritten()),
                                file(B, none(), parseFailed()),
                                file(C, none(), writeFailed())));

        assertEquals(List.of(lineOut(Level.ERROR, GAP_FORMAT),
                             line(Level.INFO, "Formatted: 1, Unchanged: 0, Errors: 2")),
                     bodyAfter(lines));
    }

    @Test
    void format_dryRunAndCheck_cli_printTheirOwnWording() {
        var files = List.of(file(A, none(), wouldChange()), file(B, none(), unchanged()));
        var dry = run(format(Surface.CLI, FormatAction.DRY_RUN), CLEAN, files);
        var check = run(format(Surface.CLI, FormatAction.CHECK), FINDINGS, files);

        assertEquals(List.of(line(Level.DETAIL, "Found 2 Java file(s) to process."),
                             line(Level.INFO, "  would format: " + A),
                             line(Level.DETAIL, "  unchanged: " + B)),
                     bodyBefore(dry));
        assertEquals(List.of(line(Level.INFO, "Dry run: 1 file(s) would be formatted.")), bodyAfter(dry));
        assertEquals(List.of(line(Level.DETAIL, "Found 2 Java file(s) to process."),
                             line(Level.INFO, "  needs formatting: " + A),
                             line(Level.DETAIL, "  unchanged: " + B)),
                     bodyBefore(check));
        assertEquals(List.of(line(Level.INFO, "1 file(s) need formatting.")), bodyAfter(check));
    }

    @Test
    void format_check_cli_withAnUnparsedFile_printsGapThenTheFailedCheckedLine() {
        var lines = run(format(Surface.CLI, FormatAction.CHECK),
                        Verdict.incomplete("gap"),
                        List.of(file(A, none(), wouldChange()), file(B, none(), parseFailed())));

        assertEquals(List.of(lineOut(Level.ERROR, GAP_FORMAT),
                             line(Level.INFO, "✗ Checked 1 of 2 file(s), 1 UNPARSEABLE: 1 file(s) need formatting")),
                     bodyAfter(lines));
    }

    @Test
    void formatCheck_maven_withWouldChangeAndReadFailed_namesBothAndTheGap() {
        var invocation = Invocation.formatCheck(Surface.MAVEN);
        var files = List.of(file(A, none(), wouldChange()), file(B, none(), readFailed()));
        var lines = run(invocation, Verdict.incomplete("gap"), files);

        assertEquals(List.of(line(Level.INFO, "Checking format of 2 Java file(s)"),
                             line(Level.ERROR, "Error reading " + B + ": denied"),
                             line(Level.ERROR, "  " + A)),
                     bodyBefore(lines));
        assertEquals(List.of(line(Level.ERROR, GAP_FORMAT_CHECK)), bodyAfter(lines));
        assertEquals("1 file(s) are not properly formatted:\n  " + A + "\nRun 'mvn jbct:format' to fix.",
                     failure(invocation, files, 0, false));
    }

    @Test
    void formatCheck_maven_logsEachUnformattedFileAsOneErrorLineAfterTheLoop() {
        var files = List.of(file(A, none(), wouldChange()), file(B, none(), readFailed()), file(C, none(), wouldChange()));
        var lines = run(Invocation.formatCheck(Surface.MAVEN), FINDINGS, files);

        assertEquals(List.of(line(Level.INFO, "Checking format of 3 Java file(s)"),
                             line(Level.ERROR, "Error reading " + B + ": denied"),
                             line(Level.ERROR, "  " + A),
                             line(Level.ERROR, "  " + C)),
                     bodyBefore(lines));
    }

    // ---------------------------------------------------------------- the pass rule, both ways, per (command, surface)

    @Test
    void passRule_cliLint() {
        assertPassRule(Invocation.lint(Surface.CLI), "✓ All 2 file(s) passed JBCT compliance check.");
    }

    @Test
    void passRule_cliCheck() {
        assertPassRule(Invocation.check(Surface.CLI), "✓ All checks passed.");
    }

    @Test
    void passRule_mavenCheck() {
        assertPassRule(Invocation.check(Surface.MAVEN), "JBCT check passed.");
    }

    @Test
    void passRule_cliFormatCheck() {
        assertPassRule(format(Surface.CLI, FormatAction.CHECK), "All files are properly formatted.");
    }

    @Test
    void passRule_mavenFormatCheck() {
        assertPassRule(Invocation.formatCheck(Surface.MAVEN), "All files are properly formatted.");
    }

    @Test
    void passRule_commandsWithoutAPassSentence_neverPrintOne() {
        Stream.of(Invocation.lint(Surface.MAVEN),
                  Invocation.process(Surface.MAVEN, false),
                  format(Surface.MAVEN, FormatAction.WRITE),
                  format(Surface.CLI, FormatAction.WRITE),
                  format(Surface.CLI, FormatAction.DRY_RUN))
              .forEach(invocation -> assertFalse(texts(run(invocation, CLEAN, okFiles(invocation))).stream()
                                                                                                    .anyMatch(RunLinesTest::isPassSentence),
                                                 invocation.command() + "/" + invocation.surface() + " printed a pass sentence"));
    }

    private static void assertPassRule(Invocation invocation, String pass) {
        var done = okFiles(invocation);

        assertTrue(texts(run(invocation, CLEAN, done)).contains(pass), "all done, CLEAN: the pass line is printed");
        assertFalse(texts(run(invocation, FINDINGS, done)).contains(pass), "verdict FINDINGS: no pass line");
        assertFalse(texts(run(invocation, Verdict.incomplete("gap"), done)).contains(pass), "verdict INCOMPLETE: no pass line");
        assertFalse(texts(run(invocation, CLEAN, List.of())).contains(pass), "no file handed over: no pass line");
        assertFalse(texts(run(invocation, CLEAN, List.of(done.get(0), skippedFile(invocation, C)))).stream()
                                                                                                  .anyMatch(RunLinesTest::isPassSentence),
                    "one file skipped by mode: no pass line");
        assertFalse(texts(run(invocation, CLEAN, List.of(skippedFile(invocation, A), skippedFile(invocation, B)))).stream()
                                                                                                                  .anyMatch(RunLinesTest::isPassSentence),
                    "every file skipped by mode (nothing done): no pass line");
        assertFalse(texts(run(invocation, CLEAN, List.of(done.get(0), unparsedFile(invocation, C)))).stream()
                                                                                                  .anyMatch(RunLinesTest::isPassSentence),
                    "one file not parsed under a CLEAN verdict: no pass line");
    }

    private static boolean isPassSentence(String text) {
        return text.startsWith("✓ All ") || text.equals("JBCT check passed.") || text.equals("All files are properly formatted.");
    }

    // ---------------------------------------------------------------- skipped and nothing done (T14, T15)

    @Test
    void skippedByMode_someDone_namesTheSkippedRootsBeforeTheSummary() {
        var invocation = Invocation.lint(Surface.MAVEN);
        var files = List.of(file(ROOT, A, linted(), none()), file(TEST_ROOT, T, skipped(), none()));
        var lines = RunLines.runLines(invocation,
                                      files,
                                      List.of(rootOf(invocation, ROOT, JbctMode.BUSINESS, files, 0, 0, 0, false),
                                              rootOf(invocation, TEST_ROOT, JbctMode.DISABLED, files, 0, 0, 0, false)),
                                      CLEAN,
                                      List.of(),
                                      0)
                            .lines();

        assertEquals(List.of(line(Level.WARN, "1 file(s) skipped by mode: src/test/java (mode disabled)"),
                             line(Level.INFO, "Lint results (checked 1 file(s)): 0 error(s), 0 warning(s), 0 info(s)")),
                     bodyAfter(lines));
    }

    @Test
    void nothingDone_lint_saysNothingWasLinted_withTheReasonPerRoot() {
        var invocation = Invocation.lint(Surface.MAVEN);
        var files = List.of(file(TEST_ROOT, T, skipped(), none()));
        var lines = RunLines.runLines(invocation,
                                      files,
                                      List.of(rootOf(invocation, TEST_ROOT, JbctMode.DISABLED, files, 0, 0, 0, false),
                                              rootOf(invocation, ROOT, JbctMode.BUSINESS, files, 0, 0, 0, false)),
                                      CLEAN,
                                      List.of(),
                                      0)
                            .lines();

        assertEquals(List.of(line(Level.WARN, "Nothing was linted: src/test/java (mode disabled skips it); src/main/java (no Java files)")),
                     bodyAfter(lines));
    }

    @Test
    void nothingDone_formatAndFormatCheck_useTheirOwnVerb() {
        var files = List.of(file(A, none(), skipped()));

        assertEquals(List.of(line(Level.WARN, "Nothing was formatted: src/main/java (mode business skips it)")),
                     bodyAfter(run(format(Surface.CLI, FormatAction.WRITE), CLEAN, files)));
        assertEquals(List.of(line(Level.WARN, "Nothing was format-checked: src/main/java (mode business skips it)")),
                     bodyAfter(run(Invocation.formatCheck(Surface.MAVEN), CLEAN, files)));
    }

    // ---------------------------------------------------------------- score lines (T6, T15, T16, T17)

    @Test
    void score_maven_headerCountsTheFilesWhoseModeRunsRules_andOmitsItAtZero() {
        var invocation = Invocation.score(Surface.MAVEN, Option.none());
        var files = List.of(file(ROOT, A, linted(), none()), file(TEST_ROOT, T, skipped(), none()));

        assertEquals(List.of(line(Level.INFO, "Measuring 1 Java file(s)")),
                     bodyBefore(scoreRun(invocation, files)));
        assertEquals(List.of(),
                     bodyBefore(scoreRun(invocation, List.of(file(TEST_ROOT, T, skipped(), none())))));
    }

    @Test
    void score_rootLine_namesTheLintClauseScored() {
        var invocation = Invocation.score(Surface.CLI, Option.none());
        var lines = scoreRun(invocation, List.of(file(ROOT, A, linted(), none())));

        assertEquals(line(Level.INFO,
                          "mode=business (default) root=src/main/java: 1 files; lint: 1 scored; test-root rule: production root"),
                     rootLineOf(lines));
    }

    @Test
    void score_aRootThatScoredNothingWhileAnotherScored_isNamedWithItsReason() {
        var invocation = Invocation.score(Surface.CLI, Option.none());
        var files = List.of(file(ROOT, A, linted(), none()), file(TEST_ROOT, T, skipped(), none()));
        var lines = RunLines.runLines(invocation,
                                      files,
                                      List.of(rootOf(invocation, ROOT, JbctMode.BUSINESS, files, 0, 0, 0, false),
                                              rootOf(invocation, TEST_ROOT, JbctMode.DISABLED, files, 0, 0, 0, false)),
                                      CLEAN,
                                      List.of(),
                                      0)
                            .lines();

        assertEquals(List.of(line(Level.WARN, "Not scored: src/test/java (mode disabled skips it)")), bodyAfter(lines));
    }

    @Test
    void score_nothingScored_saysNothingWasScoredWithTheReasonPerRoot_andNeverNotScored() {
        var invocation = Invocation.score(Surface.CLI, Option.none());
        var files = List.of(file(TEST_ROOT, T, skipped(), none()));
        var lines = RunLines.runLines(invocation,
                                      files,
                                      List.of(rootOf(invocation, TEST_ROOT, JbctMode.DISABLED, files, 0, 0, 0, false)),
                                      CLEAN,
                                      List.of(),
                                      0)
                            .lines();

        assertEquals(List.of(line(Level.WARN, "Nothing was scored: src/test/java (mode disabled skips it)")),
                     bodyAfter(lines));
    }

    @Test
    void score_nothingScoredWithALimit_reportsItNotEvaluated_andNoLineSaysPasses() {
        var limit = Option.some(DensityLimit.densityLimit(5.0, "--max-density"));
        var invocation = Invocation.score(Surface.CLI, limit);
        var lines = scoreRun(invocation, List.of(skippedFile(invocation, A)));
        var all = Stream.concat(lines.before().stream(), bodyAfter(lines).stream()).map(Line::text).toList();

        assertTrue(all.contains("--max-density 5.0 was not evaluated: nothing was scored"), all.toString());
        assertTrue(all.stream().noneMatch(text -> text.contains("passes")), all.toString());
    }

    @Test
    void score_mavenLimitFlag_isTheGoalsProperty() {
        var limit = Option.some(DensityLimit.densityLimit(2.5, "jbct.score.maxDensity"));
        var invocation = Invocation.score(Surface.MAVEN, limit);
        var lines = scoreRun(invocation, List.of(skippedFile(invocation, A)));

        assertTrue(bodyAfter(lines).contains(line(Level.INFO, "jbct.score.maxDensity 2.5 was not evaluated: nothing was scored")));
    }

    @Test
    void score_aScoredRunWithALimit_printsNoNotEvaluatedLine() {
        var limit = Option.some(DensityLimit.densityLimit(5.0, "--max-density"));
        var invocation = Invocation.score(Surface.CLI, limit);
        var score = Option.some(ScoreCalculator.calculate(new SourceScan(List.of(), 100, 1)));
        var files = List.of(file(ROOT, A, linted(), none()));
        var lines = RunLines.runLines(invocation,
                                      files,
                                      List.of(rootOf(invocation, ROOT, JbctMode.BUSINESS, files, 0, 0, 0, false)),
                                      CLEAN,
                                      List.of(),
                                      0,
                                      score)
                            .lines();

        assertEquals(List.of(), bodyAfter(lines));
    }

    @Test
    void score_failureText_gapOutranksTheBreach_andABreachIsTheGatesMessage() {
        var limit = Option.some(DensityLimit.densityLimit(0.5, "--max-density"));
        var invocation = Invocation.score(Surface.CLI, limit);
        var score = Option.some(ScoreCalculator.calculate(new SourceScan(List.of(diagnostic()), 1000, 1)));
        var scored = List.of(file(ROOT, A, linted(), none()));
        var gap = List.of(file(ROOT, A, linted(), none()), file(ROOT, B, parseFailed(), none()));

        assertEquals(DensityGate.breachMessage(score.unwrap().totalDensityPerKloc(), 0.5),
                     RunLines.failureOf(invocation,
                                        scored,
                                        List.of(rootOf(invocation, ROOT, JbctMode.BUSINESS, scored, 0, 0, 0, false)),
                                        score));
        assertEquals("JBCT score failed: 1 file(s) could not be read or parsed and were NOT analysed,"
                     + " so the reported density does not cover them",
                     RunLines.failureOf(invocation,
                                        gap,
                                        List.of(rootOf(invocation, ROOT, JbctMode.BUSINESS, gap, 0, 0, 0, false)),
                                        score));
    }

    @Test
    void score_aBreach_isPrintedToStderrAfterABlankLine_onTheCliOnly() {
        var limit = Option.some(DensityLimit.densityLimit(0.5, "--max-density"));
        var score = Option.some(ScoreCalculator.calculate(new SourceScan(List.of(diagnostic()), 1000, 1)));
        var files = List.of(file(ROOT, A, linted(), none()));
        var cli = Invocation.score(Surface.CLI, limit);
        var maven = Invocation.score(Surface.MAVEN, limit);
        var breach = "\n" + DensityGate.breachMessage(score.unwrap().totalDensityPerKloc(), 0.5);

        assertEquals(List.of(RunOutcome.Line.line(Level.ERROR, breach, RunOutcome.Channel.STDERR)),
                     bodyAfter(RunLines.runLines(cli, files, List.of(rootOf(cli, ROOT, JbctMode.BUSINESS, files, 0, 0, 0, false)), CLEAN, List.of(), 0, score).lines()));
        assertEquals(tableLines(score.unwrap()),
                     bodyAfter(RunLines.runLines(maven, files, List.of(rootOf(maven, ROOT, JbctMode.BUSINESS, files, 0, 0, 0, false)), CLEAN, List.of(), 0, score).lines()));
    }

    @Test
    void score_maven_theTableLeadsTheAfterGroupSoTheCoverageGapIsTheGoalsLastErrorLine() {
        var score = Option.some(ScoreCalculator.calculate(new SourceScan(List.of(diagnostic()), 1000, 1)));
        var maven = Invocation.score(Surface.MAVEN, Option.none());
        var cli = Invocation.score(Surface.CLI, Option.none());
        var files = List.of(file(ROOT, A, linted(), none()), file(ROOT, B, parseFailed(), none()));
        var mavenAfter = bodyAfter(scoreLines(maven, files, score));
        var table = tableLines(score.unwrap());

        assertEquals(table, mavenAfter.subList(0, table.size()));
        assertEquals(Level.ERROR, mavenAfter.getLast().level());
        assertTrue(mavenAfter.getLast().text().startsWith("Parse errors:"), mavenAfter.toString());
        assertFalse(bodyAfter(scoreLines(cli, files, score)).stream().anyMatch(table::contains));
    }

    private static List<Line> tableLines(ScoreResult score) {
        return ScoreReport.terminalLines(score).stream().map(text -> line(Level.INFO, text)).toList();
    }

    private static RunLines.Lines scoreLines(Invocation invocation, List<FileResult> files, Option<ScoreResult> score) {
        return RunLines.runLines(invocation, files, List.of(rootOf(invocation, ROOT, JbctMode.BUSINESS, files, 0, 0, 0, false)), Verdict.incomplete("gap"), List.of(), 0, score).lines();
    }

    // ---------------------------------------------------------------- per-file lines (T20)

    @Test
    void perFile_failureLines_perCommandAndSurface() {
        assertEquals(line(Level.ERROR, "  ✗ " + A + ": boom"), firstFileLine(Invocation.lint(Surface.CLI), fileOf(parseFailed(), none())));
        assertEquals(line(Level.ERROR, "  ✗ " + A + ": denied"), firstFileLine(Invocation.lint(Surface.CLI), fileOf(readFailed(), none())));
        assertEquals(line(Level.ERROR, "  ✗ parse error: " + A + ": boom"),
                     firstFileLine(Invocation.check(Surface.CLI), fileOf(parseFailed(), none())));
        assertEquals(line(Level.ERROR, "Parse error in " + A + ": boom"),
                     firstFileLine(Invocation.lint(Surface.MAVEN), fileOf(parseFailed(), none())));
        assertEquals(line(Level.ERROR, "Error reading " + A + ": denied"),
                     firstFileLine(Invocation.lint(Surface.MAVEN), fileOf(readFailed(), none())));
        assertEquals(line(Level.ERROR, "Error checking " + A + ": Parse error at A.java:1:1 - boom"),
                     firstFileLine(Invocation.formatCheck(Surface.MAVEN), fileOf(none(), parseFailed())));
        assertEquals(line(Level.ERROR, "Parse error in " + A + ": boom"),
                     firstFileLine(Invocation.process(Surface.MAVEN, false), fileOf(linted(), parseFailed())));
        assertEquals(line(Level.ERROR, "Error writing " + A + ": disk"),
                     firstFileLine(Invocation.process(Surface.MAVEN, false), fileOf(linted(), writeFailed())));
        assertEquals(line(Level.ERROR, "Error formatting " + A + ": Parse error at A.java:1:1 - boom"),
                     firstFileLine(format(Surface.MAVEN, FormatAction.WRITE), fileOf(none(), parseFailed())));
        assertEquals(line(Level.ERROR, "Error formatting " + A + ": disk"),
                     firstFileLine(format(Surface.MAVEN, FormatAction.WRITE), fileOf(none(), writeFailed())));
        assertEquals(line(Level.ERROR, "Error reading " + A + ": denied"),
                     firstFileLine(format(Surface.MAVEN, FormatAction.WRITE), fileOf(none(), readFailed())));
        assertEquals(line(Level.ERROR, "  error: " + A + " - Parse error at A.java:1:1 - boom"),
                     firstFileLine(format(Surface.CLI, FormatAction.WRITE), fileOf(none(), parseFailed())));
        assertEquals(line(Level.ERROR, "  error: " + A + " - disk"),
                     firstFileLine(format(Surface.CLI, FormatAction.WRITE), fileOf(none(), writeFailed())));
    }

    @Test
    void parseFailure_mavenFormatAndFormatCheck_keepTheReleasesParsePosition() {
        assertEquals(line(Level.ERROR, "Error formatting " + A + ": Parse error at A.java:1:1 - boom"),
                     firstFileLine(format(Surface.MAVEN, FormatAction.WRITE), fileOf(none(), parseFailed())));
        assertEquals(line(Level.ERROR, "Error checking " + A + ": Parse error at A.java:1:1 - boom"),
                     firstFileLine(Invocation.formatCheck(Surface.MAVEN), fileOf(none(), parseFailed())));
    }

    // ---------------------------------------------------------------- failure texts (T22, T23)

    @Test
    void failure_lint_namesEachFailingRootWithItsCounts() {
        var invocation = Invocation.lint(Surface.MAVEN);
        var files = List.of(file(ROOT, A, linted(), none()), file(TEST_ROOT, T, linted(), none()));
        var roots = List.of(rootOf(invocation, ROOT, JbctMode.BUSINESS, files, 2, 1, 0, true),
                            rootOf(invocation, TEST_ROOT, JbctMode.BUSINESS, files, 0, 3, 0, true));

        assertEquals("JBCT lint failed: JBCT failed in 2 source root(s): src/main/java: 2 error(s), 1 warning(s); "
                     + "src/test/java: 0 error(s), 3 warning(s)",
                     RunLines.failureOf(invocation, files, roots));
    }

    @Test
    void failure_lint_gapOnly_namesTheUnparsedFiles() {
        var invocation = Invocation.lint(Surface.MAVEN);

        assertEquals("JBCT lint failed: 1 file(s) could not be read or parsed and were NOT analysed",
                     failure(invocation, List.of(file(A, linted(), none()), file(B, parseFailed(), none())), 0, false));
    }

    @Test
    void failure_check_listsTheUnformattedFilesThenTheFailingRoots() {
        var invocation = Invocation.check(Surface.MAVEN);

        assertEquals("JBCT check failed: 1 file(s) need formatting:\n  " + A
                     + ", JBCT failed in 1 source root(s): src/main/java: 1 error(s), 0 warning(s)",
                     failure(invocation, List.of(file(A, linted(), wouldChange())), 1, true));
    }

    @Test
    void refused_isOneErrorLineNamingTheFileAndTheDifference_onEverySurface() {
        assertEquals(line(Level.ERROR, REFUSAL), firstFileLine(format(Surface.MAVEN, FormatAction.WRITE), fileOf(none(), refused())));
        assertEquals(line(Level.ERROR, REFUSAL), firstFileLine(Invocation.formatCheck(Surface.MAVEN), fileOf(none(), refused())));
        assertEquals(line(Level.ERROR, REFUSAL), firstFileLine(Invocation.process(Surface.MAVEN, false), fileOf(linted(), refused())));
        assertEquals(line(Level.ERROR, "  error: " + REFUSAL), firstFileLine(format(Surface.CLI, FormatAction.WRITE), fileOf(none(), refused())));
        assertEquals(line(Level.ERROR, "  error: " + REFUSAL), firstFileLine(Invocation.check(Surface.CLI), fileOf(linted(), refused())));
    }

    @Test
    void refused_isCountedUnderErrors_andFailsEveryCommandThatFormats() {
        var files = List.of(file(A, none(), refused()), file(B, none(), unchanged()));
        var lines = run(format(Surface.CLI, FormatAction.WRITE), Verdict.incomplete("refused"), files);

        assertEquals(List.of(line(Level.INFO, "Formatted: 0, Unchanged: 1, Errors: 1")), bodyAfter(lines));
        assertEquals("Formatting failed for 1 file(s)", failure(format(Surface.CLI, FormatAction.WRITE), files, 0, false));
        assertEquals("Formatting failed for 1 file(s)", failure(Invocation.process(Surface.MAVEN, false), files, 0, false));
        assertEquals("Format check failed for 1 file(s)", failure(Invocation.formatCheck(Surface.CLI), files, 0, false));
        assertEquals("JBCT check failed: 1 file(s) refused by the formatter's content guard",
                     failure(Invocation.check(Surface.CLI), files, 0, false));
    }

    @Test
    void failure_process_namesTheFailingRoot() {
        assertEquals("JBCT process failed: JBCT failed in 1 source root(s): src/main/java: 1 error(s), 0 warning(s)",
                     failure(Invocation.process(Surface.MAVEN, false), List.of(file(A, linted(), unchanged())), 1, true));
    }

    @Test
    void failure_format_countsErroredFilesFirst_thenUnformattedFiles() {
        var write = format(Surface.MAVEN, FormatAction.WRITE);
        var check = format(Surface.CLI, FormatAction.CHECK);

        assertEquals("Formatting failed for 1 file(s)", failure(write, List.of(file(A, none(), writeFailed())), 0, false));
        assertEquals("1 file(s) need formatting", failure(check, List.of(file(A, none(), wouldChange())), 0, false));
    }

    @Test
    void failure_formatCheck_unreadableOnly_namesTheCount() {
        assertEquals("Format check failed for 1 file(s)",
                     failure(Invocation.formatCheck(Surface.MAVEN), List.of(file(A, none(), readFailed())), 0, false));
        assertEquals("1 file(s) are not properly formatted:\n  " + A,
                     failure(Invocation.formatCheck(Surface.CLI), List.of(file(A, none(), wouldChange())), 0, false));
    }

    @Test
    void cliCheck_withAnUnformattedFileAndAFinding_framesTheListTheFindingsAndTheAfterLines() {
        var lines = run(Invocation.check(Surface.CLI),
                        FINDINGS,
                        1,
                        0,
                        0,
                        List.of(file(A, RunOutcome.Linted.linted(List.of(diagnostic())), wouldChange())));

        var head = lines.before().indexOf(lineOut(Level.ERROR, "Files not properly formatted:"));

        assertEquals(Line.line(Level.INFO, "", RunOutcome.Channel.STDOUT), lines.before().get(head - 1));
        assertEquals(Line.line(Level.INFO, "", RunOutcome.Channel.WITH_FINDINGS), lines.before().getLast());
        assertEquals(Line.line(Level.INFO, "", RunOutcome.Channel.STDOUT), lines.after().getFirst());
    }

    @Test
    void cliFormat_write_putsABlankLineBeforeTheSummary_andMavenPutsNone() {
        var files = List.of(file(A, none(), rewritten()));

        assertEquals(Line.line(Level.INFO, "", RunOutcome.Channel.STDOUT), run(format(Surface.CLI, FormatAction.WRITE), CLEAN, files).after().getFirst());
        assertEquals(List.of(), run(format(Surface.MAVEN, FormatAction.WRITE), CLEAN, files).after().stream().filter(l -> l.text().isEmpty()).toList());
    }

    @Test
    void cliCheck_verbose_namesEveryCleanFilePerActivity_formatFirst() {
        var lines = run(Invocation.check(Surface.CLI), CLEAN, List.of(file(A, linted(), unchanged())));

        assertEquals(List.of(line(Level.DETAIL, "Checking 1 Java file(s)..."),
                             line(Level.DETAIL, "  ✓ format: A.java"),
                             line(Level.DETAIL, "  ✓ lint: A.java")),
                     bodyBefore(lines));
    }

    @Test
    void cliScore_gapAndNothingCollected_goToStderr_whereTheOtherCommandsWriteThemToStdout() {
        var gap = run(Invocation.score(Surface.CLI, Option.none()), Verdict.incomplete("gap"), List.of(file(A, linted(), none()), file(B, parseFailed(), none())));
        var lint = run(Invocation.lint(Surface.CLI), Verdict.incomplete("gap"), List.of(file(A, linted(), none()), file(B, parseFailed(), none())));

        assertEquals(RunOutcome.Channel.STDERR, gap.after().stream().filter(l -> l.text().startsWith("Parse errors:")).findFirst().orElseThrow().channel());
        assertEquals(RunOutcome.Channel.STDOUT, lint.after().stream().filter(l -> l.text().startsWith("Parse errors:")).findFirst().orElseThrow().channel());
    }

    // ---------------------------------------------------------------- fixtures

    private static Line line(Level level, String text) {
        return Line.line(level, text);
    }

    /// A line the release's CLI wrote to stdout although its level is ERROR.
    private static Line lineOut(Level level, String text) {
        return Line.line(level, text, RunOutcome.Channel.STDOUT);
    }

    /// The after-lines without the blank line that frames them on the CLI surface.
    private static List<Line> bodyAfter(RunLines.Lines lines) {
        return lines.after().stream().filter(line -> !line.text().isEmpty()).toList();
    }

    private static Invocation format(Surface surface, FormatAction action) {
        return Invocation.format(surface, action)
                         .fold(cause -> fail("invocation refused: " + cause.message()), invocation -> invocation);
    }

    private static RunOutcome.Linted linted() {
        return RunOutcome.Linted.linted(List.of());
    }

    private static RunOutcome.ParseFailed parseFailed() {
        return RunOutcome.ParseFailed.parseFailed("boom");
    }

    private static RunOutcome.ReadFailed readFailed() {
        return RunOutcome.ReadFailed.readFailed("denied");
    }

    private static RunOutcome.SkippedByMode skipped() {
        return RunOutcome.SkippedByMode.skippedByMode(JbctMode.DISABLED);
    }

    private static RunOutcome.NotAsked none() {
        return RunOutcome.NotAsked.notAsked();
    }

    private static FormatOutcome unchanged() {
        return RunOutcome.Unchanged.unchanged();
    }

    private static FormatOutcome rewritten() {
        return RunOutcome.Rewritten.rewritten();
    }

    private static FormatOutcome wouldChange() {
        return RunOutcome.WouldChange.wouldChange();
    }

    private static FormatOutcome refused() {
        return RunOutcome.Refused.refused(REFUSAL);
    }

    private static FormatOutcome writeFailed() {
        return RunOutcome.WriteFailed.writeFailed("disk");
    }

    private static FormatOutcome withheld() {
        return RunOutcome.Withheld.withheld();
    }

    private static FileResult file(String name, LintOutcome lint, FormatOutcome format) {
        return file(ROOT, name, lint, format);
    }

    private static FileResult file(Path root, String name, LintOutcome lint, FormatOutcome format) {
        return FileResult.fileResult(Path.of(name), root, lint, format, 10, "p");
    }

    /// A file whose outcomes are `whenLint` / `whenFormat` for the activities the invocation asks, `NotAsked` otherwise.
    private static FileResult shaped(Invocation invocation, String name, LintOutcome whenLint, FormatOutcome whenFormat) {
        return file(name,
                    invocation.activities().lint() ? whenLint : none(),
                    invocation.activities().format() != FormatAction.NONE ? whenFormat : none());
    }

    private static List<FileResult> okFiles(Invocation invocation) {
        return List.of(shaped(invocation, A, linted(), unchanged()), shaped(invocation, B, linted(), unchanged()));
    }

    private static FileResult skippedFile(Invocation invocation, String name) {
        return shaped(invocation, name, skipped(), skipped());
    }

    private static FileResult unparsedFile(Invocation invocation, String name) {
        return shaped(invocation, name, parseFailed(), parseFailed());
    }

    private static FileResult fileOf(LintOutcome lint, FormatOutcome format) {
        return file(A, lint, format);
    }

    /// The first ERROR line a single file produces under the invocation.
    private static Line firstFileLine(Invocation invocation, FileResult file) {
        return run(invocation, CLEAN, List.of(file)).before()
                                                    .stream()
                                                    .filter(line -> line.level() == Level.ERROR)
                                                    .findFirst()
                                                    .orElseGet(() -> fail(invocation.command() + " printed no error line"));
    }

    private static long count(List<FileResult> files, Predicate<FileResult> kind) {
        return files.stream().filter(kind).count();
    }

    private static RootResult rootOf(Invocation invocation,
                                     Path path,
                                     JbctMode mode,
                                     List<FileResult> all,
                                     int errors,
                                     int warnings,
                                     int infos,
                                     boolean fails) {
        var files = all.stream().filter(file -> file.root().equals(path)).toList();
        var lint = new Tally((int) count(files, f -> f.lint() instanceof RunOutcome.Linted),
                             0,
                             0,
                             0,
                             (int) count(files, f -> f.lint() instanceof RunOutcome.SkippedByMode),
                             (int) count(files, f -> f.lint() instanceof RunOutcome.ReadFailed),
                             (int) count(files, f -> f.lint() instanceof RunOutcome.ParseFailed),
                             0);
        var format = new Tally((int) count(files, f -> f.format() instanceof RunOutcome.Rewritten),
                               (int) count(files, f -> f.format() instanceof RunOutcome.Unchanged),
                               (int) count(files, f -> f.format() instanceof RunOutcome.WouldChange),
                               (int) count(files, f -> f.format() instanceof RunOutcome.Withheld),
                               (int) count(files, f -> f.format() instanceof RunOutcome.SkippedByMode),
                               (int) count(files, f -> f.format() instanceof RunOutcome.ReadFailed),
                               (int) count(files, f -> f.format() instanceof RunOutcome.ParseFailed),
                               (int) count(files, f -> f.format() instanceof RunOutcome.WriteFailed || f.format() instanceof RunOutcome.Refused));

        return RootResult.rootResult(path,
                                     mode,
                                     ModeSource.FromDefault.fromDefault(),
                                     Option.none(),
                                     false,
                                     "production root",
                                     files.size(),
                                     invocation.activities().lint() ? Option.some(lint) : Option.none(),
                                     invocation.activities().format() != FormatAction.NONE ? Option.some(format) : Option.none(),
                                     errors,
                                     warnings,
                                     infos,
                                     fails);
    }

    private static Diagnostic diagnostic() {
        return Diagnostic.diagnostic("JBCT-RET-01", DiagnosticSeverity.WARNING, "A.java", 1, 1, "m", "d");
    }

    private static RunLines.Lines scoreRun(Invocation invocation, List<FileResult> files) {
        return run(invocation, CLEAN, files);
    }

    private static RunLines.Lines run(Invocation invocation, Verdict verdict) {
        return run(invocation, verdict, List.of());
    }

    private static RunLines.Lines run(Invocation invocation, Verdict verdict, List<FileResult> files) {
        return run(invocation, verdict, 0, 0, 0, files);
    }

    private static RunLines.Lines run(Invocation invocation, Verdict verdict, int errors, int warnings, int infos, List<FileResult> files) {
        return run(invocation, verdict, errors, warnings, infos, errors > 0, files);
    }

    private static RunLines.Lines run(Invocation invocation,
                                      Verdict verdict,
                                      int errors,
                                      int warnings,
                                      int infos,
                                      boolean fails,
                                      List<FileResult> files) {
        var root = rootOf(invocation, ROOT, JbctMode.BUSINESS, files, errors, warnings, infos, fails);

        return RunLines.runLines(invocation, files, List.of(root), verdict, List.of(), 0).lines();
    }

    private static String failure(Invocation invocation, List<FileResult> files, int errors, boolean fails) {
        return RunLines.failureOf(invocation,
                                  files,
                                  List.of(rootOf(invocation, ROOT, JbctMode.BUSINESS, files, errors, 0, 0, fails)));
    }

    private static boolean isRootLine(Line line) {
        return line.text().startsWith("mode=");
    }

    /// The lines before the findings without the root line, which has its own tests.
    private static List<Line> bodyBefore(RunLines.Lines lines) {
        return lines.before().stream().filter(line -> !isRootLine(line) && !line.text().isEmpty()).toList();
    }

    private static Line rootLineOf(RunLines.Lines lines) {
        return lines.before().stream().filter(RunLinesTest::isRootLine).findFirst().orElseGet(() -> fail("no root line"));
    }

    private static List<String> texts(RunLines.Lines lines) {
        return Stream.concat(lines.before().stream(), bodyAfter(lines).stream())
                     .filter(line -> !isRootLine(line))
                     .map(Line::text)
                     .toList();
    }
}
