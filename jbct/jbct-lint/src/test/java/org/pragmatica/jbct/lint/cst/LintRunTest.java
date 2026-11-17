package org.pragmatica.jbct.lint.cst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.LintRun.RunReport;
import org.pragmatica.jbct.lint.cst.LintRun.SourceRoot;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// [LintRun] (#2525): files grouped by source root, linted with their root's rules, finished with a per-root summary.
///
/// `format-only` and `disabled` are refused by the config parser until the entry points honour them, so those roots
/// are built here from a directly constructed context.
class LintRunTest {
    private static final String ARCH_02 = "JBCT-ARCH-02";
    private static final String RET_03 = "JBCT-RET-03";
    /// `lift` in a `domain` package (ARCH-02, a structure rule) and `return null` (RET-03, a code-level rule).
    private static final String BOTH = """
        package com.example.domain.pricing;
        class Pricing {
            Object run() {
                return Result.lift(Err::new, () -> compute());
            }
            String name() {
                return null;
            }
        }
        """;

    @TempDir
    Path temp;

    private static SourceRoot root(Path path, JbctMode mode) {
        return SourceRoot.sourceRoot(path, LintContext.defaultContext().withProject(mode, Option.none()));
    }

    private static Set<String> reported(Result<List<Diagnostic>> result) {
        return result.map(list -> list.stream()
                                      .map(Diagnostic::ruleId)
                                      .collect(Collectors.toSet()))
                     .or(Set.of("LINT-FAILED"));
    }

