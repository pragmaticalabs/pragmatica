package org.pragmatica.jbct.lint.cst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.jbct.config.ConfigError;
import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.cst.LintRun.RootCounts;
import org.pragmatica.jbct.lint.cst.LintRun.RunReport;
import org.pragmatica.jbct.lint.cst.RootResolver.ModeOverride;
import org.pragmatica.jbct.lint.cst.RootResolver.ModeSource;
import org.pragmatica.jbct.lint.cst.RootResolver.Request;
import org.pragmatica.jbct.lint.cst.RootResolver.ResolvedRoot;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// [RootResolver] and the plan-built [LintRun] (#2525, P2c): which root owns each file, that root's mode and where it
/// came from, what the run examined, formatted and skipped, and which roots fail. Every fixture is a temp directory
/// with its own `.git` marker, so no repository config leaks in; `user.home` is redirected for the same reason.
class RootResolverTest {
    private static final String ARCH_02 = "JBCT-ARCH-02";
    private static final String RET_03 = "JBCT-RET-03";
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
    private static final String BROKEN = "class {{{ not java";
    private static final String BUSINESS = "[lint]\nmode = \"business\"\n";
    private static final String INFRASTRUCTURE = "[lint]\nmode = \"infrastructure\"\n";
    private static final String FORMAT_ONLY = "[lint]\nmode = \"format-only\"\n";
    private static final String DISABLED = "[lint]\nmode = \"disabled\"\n";
    private static final String RET_03_AS_WARNING = "[lint.rules]\nJBCT-RET-03 = \"warning\"\n";

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

    private Path module(String name, String toml) throws IOException {
        var module = Files.createDirectories(temp.resolve(name));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        if (toml != null) {
            Files.writeString(module.resolve("jbct.toml"), toml);
        }
        return module;
    }

    private static Path source(Path module, String name, String content) throws IOException {
        var directory = Files.createDirectories(module.resolve("src/main/java/com/example"));

        return Files.writeString(directory.resolve(name), content);
    }

    private static RootPlan plan(Request request) {
        return RootResolver.resolve(request)
                           .fold(cause -> fail("resolve failed: " + cause.message()), plan -> plan);
    }

    private static RootPlan plan(Path... paths) {
        return plan(Request.request(List.of(paths)));
    }

    private static ResolvedRoot rootOf(RootPlan plan, Path path) {
        return plan.roots()
                   .stream()
                   .filter(root -> root.path().equals(path.toAbsolutePath().normalize()))
                   .findFirst()
                   .orElseGet(() -> fail("no root " + path + " in " + plan.roots().stream().map(ResolvedRoot::path).toList()));
    }

    private static Set<String> reported(Result<List<Diagnostic>> result) {
        return result.map(list -> list.stream()
                                      .map(Diagnostic::ruleId)
                                      .collect(Collectors.toSet()))
                     .or(Set.of("LINT-FAILED"));
    }

    private static RunReport finished(LintRun run) {
        return run.finish()
                  .fold(cause -> fail("finish failed: " + cause.message()), report -> report);
    }

    private static RootCounts countsOf(RunReport report, Path root) {
        return report.roots()
                     .stream()
                     .filter(counts -> counts.root().equals(root.toAbsolutePath().normalize()))
                     .findFirst()
                     .orElseGet(() -> fail("no counts for " + root));
    }

    @Test
    void resolve_oneDirectoryHoldingTwoModules_eachRootGetsItsOwnModeAndRules() throws IOException {
        var business = module("biz", BUSINESS);
        var infrastructure = module("infra", INFRASTRUCTURE);
        var businessFile = source(business, "Pricing.java", BOTH);
        var infrastructureFile = source(infrastructure, "Pricing.java", BOTH);
        var plan = plan(temp);
        var run = LintRun.lintRun(plan);

        assertEquals(2, plan.roots().size());
        assertEquals(JbctMode.BUSINESS, rootOf(plan, business.resolve("src/main/java")).mode());
        assertEquals(JbctMode.INFRASTRUCTURE, rootOf(plan, infrastructure.resolve("src/main/java")).mode());
        assertEquals(Set.of(ARCH_02, RET_03), reported(run.lintFile(businessFile)));
        assertEquals(Set.of(RET_03), reported(run.lintFile(infrastructureFile)));
    }

