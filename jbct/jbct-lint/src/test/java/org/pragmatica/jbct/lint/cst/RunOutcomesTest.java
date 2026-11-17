package org.pragmatica.jbct.lint.cst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.cst.LintRun.RootCounts;
import org.pragmatica.jbct.lint.cst.LintRun.RunReport;
import org.pragmatica.jbct.lint.cst.RootResolver.Request;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// What a plan-built [LintRun] RECORDS per file (linted, formatted, not parsed, skipped by mode) and the sentences it
/// produces from those records (#2525, P2c fix round 2). Every count here is the tally of recorded outcomes; no test
/// reads a count that is a difference of two others.
class RunOutcomesTest {
    private static final String BROKEN = "class {{{ not java";
    private static final String BUSINESS = "[lint]\nmode = \"business\"\n";
    private static final String FORMAT_ONLY = "[lint]\nmode = \"format-only\"\n";
    private static final String DISABLED = "[lint]\nmode = \"disabled\"\n";

    @TempDir
    Path temp;

    private String savedUserHome;

    @BeforeEach
    void isolate() throws IOException {
        savedUserHome = System.getProperty("user.home");
        System.setProperty("user.home", temp.resolve("home").toString());
        Files.createDirectories(temp.resolve(".git"));
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", savedUserHome);
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

    private static RunReport finished(LintRun run) {
        return run.finish().fold(cause -> fail("finish failed: " + cause.message()), report -> report);
    }

    private static boolean claimed(LintRun run, Path file) {
        return run.claimFormat(file)
                  .onFailure(cause -> fail("claim failed: " + cause.message()))
                  .or(Boolean.FALSE);
    }

    private static RootCounts countsOf(RunReport report, Path module) {
        var root = module.resolve("src/main/java").toAbsolutePath().normalize();

        return report.roots().stream()
                     .filter(counts -> counts.root().equals(root))
                     .findFirst()
                     .orElseGet(() -> fail("no root " + root));
    }

    @Test
    void notParsed_unparseableFileInABusinessRoot_isNotParsedAndNotSkippedByMode() throws IOException {
        var business = module("biz", BUSINESS);
        var broken = source(business, "Broken.java", BROKEN);
        var run = LintRun.lintRun(plan(temp));

        assertTrue(run.lintFile(broken).isFailure());

        var report = finished(run);
        var counts = countsOf(report, business);

        assertEquals(1, counts.notParsed());
        assertEquals(0, counts.skipped());
        assertEquals(0, counts.examined());
        assertEquals(1, counts.files());
        assertEquals(1, report.notParsedFiles().size());
        assertTrue(report.notParsedFiles().getFirst().startsWith(broken.toAbsolutePath().normalize().toString()));
        assertTrue(report.summary().getFirst().contains("1 not parsed, 0 skipped by mode"), report.summary().getFirst());
    }

    @Test
    void notParsed_alongsideALintedFile_theLinesCountBoth() throws IOException {
        var business = module("biz", BUSINESS);
        var good = source(business, "Good.java", "class Good {}\n");
        var broken = source(business, "Broken.java", BROKEN);
        var run = LintRun.lintRun(plan(temp));

        assertTrue(run.lintFile(good).isSuccess());
        assertTrue(run.lintFile(broken).isFailure());

        assertEquals(List.of("1 file(s) linted, 1 file(s) not parsed"), finished(run).lintLines());
    }

    @Test
    void skipped_aDisabledRootsFile_isSkippedByModeAndNeverCountedAsLinted() throws IOException {
        var disabled = module("off", DISABLED);
        var file = source(disabled, "A.java", "class A {}\n");
        var run = LintRun.lintRun(plan(temp));

        assertTrue(run.lintFile(file).isSuccess());
        assertFalse(claimed(run, file));

        var counts = countsOf(finished(run), disabled);

        assertEquals(1, counts.skipped());
        assertEquals(0, counts.examined());
        assertEquals(0, counts.formatted());
        assertEquals(0, counts.notParsed());
    }

    @Test
    void skipped_aFormatOnlyRootsFile_isFormattedNotSkipped_andNeverLinted() throws IOException {
        var formatOnly = module("fmt", FORMAT_ONLY);
        var file = source(formatOnly, "A.java", BROKEN);
        var run = LintRun.lintRun(plan(temp));

        assertTrue(claimed(run, file));
        assertTrue(run.lintFile(file).isSuccess());

        var counts = countsOf(finished(run), formatOnly);

        assertEquals(1, counts.formatted());
        assertEquals(0, counts.examined());
        assertEquals(0, counts.skipped());
        assertEquals(0, counts.notParsed());
    }

    @Test
    void lines_everyRootDisabled_sayNothingWasDoneAndWhyPerRoot_neverAPass() throws IOException {
        var disabled = module("off", DISABLED);
        var file = source(disabled, "A.java", "class A {}\n");
        var run = LintRun.lintRun(plan(temp));
        var root = disabled.resolve("src/main/java").toAbsolutePath().normalize();

        assertTrue(run.lintFile(file).isSuccess());
        run.claimFormat(file);

        var report = finished(run);

        assertEquals(List.of("Nothing was linted: " + root + " (mode disabled skips it)"), report.lintLines());
        assertEquals(List.of("Nothing was formatted: " + root + " (mode disabled skips it)"), report.formatLines());
        assertEquals(List.of("Nothing was format-checked: " + root + " (mode disabled skips it)"),
                     report.formatCheckLines());
        assertEquals(List.of("Not scored: " + root + " (mode disabled skips it)",
                             "Nothing was scored: " + root + " (mode disabled skips it)"),
                     report.scoreLines());
    }

    @Test
    void lines_aFormatOnlyRoot_formatsButLintsNothing() throws IOException {
        var formatOnly = module("fmt", FORMAT_ONLY);
        var file = source(formatOnly, "A.java", "class A {}\n");
        var run = LintRun.lintRun(plan(temp));
        var root = formatOnly.resolve("src/main/java").toAbsolutePath().normalize();

        run.claimFormat(file);
        assertTrue(run.lintFile(file).isSuccess());

        var report = finished(run);

        assertEquals(List.of("1 file(s) formatted"), report.formatLines());
        assertEquals(List.of("1 file(s) format-checked"), report.formatCheckLines());
        assertEquals(List.of("Nothing was linted: " + root + " (mode format-only skips it)"), report.lintLines());
    }

    @Test
    void lines_aBusinessRoot_countsWhatWasDone() throws IOException {
        var business = module("biz", BUSINESS);
        var file = source(business, "A.java", "class A {}\n");
        var run = LintRun.lintRun(plan(temp));

        run.claimFormat(file);
        assertTrue(run.lintFile(file).isSuccess());

        var report = finished(run);
        var counts = countsOf(report, business);

        assertEquals(1, counts.examined());
        assertEquals(1, counts.formatted());
        assertEquals(0, counts.skipped());
        assertEquals(List.of("1 file(s) linted"), report.lintLines());
        assertEquals(List.of("1 file(s) scored"), report.scoreLines());
    }

    @Test
    void scorable_onlyTheFilesOfRootsWhoseModeRunsRules() throws IOException {
        var business = module("biz", BUSINESS);
        var formatOnly = module("fmt", FORMAT_ONLY);
        var disabled = module("off", DISABLED);
        var scored = source(business, "A.java", "class A {}\n");

        source(formatOnly, "B.java", "class B {}\n");
        source(disabled, "C.java", "class C {}\n");

        assertEquals(List.of(scored.toAbsolutePath().normalize()), LintRun.lintRun(plan(temp)).scorable());
    }

    @Test
    void resolve_aDirectoryArgumentWrittenWithDots_isOneRootInNormalisedForm() throws IOException {
        var business = module("biz", BUSINESS);
        var file = source(business, "A.java", "class A {}\n");
        var plan = plan(temp.resolve("biz/../biz/./src"), business);

        assertEquals(List.of(file.toAbsolutePath().normalize()), plan.files());
        assertEquals(1, plan.roots().size());
        assertEquals(business.resolve("src/main/java").toAbsolutePath().normalize(), plan.roots().getFirst().path());
    }

    @Test
    void resolve_layoutAboveTheArgument_isNotTheRootOfALayoutlessModule() throws IOException {
        var above = Files.createDirectories(temp.resolve("work/src/main/java/checkout"));
        var module = Files.createDirectories(above.resolve("mod"));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"), BUSINESS);

        var file = Files.writeString(Files.createDirectories(module.resolve("com/example")).resolve("A.java"),
                                     "class A {}\n");
        var plan = plan(module);

        assertEquals(List.of(module.toAbsolutePath().normalize()),
                     plan.roots().stream().map(root -> root.path()).toList());
        assertEquals(List.of(file.toAbsolutePath().normalize()), plan.files());
    }
}