    private static SourceFile file(Path directory, String name) {
        return SourceFile.sourceFile(directory.resolve("com/example/" + name), BOTH);
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static <T> Cause failureOf(Result<T> result) {
        return result.fold(cause -> cause, value -> fail("expected a failure, got " + value));
    }

    private static RunReport finished(LintRun run) {
        return run.finish()
                  .onFailure(cause -> fail("finish failed: " + cause.message()))
                  .or(RunReport.runReport(List.of(), List.of()));
    }

    private Path directory(String relative) throws IOException {
        return Files.createDirectories(temp.resolve(relative));
    }

    @Test
    void lint_twoRootsWithDifferentModes_getDifferentRuleLists() throws IOException {
        var business = directory("biz/src/main/java");
        var infrastructure = directory("infra/src/main/java");
        var run = LintRun.lintRun(List.of(root(business, JbctMode.BUSINESS), root(infrastructure, JbctMode.INFRASTRUCTURE)));

        assertEquals(Set.of(ARCH_02, RET_03), reported(run.lint(file(business, "Pricing.java"))));
        assertEquals(Set.of(RET_03), reported(run.lint(file(infrastructure, "Pricing.java"))));
    }

    @Test
    void lintParsed_usesTheRulesOfTheFilesRoot_andRefusesAfterFinish() throws IOException {
        var business = directory("biz/src/main/java");
        var infrastructure = directory("infra/src/main/java");
        var formatOnly = directory("fmt/src/main/java");
        var run = LintRun.lintRun(List.of(root(business, JbctMode.BUSINESS),
                                          root(infrastructure, JbctMode.INFRASTRUCTURE),
                                          root(formatOnly, JbctMode.FORMAT_ONLY)));

        assertEquals(Set.of(ARCH_02, RET_03), reported(parsedLint(run, file(business, "Pricing.java"))));
        assertEquals(Set.of(RET_03), reported(parsedLint(run, file(infrastructure, "Pricing.java"))));
        assertEquals(Set.of(), reported(parsedLint(run, file(formatOnly, "Pricing.java"))));
        assertTrue(run.finish().isSuccess());
        assertEquals(LintRunError.General.RUN_FINISHED, failureOf(parsedLint(run, file(business, "Late.java"))));
    }

    private static Result<List<Diagnostic>> parsedLint(LintRun run, SourceFile file) {
        return new Java25Parser().parse(file.content())
                                 .flatMap(tree -> run.lintParsed(tree, file));
    }

    @Test
    void lint_formatOnlyAndDisabledRoots_lintNothing() throws IOException {
        var formatOnly = directory("fmt/src/main/java");
        var disabled = directory("off/src/main/java");
        var run = LintRun.lintRun(List.of(root(formatOnly, JbctMode.FORMAT_ONLY), root(disabled, JbctMode.DISABLED)));

        assertEquals(Set.of(), reported(run.lint(file(formatOnly, "Pricing.java"))));
        assertEquals(Set.of(), reported(run.lint(file(disabled, "Pricing.java"))));
    }

    @Test
    void lint_nestedRoots_routesToTheDeepestRoot() throws IOException {
        var outer = directory("outer");
        var inner = directory("outer/inner");
        var run = LintRun.lintRun(List.of(root(outer, JbctMode.BUSINESS), root(inner, JbctMode.INFRASTRUCTURE)));

        assertEquals(Set.of(RET_03), reported(run.lint(file(inner, "Pricing.java"))));
        assertEquals(Set.of(ARCH_02, RET_03), reported(run.lint(file(outer, "Pricing.java"))));
    }

    @Test
    void lint_fileOutsideEveryRoot_isTypedFailure() throws IOException {
        var run = LintRun.lintRun(List.of(root(directory("biz/src/main/java"), JbctMode.BUSINESS)));
        var stray = file(directory("elsewhere"), "Stray.java");

        assertInstanceOf(LintRunError.OutsideSourceRoots.class, failureOf(run.lint(stray)));
        // The refusal is not a count: the stray file belongs to no root's summary line.
        assertTrue(finished(run).summary().getFirst().contains(" 0 files"));
    }

    @Test
    void lint_siblingDirectoryWithSharedPrefix_isNotInsideTheRoot() throws IOException {
        var run = LintRun.lintRun(List.of(root(directory("src/main/java"), JbctMode.BUSINESS)));
        var sibling = file(directory("src/main/java-generated"), "Gen.java");

        assertInstanceOf(LintRunError.OutsideSourceRoots.class, failureOf(run.lint(sibling)));
    }

    @Test
    void summary_declaredTestRoot_countsItsFilesAsTestRootFiles() throws IOException {
        var main = directory("mod/src/main/java");
        var integration = directory("mod/src/it/java");
        var run = LintRun.lintRun(List.of(root(main, JbctMode.BUSINESS), root(integration, JbctMode.BUSINESS)),
                                  Set.of(integration));

        assertTrue(run.lint(file(main, "A.java")).isSuccess());
        assertTrue(run.lint(file(integration, "B.java")).isSuccess());
        assertTrue(run.lint(file(integration, "C.java")).isSuccess());

        assertEquals(List.of("mode=business root=" + normalized(integration) + " 2 files, 2 test-root files",
                             "mode=business root=" + normalized(main) + " 1 files"),
                     finished(run).summary());
    }

    @Test
    void summary_rootUnderADeclaredTestDirectory_isATestRoot() throws IOException {
        var declared = directory("mod/src/it");
        var below = directory("mod/src/it/java");
        var run = LintRun.lintRun(List.of(root(below, JbctMode.BUSINESS)), Set.of(declared));

        assertTrue(run.lint(file(below, "A.java")).isSuccess());

        assertEquals(List.of("mode=business root=" + normalized(below) + " 1 files, 1 test-root files"),
                     finished(run).summary());
    }

    @Test
    void summary_defaultPredicate_srcTestRelativeToTheModuleIsATestRoot() throws IOException {
        var module = directory("mod");
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        var test = directory("mod/src/test/java");
        var main = directory("mod/src/main/java");
        var run = LintRun.lintRun(List.of(root(test, JbctMode.BUSINESS), root(main, JbctMode.BUSINESS)));

        assertTrue(run.lint(file(test, "ATest.java")).isSuccess());
        assertTrue(run.lint(file(main, "A.java")).isSuccess());

        assertEquals(List.of("mode=business root=" + normalized(main) + " 1 files",
                             "mode=business root=" + normalized(test) + " 1 files, 1 test-root files"),
                     finished(run).summary());
    }

    @Test
    void summary_moduleCheckedOutUnderSrcTest_isNotATestRoot() throws IOException {
        var module = directory("checkout/src/test/repo/mod");
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        var main = directory("checkout/src/test/repo/mod/src/main/java");
        var run = LintRun.lintRun(List.of(root(main, JbctMode.BUSINESS)));

        assertTrue(run.lint(file(main, "A.java")).isSuccess());

        assertEquals(List.of("mode=business root=" + normalized(main) + " 1 files"), finished(run).summary());
    }

    @Test
    void summary_allFourModes_sayWhatTheRootDoesInStableOrder() throws IOException {
        var business = directory("a/src/main/java");
        var infrastructure = directory("b/src/main/java");
        var formatOnly = directory("c/src/main/java");
        var disabled = directory("d/src/main/java");
        var run = LintRun.lintRun(List.of(root(disabled, JbctMode.DISABLED),
                                          root(formatOnly, JbctMode.FORMAT_ONLY),
                                          root(infrastructure, JbctMode.INFRASTRUCTURE),
                                          root(business, JbctMode.BUSINESS)));

        assertTrue(run.lint(file(business, "A.java")).isSuccess());
        assertTrue(run.lint(file(infrastructure, "A.java")).isSuccess());
        assertTrue(run.lint(file(infrastructure, "B.java")).isSuccess());
        assertTrue(run.lint(file(formatOnly, "A.java")).isSuccess());
        assertTrue(run.lint(file(disabled, "A.java")).isSuccess());

        assertEquals(List.of("mode=business root=" + normalized(business) + " 1 files",
                             "mode=infrastructure root=" + normalized(infrastructure) + " 2 files: structure checks off",
                             "mode=format-only root=" + normalized(formatOnly) + " 1 files: format only, no rule run",
                             "mode=disabled root=" + normalized(disabled) + " 1 files: skipped, no format, no rule run"),
                     finished(run).summary());
    }

    @Test
    void finish_hasNoRunDiagnosticsWhileNoRunRuleExists() throws IOException {
        var business = directory("biz/src/main/java");
        var run = LintRun.lintRun(List.of(root(business, JbctMode.BUSINESS)));

        assertTrue(run.lint(file(business, "A.java")).isSuccess());

        assertTrue(finished(run).diagnostics().isEmpty());
        assertTrue(CstLinter.defaultRules().stream().noneMatch(RunRule.class::isInstance));
    }

    @Test
    void finish_twice_isRefusedAndLaterFilesAreRefused() throws IOException {
        var business = directory("biz/src/main/java");
        var run = LintRun.lintRun(List.of(root(business, JbctMode.BUSINESS)));

        assertTrue(run.finish().isSuccess());
        assertEquals(LintRunError.General.RUN_FINISHED, failureOf(run.finish()));
        assertEquals(LintRunError.General.RUN_FINISHED, failureOf(run.lint(file(business, "Late.java"))));
    }

    @Test
    void finish_withoutRoots_isAnEmptySummary() {
        assertTrue(finished(LintRun.lintRun(List.of())).summary().isEmpty());
    }
}