    @Test
    void resolve_aFileArgument_isOwnedByItsLayoutRoot_notByTheArgument() throws IOException {
        var business = module("biz", BUSINESS);
        var file = source(business, "Pricing.java", BOTH);
        var plan = plan(file);

        assertEquals(List.of(business.resolve("src/main/java").toAbsolutePath().normalize()),
                     plan.roots().stream().map(ResolvedRoot::path).toList());
        assertEquals(List.of(file.toAbsolutePath().normalize()), plan.files());
    }

    @Test
    void resolve_dotSlashArgumentAndItsSubdirectory_examineEachFileOnce() throws IOException {
        var x = Files.createDirectories(temp.resolve("x/sub"));

        Files.writeString(temp.resolve("x/A.java"), "class A {}\n");
        Files.writeString(x.resolve("B.java"), "class B {}\n");
        var plan = plan(temp.resolve("./x"), temp.resolve("x/../x/sub"));

        assertEquals(2, plan.files().size());
        assertEquals(2, Set.copyOf(plan.files()).size());
        assertEquals(List.of(temp.resolve("x/A.java")), rootOf(plan, temp.resolve("x")).files());
        assertEquals(List.of(temp.resolve("x/sub/B.java")), rootOf(plan, x).files());
    }

    @Test
    void resolve_nestedDeclaredRoots_theDeepestOwnsTheFile_andIdenticalRootsAreOne() throws IOException {
        var outer = Files.createDirectories(temp.resolve("outer"));
        var inner = Files.createDirectories(temp.resolve("outer/inner"));

        Files.writeString(outer.resolve("A.java"), "class A {}\n");
        Files.writeString(inner.resolve("B.java"), "class B {}\n");
        var nested = plan(Request.request(List.of(outer)).withDeclared(Set.of(outer, inner), Set.of()));
        var identical = plan(Request.request(List.of(outer)).withDeclared(Set.of(outer), Set.of(outer)));

        assertEquals(List.of(outer.resolve("A.java")), rootOf(nested, outer).files());
        assertEquals(List.of(inner.resolve("B.java")), rootOf(nested, inner).files());
        assertEquals(1, identical.roots().size());
        assertTrue(identical.roots().getFirst().testRoot());
    }

    @Test
    void run_formatOnlyRoot_isNeverParsedForLint_butIsFormatted() throws IOException {
        var formatOnly = module("fmt", FORMAT_ONLY);
        var business = module("biz", BUSINESS);
        var broken = source(formatOnly, "Broken.java", BROKEN);
        var control = source(business, "Broken.java", BROKEN);
        var run = LintRun.lintRun(plan(temp));

        assertEquals(Set.of(), reported(run.lintFile(broken)));
        assertEquals(Set.of("LINT-FAILED"), reported(run.lintFile(control)));
        assertTrue(run.claimFormat(broken).unwrap());

        var report = finished(run);
        var counts = countsOf(report, formatOnly.resolve("src/main/java"));

        assertEquals(0, counts.examined());
        assertEquals(1, counts.formatted());
        assertEquals(0, counts.skipped());
    }

