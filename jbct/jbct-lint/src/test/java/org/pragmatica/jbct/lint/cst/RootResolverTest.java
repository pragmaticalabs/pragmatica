package org.pragmatica.jbct.lint.cst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.config.ConfigError;
import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Linted;
import org.pragmatica.jbct.lint.cst.RunOutcome.RootResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.Surface;
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
    private static final String NAM_07 = "JBCT-NAM-07";
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

    private static RunResult performed(RootPlan plan) {
        return LintRun.lintRun(plan)
                      .perform(Invocation.lint(Surface.CLI))
                      .fold(cause -> fail("perform failed: " + cause.message()), result -> result);
    }

    private static Set<String> reported(RunResult result, Path file) {
        return result.files()
                     .stream()
                     .filter(candidate -> candidate.file().equals(file.toAbsolutePath().normalize()))
                     .flatMap(candidate -> candidate.lint() instanceof Linted(var diagnostics)
                                           ? diagnostics.stream()
                                           : Stream.<Diagnostic> empty())
                     .map(Diagnostic::ruleId)
                     .collect(Collectors.toSet());
    }

    private static RootResult rootResult(RunResult result, Path root) {
        return result.roots()
                     .stream()
                     .filter(candidate -> candidate.root().equals(root.toAbsolutePath().normalize()))
                     .findFirst()
                     .orElseGet(() -> fail("no result for " + root));
    }

    @Test
    void resolve_oneDirectoryHoldingTwoModules_eachRootGetsItsOwnModeAndRules() throws IOException {
        var business = module("biz", BUSINESS);
        var infrastructure = module("infra", INFRASTRUCTURE);
        var businessFile = source(business, "Pricing.java", BOTH);
        var infrastructureFile = source(infrastructure, "Pricing.java", BOTH);
        var plan = plan(temp);
        var result = performed(plan);

        assertEquals(2, plan.roots().size());
        assertEquals(JbctMode.BUSINESS, rootOf(plan, business.resolve("src/main/java")).mode());
        assertEquals(JbctMode.INFRASTRUCTURE, rootOf(plan, infrastructure.resolve("src/main/java")).mode());
        assertEquals(Set.of(ARCH_02, RET_03, NAM_07), reported(result, businessFile));
        assertEquals(Set.of(RET_03), reported(result, infrastructureFile));
    }

    @Test
    void resolve_aPackageDirectoryArgumentInsideALayout_isOwnedByTheModulesLayoutRoot() throws IOException {
        var business = module("biz", BUSINESS);
        var file = source(business, "Pricing.java", BOTH);
        var plan = plan(file.getParent());

        assertEquals(List.of(business.resolve("src/main/java").toAbsolutePath().normalize()),
                     plan.roots().stream().map(ResolvedRoot::path).toList());
        assertEquals(List.of(file.toAbsolutePath().normalize()), plan.files());
    }

    @Test
    void resolve_twoPackageArgumentsOfOneModule_shareOneRoot() throws IOException {
        var business = module("biz", BUSINESS);
        var first = source(business, "Pricing.java", BOTH);
        var otherDirectory = Files.createDirectories(business.resolve("src/main/java/com/other"));
        var second = Files.writeString(otherDirectory.resolve("Other.java"), BOTH);
        var plan = plan(first.getParent(), otherDirectory);

        assertEquals(List.of(business.resolve("src/main/java").toAbsolutePath().normalize()),
                     plan.roots().stream().map(ResolvedRoot::path).toList());
        assertEquals(Set.of(first.toAbsolutePath().normalize(), second.toAbsolutePath().normalize()),
                     Set.copyOf(plan.files()));
    }

    @Test
    void resolve_aPackageDirectoryArgumentWithoutAPom_isItsOwnRoot() throws IOException {
        var plain = Files.createDirectories(temp.resolve("plain/src/main/java/com/example"));

        Files.writeString(plain.resolve("Pricing.java"), BOTH);
        var plan = plan(plain);

        assertEquals(List.of(plain.toAbsolutePath().normalize()),
                     plan.roots().stream().map(ResolvedRoot::path).toList());
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
    void resolve_declaredTestRootNotHandedOver_isCountedAsUncollectedTestFiles() throws IOException {
        var main = Files.createDirectories(temp.resolve("m/src/main/java/com/example"));
        var tests = Files.createDirectories(temp.resolve("m/src/test/java/com/example"));

        Files.writeString(main.resolve("A.java"), "class A {}\n");
        Files.writeString(tests.resolve("ATest.java"), "class ATest {}\n");
        Files.writeString(tests.resolve("BTest.java"), "class BTest {}\n");
        var mainRoot = temp.resolve("m/src/main/java");
        var testRoot = temp.resolve("m/src/test/java");
        var withoutTests = plan(Request.request(List.of(mainRoot)).withDeclared(Set.of(mainRoot), Set.of(testRoot)));
        var withTests = plan(Request.request(List.of(mainRoot, testRoot)).withDeclared(Set.of(mainRoot), Set.of(testRoot)));

        assertEquals(2, withoutTests.uncollectedTestFiles());
        assertEquals(1, withoutTests.files().size());
        assertEquals(0, withTests.uncollectedTestFiles());
    }

    @Test
    void resolve_dotSlashArgumentAndItsSubdirectory_examineEachFileOnce() throws IOException {
        var x = Files.createDirectories(temp.resolve("x/sub"));

        Files.writeString(temp.resolve("x/A.java"), "class A {}\n");
        Files.writeString(x.resolve("B.java"), "class B {}\n");
        var plan = plan(temp.resolve("./x"), temp.resolve("x/../x/sub"));

        assertEquals(2, plan.files().size());
        assertEquals(2, Set.copyOf(plan.files()).size());
        // printed form: the argument as given (the first argument's form for a file both reach)
        assertEquals(List.of(temp.resolve("./x/A.java")), rootOf(plan, temp.resolve("x")).files());
        assertEquals(List.of(temp.resolve("./x/sub/B.java")), rootOf(plan, x).files());
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
    void resolve_rootBelowADeclaredTestDirectory_isATestRoot() throws IOException {
        var declared = Files.createDirectories(temp.resolve("mod/src/it"));
        var below = Files.createDirectories(temp.resolve("mod/src/it/java"));

        Files.writeString(below.resolve("A.java"), "class A {}\n");
        var plan = plan(Request.request(List.of(below)).withDeclared(Set.of(below), Set.of(declared)));

        assertTrue(rootOf(plan, below).testRoot());
    }

    @Test
    void resolve_siblingDirectoryWithSharedPrefix_isNotInsideTheDeclaredRoot() throws IOException {
        var main = Files.createDirectories(temp.resolve("src/main/java"));
        var generated = Files.createDirectories(temp.resolve("src/main/java-generated"));
        var file = Files.writeString(generated.resolve("Gen.java"), "class Gen {}\n");
        var plan = plan(Request.request(List.of(generated)).withDeclared(Set.of(main), Set.of()));

        assertEquals(List.of(generated.toAbsolutePath().normalize()),
                     plan.roots().stream().filter(root -> root.files().contains(file)).map(ResolvedRoot::path).toList());
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
    void perform_failOnWarning_isDecidedPerRootAndOverriddenByTheFlag() throws IOException {
        var strict = module("strict", INFRASTRUCTURE + "failOnWarning = true\n" + RET_03_AS_WARNING);
        var lenient = module("lenient", INFRASTRUCTURE + RET_03_AS_WARNING);

        source(strict, "A.java", BOTH);
        source(lenient, "A.java", BOTH);

        var perRoot = performed(plan(temp));
        var flagged = performed(plan(Request.request(List.of(temp)).withFailOnWarning(true)));

        assertTrue(rootResult(perRoot, strict.resolve("src/main/java")).fails());
        assertFalse(rootResult(perRoot, lenient.resolve("src/main/java")).fails());
        assertTrue(rootResult(flagged, lenient.resolve("src/main/java")).fails());
    }

    @Test
    void perform_errorsFailTheirRootWithoutAnyFlag() throws IOException {
        var business = module("biz", BUSINESS);

        source(business, "A.java", BOTH);

        var result = performed(plan(temp));

        assertTrue(rootResult(result, business.resolve("src/main/java")).fails());
        assertEquals(RunOutcome.Kind.FINDINGS, result.verdict().kind());
    }

    @Test
    void perform_rootResult_namesModeProvenanceBasePackageCountsAndTheTestRootRule() throws IOException {
        var business = module("biz", BUSINESS + "\n[project]\nbasePackage = \"com.example\"\n");

        source(business, "A.java", BOTH);

        var root = rootResult(performed(plan(temp)), business.resolve("src/main/java"));

        assertEquals(JbctMode.BUSINESS, root.mode());
        assertEquals(Option.some("com.example"), root.basePackage());
        assertEquals(1, root.files());
        assertEquals(RootResolver.SRC_TEST_RULE, root.testRootRule());
        assertFalse(root.testRoot());
    }

    @Test
    void perform_declaredTestRoot_countsItsFilesAndNamesTheDeclaredRule() throws IOException {
        var integration = Files.createDirectories(temp.resolve("mod/src/it/java"));

        Files.writeString(integration.resolve("B.java"), "class B {}\n");

        var root = rootResult(performed(plan(Request.request(List.of(integration)).withDeclared(Set.of(), Set.of(integration)))),
                              integration);

        assertTrue(root.testRoot());
        assertEquals(1, root.files());
        assertEquals(RootResolver.DECLARED_TEST_RULE, root.testRootRule());
    }
}
