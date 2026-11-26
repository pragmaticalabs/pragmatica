package org.pragmatica.jbct.lint.cst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.lint.cst.RootResolver.Request;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.cst.RunOutcome.DensityLimit;
import org.pragmatica.jbct.lint.cst.RunOutcome.FileResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.FormatAction;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Kind;
import org.pragmatica.jbct.lint.cst.RunOutcome.Linted;
import org.pragmatica.jbct.lint.cst.RunOutcome.NotAsked;
import org.pragmatica.jbct.lint.cst.RunOutcome.Notification;
import org.pragmatica.jbct.lint.cst.RunOutcome.ParseFailed;
import org.pragmatica.jbct.lint.cst.RunOutcome.ReadFailed;
import org.pragmatica.jbct.lint.cst.RunOutcome.Refused;
import org.pragmatica.jbct.lint.cst.RunOutcome.Rewritten;
import org.pragmatica.jbct.lint.cst.RunOutcome.RootResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.SkippedByMode;
import org.pragmatica.jbct.lint.cst.RunOutcome.Surface;
import org.pragmatica.jbct.lint.cst.RunOutcome.Tally;
import org.pragmatica.jbct.lint.cst.RunOutcome.Unchanged;
import org.pragmatica.jbct.lint.cst.RunOutcome.Withheld;
import org.pragmatica.jbct.lint.cst.RunOutcome.WouldChange;
import org.pragmatica.jbct.lint.cst.RunOutcome.WriteFailed;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.score.DensityGate;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// The run's outcome table (#2525, memo3318 §2 and §7): one test per filled cell, named `row_<R><col>_...` after the
/// table's row (R1 done, R2 already formatted, R3 would change, R4 read failure, R5 parse failure, R6 write failure,
/// R7 withheld, R8 skipped by mode, R9 not asked) and column (L lint, W format write, C format check). Every outcome is
/// read from [LintRun#perform]'s result and, where the table says something was or was not written, from the disk.
/// Score cells: S1 scored, S4 read failure, S5 parse failure, S8 skipped by mode; the machine-format facts follow them.
class RunActionTableTest {
    private static final String RET_ERROR = """
        package com.example;
        class Pricing {
            String name() {
                return null;
            }
        }
        """;
    private static final String UNFORMATTED = "package com.example;\nclass Plain{int x;   }\n";
    private static final String BROKEN = "class {{{ not java";
    private static final String BUSINESS = "[lint]\nmode = \"business\"\n";
    private static final String INFRASTRUCTURE = "[lint]\nmode = \"infrastructure\"\n";
    private static final String FORMAT_ONLY = "[lint]\nmode = \"format-only\"\n";
    private static final String DISABLED = "[lint]\nmode = \"disabled\"\n";
    private static final String LAYERS = BUSINESS + "\n[lint.layers]\ndomain = [\"com.example.**\"]\n";

    @TempDir
    Path temp;

    private String savedUserHome;

    @BeforeEach
    void isolate() throws IOException {
        savedUserHome = System.getProperty("user.home");
        System.setProperty("user.home",
                           temp.resolve("home")
                               .toString());
        Files.createDirectories(temp.resolve(".git"));
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", savedUserHome);
    }

    // --- R1 done with result

    @Test
    void row_L1_lintedFileRecordsLintedWithItsDiagnosticsAndTheRootFails() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Pricing.java", RET_ERROR);
        var result = perform(plan(module), Invocation.lint(Surface.CLI));
        var outcome = only(result, file);