    @Test
    void run_disabledRoot_isNeverOpened_neitherLinted_norFormatted() throws IOException {
        var disabled = module("off", DISABLED);
        var business = module("biz", BUSINESS);
        var unreadable = source(disabled, "Bytes.java", "");
        var control = source(business, "Bytes.java", "");

        Files.write(unreadable, new byte[] {(byte) 0xC3, (byte) 0x28});
        Files.write(control, new byte[] {(byte) 0xC3, (byte) 0x28});
        var run = LintRun.lintRun(plan(temp));

        assertEquals(Set.of(), reported(run.lintFile(unreadable)));
        assertFalse(run.claimFormat(unreadable).unwrap());
        assertEquals(Set.of("LINT-FAILED"), reported(run.lintFile(control)));

        var counts = countsOf(finished(run), disabled.resolve("src/main/java"));

        assertEquals(0, counts.examined());
        assertEquals(0, counts.formatted());
        assertEquals(1, counts.skipped());
    }

    @Test
    void run_skippedFiles_areNotCountedAsExamined_andNoRootRanARule() throws IOException {
        var disabled = module("off", DISABLED);
        var formatOnly = module("fmt", FORMAT_ONLY);
        var disabledFile = source(disabled, "A.java", BOTH);
        var formatOnlyFile = source(formatOnly, "A.java", BOTH);
        var run = LintRun.lintRun(plan(temp));

        assertTrue(run.lintFile(disabledFile).isSuccess());
        assertTrue(run.lintFile(formatOnlyFile).isSuccess());

        var report = finished(run);

        assertTrue(report.ranNoRule());
        assertEquals(List.of(0, 0), report.roots().stream().map(RootCounts::examined).toList());
        assertEquals(List.of(1, 1), report.roots().stream().map(RootCounts::skipped).toList());
        assertFalse(report.fails());
    }

    @Test
    void resolve_provenance_namesTheTomlTheOverrideOrTheDefault() throws IOException {
        var withToml = module("a", INFRASTRUCTURE);
        var noToml = module("b", null);

        source(withToml, "A.java", BOTH);
        source(noToml, "A.java", BOTH);
        var plain = plan(temp);
        var overridden = plan(Request.request(List.of(temp))
                                     .withModeOverride(Option.some(ModeOverride.modeOverride("disabled", "--mode"))));

        assertEquals(ModeSource.FromToml.fromToml(withToml.resolve("jbct.toml").toAbsolutePath().normalize()),
                     rootOf(plain, withToml.resolve("src/main/java")).modeSource());
        assertEquals(ModeSource.FromDefault.fromDefault(), rootOf(plain, noToml.resolve("src/main/java")).modeSource());
        assertEquals(ModeSource.FromOverride.fromOverride("--mode"),
                     rootOf(overridden, withToml.resolve("src/main/java")).modeSource());
        assertEquals(JbctMode.DISABLED, rootOf(overridden, withToml.resolve("src/main/java")).mode());
    }

    @Test
    void resolve_unknownModeOverride_isTheTypedConfigError() throws IOException {
        source(module("a", null), "A.java", BOTH);

        var result = RootResolver.resolve(Request.request(List.of(temp))
                                                 .withModeOverride(Option.some(ModeOverride.modeOverride("bogus", "--mode"))));

        assertTrue(result.fold(cause -> cause, plan -> fail("expected a refusal, got " + plan))
                         .stream()
                         .anyMatch(cause -> cause instanceof ConfigError.ModeUnknown));
    }

    @Test
    void resolve_unknownModeOverride_isRefusedEvenWhenTheTreeHoldsNoSourceFile() throws IOException {
        var result = RootResolver.resolve(Request.request(List.of(Files.createDirectories(temp.resolve("empty"))))
                                                 .withModeOverride(Option.some(ModeOverride.modeOverride("bogus", "--mode"))));

        assertTrue(result.fold(cause -> cause, plan -> fail("expected a refusal, got " + plan))
                         .stream()
                         .anyMatch(cause -> cause instanceof ConfigError.ModeUnknown));
    }