        assertTrue(outcome.lint() instanceof Linted(var diagnostics) && !diagnostics.isEmpty(), "lint: " + outcome.lint());
        assertEquals(1, lintTally(result).done());
        assertTrue(root(result).fails());
        assertEquals(Kind.FINDINGS, result.verdict().kind());
        assertFalse(result.findings().isEmpty());
    }

    @Test
    void score_runOverLintFindings_returnsNoFinding_yetStillLintsTheFile() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Pricing.java", RET_ERROR);
        var result = perform(plan(module), Invocation.score(Surface.CLI, Option.none()));

        assertTrue(only(result, file).lint() instanceof Linted(var diagnostics) && !diagnostics.isEmpty());
        assertTrue(result.findings().isEmpty(), "the release's score printed no finding: " + result.findings());
    }

    @Test
    void row_W1_changedFileIsRewrittenAndTheDiskHoldsTheFormattedContent() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Plain.java", UNFORMATTED);
        var result = perform(plan(module), format(FormatAction.WRITE));

        assertInstanceOf(Rewritten.class, only(result, file).format());
        assertEquals(formatted(UNFORMATTED), Files.readString(file));
        assertEquals(1, formatTally(result).done());
        assertEquals(Kind.CLEAN, result.verdict().kind());
    }

    // --- R2 already formatted

    @Test
    void row_W2_formattedFileIsUnchangedAndNotWritten() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Plain.java", formatted(UNFORMATTED));
        var before = Files.getLastModifiedTime(file);
        var result = perform(plan(module), format(FormatAction.WRITE));

        assertInstanceOf(Unchanged.class, only(result, file).format());
        assertEquals(before, Files.getLastModifiedTime(file));
        assertEquals(Tally.tally(0, 1, 0, 0, 0, 0, 0, 0), formatTally(result));
        assertEquals(Kind.CLEAN, result.verdict().kind());
    }

    @Test
    void row_C2_formattedFileIsUnchangedUnderTheCheck() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Plain.java", formatted(UNFORMATTED));
        var result = perform(plan(module), Invocation.formatCheck(Surface.MAVEN));

        assertInstanceOf(Unchanged.class, only(result, file).format());
        assertEquals(1, formatTally(result).unchanged());
        assertEquals(Kind.CLEAN, result.verdict().kind());
    }

    // --- R3 would change

    @Test
    void row_C3_check_fileThatWouldChangeIsFindingsAndNotWritten() throws IOException {
        var module = module("m", FORMAT_ONLY);
        var file = source(module, "Plain.java", UNFORMATTED);
        var result = perform(plan(module), Invocation.check(Surface.CLI));

        assertInstanceOf(WouldChange.class, only(result, file).format());
        assertEquals(UNFORMATTED, Files.readString(file));
        assertEquals(1, formatTally(result).wouldChange());
        assertEquals(Kind.FINDINGS, result.verdict().kind());
    }

    @Test
    void row_C3_dryRun_fileThatWouldChangeIsCleanAndNotWritten() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Plain.java", UNFORMATTED);
        var result = perform(plan(module), format(FormatAction.DRY_RUN));

        assertInstanceOf(WouldChange.class, only(result, file).format());
        assertEquals(UNFORMATTED, Files.readString(file));
        assertEquals(1, formatTally(result).wouldChange());
        assertEquals(Kind.CLEAN, result.verdict().kind());
    }

    // --- R4 read failure

    @Test
    void row_L4_unreadableFileIsReadFailedAndIncomplete() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Plain.java", UNFORMATTED);
        var plan = plan(module);

        Files.delete(file);
        var result = perform(plan, Invocation.lint(Surface.CLI));

        assertInstanceOf(ReadFailed.class, only(result, file).lint());
        assertEquals(Tally.tally(0, 0, 0, 0, 0, 1, 0, 0), lintTally(result));
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    @Test
    void row_W4_unreadableFileIsReadFailedAndIncomplete() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Plain.java", UNFORMATTED);
        var plan = plan(module);

        Files.delete(file);
        var result = perform(plan, format(FormatAction.WRITE));

        assertInstanceOf(ReadFailed.class, only(result, file).format());
        assertEquals(Tally.tally(0, 0, 0, 0, 0, 1, 0, 0), formatTally(result));
        assertFalse(Files.exists(file));
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    @Test
    void row_C4_unreadableFileIsReadFailedAndIncomplete() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Plain.java", UNFORMATTED);
        var plan = plan(module);

        Files.delete(file);
        var result = perform(plan, Invocation.formatCheck(Surface.CLI));

        assertInstanceOf(ReadFailed.class, only(result, file).format());
        assertEquals(1, formatTally(result).notRead());
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    // --- R5 parse failure

    @Test
    void row_L5_unparseableFileIsParseFailedAndIncomplete() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Broken.java", BROKEN);
        var result = perform(plan(module), Invocation.lint(Surface.CLI));

        assertInstanceOf(ParseFailed.class, only(result, file).lint());
        assertEquals(Tally.tally(0, 0, 0, 0, 0, 0, 1, 0), lintTally(result));
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    @Test
    void row_W5_unparseableFileUnderProcessIsParseFailedForBothNothingWrittenNoFormattedClaim() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Broken.java", BROKEN);
        var result = perform(plan(module), Invocation.process(Surface.MAVEN, false));
        var outcome = only(result, file);

        assertInstanceOf(ParseFailed.class, outcome.lint());
        assertInstanceOf(ParseFailed.class, outcome.format());
        assertEquals(BROKEN, Files.readString(file));
        assertEquals(Tally.tally(0, 0, 0, 0, 0, 0, 1, 0), formatTally(result));
        assertEquals(Tally.tally(0, 0, 0, 0, 0, 0, 1, 0), lintTally(result));
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    @Test
    void row_C5_unparseableFileUnderCheckIsParseFailedForBothAndIncomplete() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Broken.java", BROKEN);
        var result = perform(plan(module), Invocation.check(Surface.CLI));
        var outcome = only(result, file);

        assertInstanceOf(ParseFailed.class, outcome.lint());
        assertInstanceOf(ParseFailed.class, outcome.format());
        assertEquals(0, formatTally(result).unchanged() + formatTally(result).wouldChange());
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    // --- R6 write failure

    @Test
    void row_W6_readOnlyFileIsWriteFailedNotRewritten() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Plain.java", UNFORMATTED);

        assertTrue(file.toFile().setWritable(false), "fixture: the file must become read-only");
        var result = perform(plan(module), format(FormatAction.WRITE));

        assertInstanceOf(WriteFailed.class, only(result, file).format());
        assertEquals(UNFORMATTED, Files.readString(file));
        assertEquals(Tally.tally(0, 0, 0, 0, 0, 0, 0, 1), formatTally(result));
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    @Test
    void row_R9_processLintedAndWriteFailedOnOneFile() throws IOException {
        var module = module("m", INFRASTRUCTURE);
        var file = source(module, "Plain.java", UNFORMATTED);

        assertTrue(file.toFile().setWritable(false), "fixture: the file must become read-only");
        var result = perform(plan(module), Invocation.process(Surface.MAVEN, false));
        var outcome = only(result, file);

        assertInstanceOf(Linted.class, outcome.lint());
        assertInstanceOf(WriteFailed.class, outcome.format());
        assertEquals(1, lintTally(result).done());
        assertEquals(1, formatTally(result).writeFailed());
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    // --- content guard (#3691): a formatted text that would lose a comment is refused, the file stays as it was.
    // The source is a printer gap of this base (a `//` after a ternary condition is deleted by the printer); when the
    // printer keeps it, this fixture needs another gap or the guard needs its own formatter seam.

    private static final String LOSES_A_COMMENT = "package com.example;\n\nclass Plain {\n    int f(boolean c) {\n"
                                                  + "        return c // POS4\n               ? 1\n               : 2;\n    }\n}\n";

    @Test
    void row_G1_writeRefusesAFileThatWouldLoseAComment_andLeavesItUntouched() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Plain.java", LOSES_A_COMMENT);
        var result = perform(plan(module), format(FormatAction.WRITE));
        var outcome = only(result, file);

        assertInstanceOf(Refused.class, outcome.format());
        assertTrue(((Refused) outcome.format()).message().contains("comment lost: `// POS4` (input line 5)"));
        assertEquals(LOSES_A_COMMENT, Files.readString(file));
        assertEquals(Tally.tally(0, 0, 0, 0, 0, 0, 0, 1), formatTally(result));
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    @Test
    void row_G2_checkRefusesTheSameFile_notWouldChange() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Plain.java", LOSES_A_COMMENT);
        var result = perform(plan(module), format(FormatAction.CHECK));

        assertInstanceOf(Refused.class, only(result, file).format());
        assertEquals(LOSES_A_COMMENT, Files.readString(file));
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    @Test
    void row_G3_processLintsTheFileAndRefusesItsFormat() throws IOException {
        var module = module("m", INFRASTRUCTURE);
        var file = source(module, "Plain.java", LOSES_A_COMMENT);
        var result = perform(plan(module), Invocation.process(Surface.MAVEN, false));
        var outcome = only(result, file);

        assertInstanceOf(Linted.class, outcome.lint());
        assertInstanceOf(Refused.class, outcome.format());
        assertEquals(LOSES_A_COMMENT, Files.readString(file));
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    // --- R7 withheld

    @Test
    void row_W7_failBeforeFormatWithholdsTheFormatOfAFileWithLintErrors() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Pricing.java", RET_ERROR + "class  Extra{int y;   }\n");
        var content = Files.readString(file);
        var result = perform(plan(module), Invocation.process(Surface.MAVEN, true));
        var outcome = only(result, file);

        assertTrue(outcome.lint() instanceof Linted(var diagnostics) && !diagnostics.isEmpty(), "lint: " + outcome.lint());
        assertInstanceOf(Withheld.class, outcome.format());
        assertEquals(content, Files.readString(file));
        assertEquals(Tally.tally(0, 0, 0, 1, 0, 0, 0, 0), formatTally(result));
        assertEquals(Kind.FINDINGS, result.verdict().kind());
    }

    // --- R8 skipped by mode

    @Test
    void row_L8_formatOnlyAndDisabledRootsSkipTheLintAndADisabledFileIsNeverOpened() throws IOException {
        var formatOnly = module("f", FORMAT_ONLY);
        var disabled = module("d", DISABLED);
        var kept = source(formatOnly, "Plain.java", UNFORMATTED);
        var unreadable = source(disabled, "Gone.java", UNFORMATTED);
        var plan = plan(formatOnly, disabled);

        Files.delete(unreadable);
        var result = perform(plan, Invocation.lint(Surface.CLI));

        assertEquals(SkippedByMode.skippedByMode(JbctMode.FORMAT_ONLY), only(result, kept).lint());
        assertEquals(SkippedByMode.skippedByMode(JbctMode.DISABLED), only(result, unreadable).lint());
        result.roots()
              .forEach(root -> assertEquals(Tally.tally(0, 0, 0, 0, 1, 0, 0, 0), root.lint().or(Tally.empty())));
        assertEquals(Kind.CLEAN, result.verdict().kind());
    }

    @Test
    void row_W8_disabledRootSkipsTheFormatAndNeverOpensTheFile() throws IOException {
        var disabled = module("d", DISABLED);
        var unreadable = source(disabled, "Gone.java", UNFORMATTED);
        var plan = plan(disabled);

        Files.delete(unreadable);
        var result = perform(plan, format(FormatAction.WRITE));

        assertEquals(SkippedByMode.skippedByMode(JbctMode.DISABLED), only(result, unreadable).format());
        assertEquals(Tally.tally(0, 0, 0, 0, 1, 0, 0, 0), formatTally(result));
        assertEquals(Kind.CLEAN, result.verdict().kind());
    }

    @Test
    void row_C8_disabledRootSkipsTheFormatCheckAndNeverOpensTheFile() throws IOException {
        var disabled = module("d", DISABLED);
        var unreadable = source(disabled, "Gone.java", UNFORMATTED);
        var plan = plan(disabled);

        Files.delete(unreadable);
        var result = perform(plan, Invocation.check(Surface.CLI));
        var outcome = only(result, unreadable);

        assertEquals(SkippedByMode.skippedByMode(JbctMode.DISABLED), outcome.format());
        assertEquals(SkippedByMode.skippedByMode(JbctMode.DISABLED), outcome.lint());
        assertEquals(Tally.tally(0, 0, 0, 0, 1, 0, 0, 0), formatTally(result));
        assertEquals(Kind.CLEAN, result.verdict().kind());
    }

    // --- R9 not asked

    @Test
    void row_L9W9_anActivityNotAskedIsNotAskedAndHasNoTally() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Plain.java", formatted(UNFORMATTED));
        var linted = perform(plan(module), Invocation.lint(Surface.CLI));
        var checked = perform(plan(module), Invocation.formatCheck(Surface.CLI));

        assertInstanceOf(NotAsked.class, only(linted, file).format());
        assertTrue(root(linted).format().isEmpty());
        assertInstanceOf(NotAsked.class, only(checked, file).lint());
        assertTrue(root(checked).lint().isEmpty());
    }

    // --- run-level arms

    @Test
    void perform_TP1_eachFileIsParsedOnceForLintAndFormat_withLayersConfigured() throws IOException {
        var module = module("m", LAYERS);
        var first = source(module, "Plain.java", UNFORMATTED);
        var second = source(module, "Pricing.java", RET_ERROR);
        var parses = new AtomicInteger();
        var parser = new Java25Parser();
        Function<String, Result<Cursor>> counting = content -> counted(parser, parses, content);
        var result = LintRun.lintRun(plan(module), counting)
                            .perform(Invocation.process(Surface.MAVEN, false))
                            .fold(cause -> fail("perform failed: " + cause.message()), done -> done);

        assertEquals(2, parses.get());
        assertInstanceOf(Linted.class, only(result, first).lint());
        assertInstanceOf(Linted.class, only(result, second).lint());
        assertInstanceOf(Rewritten.class, only(result, first).format());
        assertEquals(2, lintTally(result).done());
    }

    @Test
    void perform_TSigma_everyTallyOfAMixedProcessRunSumsToTheRootsFiles() throws IOException {
        var business = module("b", BUSINESS);
        var disabled = module("d", DISABLED);
        var formatOnly = module("f", FORMAT_ONLY);

        source(business, "Plain.java", UNFORMATTED);
        source(business, "Done.java", formatted(UNFORMATTED).replace("Plain", "Done"));
        source(business, "Broken.java", BROKEN);
        var gone = source(business, "Gone.java", UNFORMATTED);
        var readOnly = source(business, "ReadOnly.java", UNFORMATTED.replace("Plain", "ReadOnly"));

        source(disabled, "A.java", UNFORMATTED);
        source(disabled, "B.java", UNFORMATTED);
        source(formatOnly, "C.java", UNFORMATTED);
        var plan = plan(business, disabled, formatOnly);

        Files.delete(gone);
        assertTrue(readOnly.toFile().setWritable(false), "fixture: the file must become read-only");
        var result = perform(plan, Invocation.process(Surface.MAVEN, false));

        assertEquals(8, result.files().size());
        for (var root : result.roots()) {
            assertEquals(root.files(), sum(root.lint().or(Tally.empty())), "lint clauses of " + root.root());
            assertEquals(root.files(), sum(root.format().or(Tally.empty())), "format clauses of " + root.root());
        }
        assertEquals(Tally.tally(3, 0, 0, 0, 0, 1, 1, 0), rootOf(result, business).lint().or(Tally.empty()));
        assertEquals(Tally.tally(1, 1, 0, 0, 0, 1, 1, 1), rootOf(result, business).format().or(Tally.empty()));
        assertEquals(Tally.tally(0, 0, 0, 0, 2, 0, 0, 0), rootOf(result, disabled).lint().or(Tally.empty()));
        assertEquals(Tally.tally(0, 0, 0, 0, 2, 0, 0, 0), rootOf(result, disabled).format().or(Tally.empty()));
        assertEquals(Tally.tally(0, 0, 0, 0, 1, 0, 0, 0), rootOf(result, formatOnly).lint().or(Tally.empty()));
        assertEquals(Tally.tally(1, 0, 0, 0, 0, 0, 0, 0), rootOf(result, formatOnly).format().or(Tally.empty()));
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    @Test
    void perform_C13_aDirectoryAndItsSubdirectoryGiveOneResultAndOneWritePerFile() throws IOException {
        var sub = Files.createDirectories(temp.resolve("x/sub"));
        var top = Files.writeString(temp.resolve("x/A.java"), UNFORMATTED.replace("Plain", "A"));
        var below = Files.writeString(sub.resolve("B.java"), UNFORMATTED.replace("Plain", "B"));
        var result = perform(plan(temp.resolve("x"), sub), format(FormatAction.WRITE));

        assertEquals(2, result.files().size());
        assertEquals(Set.of(top.toAbsolutePath().normalize(), below.toAbsolutePath().normalize()),
                     result.files()
                           .stream()
                           .map(FileResult::file)
                           .collect(Collectors.toSet()));
        assertInstanceOf(Rewritten.class, only(result, top).format());
        assertInstanceOf(Rewritten.class, only(result, below).format());
        assertEquals(2,
                     result.roots()
                           .stream()
                           .mapToInt(root -> root.format().or(Tally.empty()).done())
                           .sum());
    }

    @Test
    void perform_secondCall_isRefused() throws IOException {
        var module = module("m", BUSINESS);

        source(module, "Plain.java", UNFORMATTED);
        var run = LintRun.lintRun(plan(module));

        assertTrue(run.perform(Invocation.lint(Surface.CLI)).isSuccess());
        assertTrue(run.perform(Invocation.lint(Surface.CLI)).isFailure());
    }

    // --- fixtures

    // --- S: score = the lint outcome plus the file's non-blank line count, from the run's own single read

    @Test
    void row_S1_scoredFileRecordsLintedWithItsLinesAndTheScoreIsBuiltFromTheRunsOwnResults() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Pricing.java", RET_ERROR);
        var result = perform(plan(module), Invocation.score(Surface.CLI, Option.none()));
        var outcome = only(result, file);
        var score = result.score().or(() -> fail("no score"));

        assertInstanceOf(Linted.class, outcome.lint());
        assertEquals(1, lintTally(result).done());
        assertTrue(outcome.linesOfCode() > 0);
        assertEquals(outcome.linesOfCode(), score.linesOfCode());
        assertEquals(1, score.filesAnalyzed());
        assertEquals(0, score.filesUnanalyzed());
        assertEquals(Kind.CLEAN, result.verdict().kind(), "a score without a limit is not failed by its findings");
        assertTrue(result.before().isEmpty() || result.before().stream().noneMatch(line -> line.text().contains("passes")));
    }

    @Test
    void row_S1_densityAboveTheLimitIsFindingsWithTheGatesMessage() throws IOException {
        var module = module("m", BUSINESS);

        source(module, "Pricing.java", RET_ERROR);
        var limit = Option.some(DensityLimit.densityLimit(0.0001, "--max-density"));
        var result = perform(plan(module), Invocation.score(Surface.CLI, limit));
        var density = result.score().or(() -> fail("no score")).totalDensityPerKloc();

        assertEquals(Kind.FINDINGS, result.verdict().kind());
        assertEquals(DensityGate.breachMessage(density, 0.0001), result.verdict().failure().or(""));
    }

    @Test
    void row_S1_densityWithinTheLimitIsClean() throws IOException {
        var module = module("m", BUSINESS);

        source(module, "Pricing.java", RET_ERROR);
        var limit = Option.some(DensityLimit.densityLimit(1.0e9, "--max-density"));
        var result = perform(plan(module), Invocation.score(Surface.CLI, limit));

        assertEquals(Kind.CLEAN, result.verdict().kind());
    }

    @Test
    void row_S4_unreadableFileIsReadFailedCountedUnanalysedAndIncomplete() throws IOException {
        var module = module("m", BUSINESS);
        var kept = source(module, "Plain.java", UNFORMATTED);
        var gone = source(module, "Gone.java", UNFORMATTED);
        var plan = plan(module);

        Files.delete(gone);
        var result = perform(plan, Invocation.score(Surface.CLI, Option.none()));
        var score = result.score().or(() -> fail("no score"));

        assertInstanceOf(ReadFailed.class, only(result, gone).lint());
        assertInstanceOf(Linted.class, only(result, kept).lint());
        assertEquals(Tally.tally(1, 0, 0, 0, 0, 1, 0, 0), lintTally(result));
        assertEquals(1, score.filesAnalyzed());
        assertEquals(1, score.filesUnanalyzed());
        assertEquals(only(result, kept).linesOfCode(), score.linesOfCode());
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    @Test
    void row_S5_unparseableFileIsParseFailedCountedUnanalysedAndIncompleteWithNothingScored() throws IOException {
        var module = module("m", BUSINESS);
        var file = source(module, "Broken.java", BROKEN);
        var result = perform(plan(module), Invocation.score(Surface.CLI, Option.none()));

        assertInstanceOf(ParseFailed.class, only(result, file).lint());
        assertEquals(Tally.tally(0, 0, 0, 0, 0, 0, 1, 0), lintTally(result));
        assertTrue(result.score().isEmpty());
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
    }

    @Test
    void row_S8_formatOnlyAndDisabledRootsAreNotScoredAndADisabledFileIsNeverOpened() throws IOException {
        var formatOnly = module("f", FORMAT_ONLY);
        var disabled = module("d", DISABLED);
        var business = module("b", BUSINESS);
        var kept = source(formatOnly, "Plain.java", UNFORMATTED);
        var unreadable = source(disabled, "Gone.java", UNFORMATTED);
        var scored = source(business, "Plain.java", UNFORMATTED);
        var plan = plan(formatOnly, disabled, business);

        Files.delete(unreadable);
        var result = perform(plan, Invocation.score(Surface.MAVEN, Option.none()));

        assertEquals(SkippedByMode.skippedByMode(JbctMode.FORMAT_ONLY), only(result, kept).lint());
        assertEquals(SkippedByMode.skippedByMode(JbctMode.DISABLED), only(result, unreadable).lint());
        assertInstanceOf(Linted.class, only(result, scored).lint());
        assertEquals(1, result.score().or(() -> fail("no score")).filesAnalyzed());
        assertTrue(result.before().stream().anyMatch(line -> line.text().equals("Measuring 1 Java file(s)")));
        assertEquals(2,
                     result.after().stream().filter(line -> line.text().startsWith("Not scored: ")).count());
        assertEquals(Kind.CLEAN, result.verdict().kind());
    }

    @Test
    void row_S8_nothingScoredWithALimitSaysNotEvaluatedAndNeverSaysPasses() throws IOException {
        var disabled = module("d", DISABLED);

        source(disabled, "Plain.java", UNFORMATTED);
        var limit = Option.some(DensityLimit.densityLimit(5.0, "--max-density"));
        var result = perform(plan(disabled), Invocation.score(Surface.CLI, limit));

        assertTrue(result.score().isEmpty());
        assertTrue(result.after().stream().anyMatch(line -> line.text().equals("--max-density 5.0 was not evaluated: nothing was scored")));
        assertTrue(result.before().stream().noneMatch(line -> line.text().contains("passes")));
        assertTrue(result.after().stream().noneMatch(line -> line.text().contains("passes")));
        assertEquals(Kind.CLEAN, result.verdict().kind());
    }

    // --- machine-format facts of the result (renderers copy them)

    @Test
    void machineFormat_allSkippedRun_isSuccessfulWithANoteAndCountsSkippedNotExamined() throws IOException {
        var formatOnly = module("f", FORMAT_ONLY);

        source(formatOnly, "Plain.java", UNFORMATTED);
        var result = perform(plan(formatOnly), Invocation.lint(Surface.CLI));

        assertEquals(1, result.collected());
        assertEquals(0, result.examined());
        assertEquals(1, result.skippedByMode());
        assertTrue(result.executionSuccessful());
        assertEquals("note", result.notificationLevel());
        assertEquals(List.of(), result.unanalysed());
        assertEquals(List.of(Notification.notification("note",
                                                       "Nothing was linted: " + formatOnly.resolve("src/main/java")
                                                       + " (mode format-only skips it)",
                                                       Option.none())),
                     result.notifications());
    }

    @Test
    void machineFormat_cleanRun_isSuccessfulWithANoteAndCountsExamined() throws IOException {
        var module = module("m", BUSINESS);

        source(module, "Plain.java", UNFORMATTED);
        var result = perform(plan(module), Invocation.lint(Surface.CLI));

        assertEquals(Kind.CLEAN, result.verdict().kind());
        assertEquals(1, result.collected());
        assertEquals(1, result.examined());
        assertEquals(0, result.skippedByMode());
        assertTrue(result.executionSuccessful());
        assertEquals("note", result.notificationLevel());
        assertEquals(List.of(), result.notifications());
    }

    @Test
    void machineFormat_incompleteRun_isNotSuccessfulWithAnError() throws IOException {
        var module = module("m", BUSINESS);

        source(module, "Broken.java", BROKEN);
        source(module, "Plain.java", UNFORMATTED);
        var result = perform(plan(module), Invocation.lint(Surface.CLI));

        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
        assertEquals(2, result.collected());
        assertEquals(1, result.examined());
        assertFalse(result.executionSuccessful());
        assertEquals("error", result.notificationLevel());
        assertEquals(1, result.unanalysed().size());
        assertEquals(1, result.notifications().size());
        assertEquals("error", result.notifications().getFirst().level());
        assertTrue(result.notifications().getFirst().text().startsWith("Not analysed: Parse failed: "));
        assertTrue(result.notifications().getFirst().file().map(file -> file.endsWith("Broken.java")).or(false));
    }

    @Test
    void machineFormat_nothingCollectedRun_isNotSuccessfulWithAnError() throws IOException {
        var module = module("m", BUSINESS);
        var result = perform(plan(module), Invocation.lint(Surface.CLI));

        assertEquals(Kind.NOTHING_COLLECTED, result.verdict().kind());
        assertEquals(0, result.collected());
        assertFalse(result.executionSuccessful());
        assertEquals("error", result.notificationLevel());
        // As released: the run's closing sentences are empty here (the no-files sentence is a `before` line), so the
        // notification text is empty; the level and the absence of a file are what the SARIF note carries.
        assertEquals(List.of(Notification.notification("error", "", Option.none())), result.notifications());
    }

    @Test
    void machineFormat_totalsAreOneTallyPerAskedActivity() throws IOException {
        var module = module("m", BUSINESS);

        source(module, "Plain.java", UNFORMATTED);
        var lintOnly = perform(plan(module), Invocation.lint(Surface.CLI));

        assertEquals(Tally.tally(1, 0, 0, 0, 0, 0, 0, 0), lintOnly.totals().lint().or(Tally.empty()));
        assertTrue(lintOnly.totals().format().isEmpty());
        assertEquals(1, lintOnly.totals().files());
    }

    private static Result<Cursor> counted(Java25Parser parser, AtomicInteger parses, String content) {
        parses.incrementAndGet();
        return parser.parse(content);
    }

    private Path module(String name, String toml) throws IOException {
        var module = Files.createDirectories(temp.resolve(name));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"), toml);
        return module;
    }

    private static Path source(Path module, String name, String content) throws IOException {
        var directory = Files.createDirectories(module.resolve("src/main/java/com/example"));

        return Files.writeString(directory.resolve(name), content);
    }

    private static RootPlan plan(Path... paths) {
        return RootResolver.resolve(Request.request(List.of(paths)))
                           .fold(cause -> fail("resolve failed: " + cause.message()), plan -> plan);
    }

    private static RunResult perform(RootPlan plan, Invocation invocation) {
        return LintRun.lintRun(plan)
                      .perform(invocation)
                      .fold(cause -> fail("perform failed: " + cause.message()), result -> result);
    }

    private static Invocation format(FormatAction action) {
        return Invocation.format(Surface.CLI, action)
                         .fold(cause -> fail("invocation refused: " + cause.message()), invocation -> invocation);
    }

    /// The formatter's own output for the content; the fixture asserts it is a fixed point, so an "already formatted"
    /// file is one the run's format leaves as it is.
    private static String formatted(String content) {
        var formatter = JbctFormatter.jbctFormatter();
        var once = formatter.format(SourceFile.sourceFile(Path.of("Fixture.java"), content))
                            .fold(cause -> fail("fixture does not format: " + cause.message()), SourceFile::content);
        var twice = formatter.format(SourceFile.sourceFile(Path.of("Fixture.java"), once))
                             .fold(cause -> fail("fixture does not format: " + cause.message()), SourceFile::content);

        assertEquals(once, twice, "fixture: the formatted content must be a fixed point");
        assertNotEquals(content, once, "fixture: the unformatted content must change");
        return once;
    }

    private static FileResult only(RunResult result, Path file) {
        var matching = result.files()
                             .stream()
                             .filter(found -> found.file()
                                                   .equals(file.toAbsolutePath()
                                                               .normalize()))
                             .toList();

        assertEquals(1, matching.size(), "one result for " + file + " in " + result.files());
        return matching.getFirst();
    }

    private static RootResult root(RunResult result) {
        assertEquals(1, result.roots().size());
        return result.roots().getFirst();
    }

    private static RootResult rootOf(RunResult result, Path module) {
        var path = module.resolve("src/main/java")
                         .toAbsolutePath()
                         .normalize();

        return result.roots()
                     .stream()
                     .filter(root -> root.root()
                                         .equals(path))
                     .findFirst()
                     .orElseGet(() -> fail("no root " + path));
    }

    private static Tally lintTally(RunResult result) {
        return root(result).lint()
                           .or(() -> fail("no lint tally"));
    }

    private static Tally formatTally(RunResult result) {
        return root(result).format()
                           .or(() -> fail("no format tally"));
    }

    private static int sum(Tally tally) {
        return tally.done() + tally.unchanged() + tally.wouldChange() + tally.withheld() + tally.skippedByMode()
               + tally.notRead() + tally.notParsed() + tally.writeFailed();
    }
}