    @Test
    void finish_failOnWarning_isDecidedPerRootAndOverriddenByTheFlag() throws IOException {
        var strict = module("strict", INFRASTRUCTURE + "failOnWarning = true\n" + RET_03_AS_WARNING);
        var lenient = module("lenient", INFRASTRUCTURE + RET_03_AS_WARNING);
        var strictFile = source(strict, "A.java", BOTH);
        var lenientFile = source(lenient, "A.java", BOTH);
        var perRoot = LintRun.lintRun(plan(temp));
        var flagged = LintRun.lintRun(plan(Request.request(List.of(temp)).withFailOnWarning(true)));

        assertTrue(perRoot.lintFile(strictFile).isSuccess());
        assertTrue(perRoot.lintFile(lenientFile).isSuccess());
        assertTrue(flagged.lintFile(strictFile).isSuccess());
        assertTrue(flagged.lintFile(lenientFile).isSuccess());

        var perRootReport = finished(perRoot);
        var flaggedReport = finished(flagged);

        assertTrue(countsOf(perRootReport, strict.resolve("src/main/java")).fails());
        assertFalse(countsOf(perRootReport, lenient.resolve("src/main/java")).fails());
        assertTrue(perRootReport.failure().unwrap().contains(strict.resolve("src/main/java").toString() + ": 0 error(s), 1 warning(s)"));
        assertFalse(perRootReport.failure().unwrap().contains(lenient.resolve("src/main/java").toString()));
        assertTrue(countsOf(flaggedReport, lenient.resolve("src/main/java")).fails());
    }

    @Test
    void finish_errorsFailTheirRootWithoutAnyFlag() throws IOException {
        var business = module("biz", BUSINESS);
        var file = source(business, "A.java", BOTH);
        var run = LintRun.lintRun(plan(temp));

        assertTrue(run.lintFile(file).isSuccess());

        var report = finished(run);

        assertTrue(report.fails());
        assertTrue(report.failure().unwrap().contains(business.resolve("src/main/java").toString()));
    }

    @Test
    void summary_namesModeProvenanceBasePackageCountsAndTheTestRootRule() throws IOException {
        var business = module("biz", BUSINESS + "\n[project]\nbasePackage = \"com.example\"\n");
        var file = source(business, "A.java", BOTH);
        var run = LintRun.lintRun(plan(temp));

        assertTrue(run.lintFile(file).isSuccess());
        assertTrue(run.claimFormat(file).unwrap());

        var root = business.resolve("src/main/java").toAbsolutePath().normalize();

        assertEquals(List.of("mode=business (from " + business.resolve("jbct.toml").toAbsolutePath().normalize() + ") root=" + root
                             + " basePackage=com.example: 1 examined by rules, 1 formatted or format-checked, 0 skipped by mode"
                             + "; test-root rule: " + RootResolver.SRC_TEST_RULE),
                     finished(run).summary());
    }

    @Test
    void summary_declaredTestRoot_countsItsFilesAndNamesTheDeclaredRule() throws IOException {
        var integration = Files.createDirectories(temp.resolve("mod/src/it/java"));
        var file = Files.writeString(integration.resolve("B.java"), "class B {}\n");
        var run = LintRun.lintRun(plan(Request.request(List.of(integration)).withDeclared(Set.of(), Set.of(integration))));

        assertTrue(run.lintFile(file).isSuccess());

        var line = finished(run).summary().getFirst();

        assertTrue(line.contains(", 1 test-root files"), line);
        assertTrue(line.contains("test-root rule: " + RootResolver.DECLARED_TEST_RULE), line);
    }

    @Test
    void run_afterFinish_refusesClaimsAndFiles() throws IOException {
        var business = module("biz", BUSINESS);
        var file = source(business, "A.java", "class A {}\n");
        var run = LintRun.lintRun(plan(temp));

        assertTrue(run.finish().isSuccess());
        assertEquals(LintRunError.General.RUN_FINISHED,
                     run.claimFormat(file).fold(cause -> cause, value -> fail("expected a refusal")));
        assertEquals(LintRunError.General.RUN_FINISHED,
                     run.lintFile(file).fold(cause -> cause, value -> fail("expected a refusal")));
    }
}
