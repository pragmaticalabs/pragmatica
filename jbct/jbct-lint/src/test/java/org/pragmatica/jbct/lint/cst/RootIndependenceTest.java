package org.pragmatica.jbct.lint.cst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.cst.RootResolver.Request;
import org.pragmatica.jbct.lint.cst.RootResolver.ResolvedRoot;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Kind;
import org.pragmatica.jbct.lint.cst.RunOutcome.Line;
import org.pragmatica.jbct.lint.cst.RunOutcome.Linted;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.Surface;
import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// The TREE judgement is a function of the source root, never of what else the run holds (#2526): one file's TREE
/// findings and its root's refusal (text and suggestion included) are the same linted alone or with its module, and
/// one root's refusal leaves another root of the same run judged.
class RootIndependenceTest {
    private static final String BUSINESS = "[lint]\nmode = \"business\"\n";
    private static final String ROOTED = BUSINESS + "[project]\nbasePackage = \"com.example\"\n";
    private static final String PLACE = """
        package com.example.order.place;
        import com.example.order.cancel.Refund;
        class PlaceOrder {
        }
        """;
    private static final String CANCEL = """
        package com.example.order.cancel;
        class Refund {
        }
        """;
    private static final String STRAY = """
        package org.stray;
        class Stray {
        }
        """;

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
        Files.writeString(module.resolve("jbct.toml"), toml);
        return module;
    }

    private static Path source(Path module, String packageName, String name, String content) throws IOException {
        var directory = Files.createDirectories(module.resolve("src/main/java/" + packageName.replace('.', '/')));

        return Files.writeString(directory.resolve(name), content);
    }

    private static RootPlan plan(Path... paths) {
        return RootResolver.resolve(Request.request(List.of(paths)))
                           .fold(cause -> fail("resolve failed: " + cause.message()), plan -> plan);
    }

    private static RunResult linted(RootPlan plan) {
        return LintRun.lintRun(plan)
                      .perform(Invocation.lint(Surface.CLI))
                      .fold(cause -> fail("perform failed: " + cause.message()), result -> result);
    }

    /// The TREE findings of one file, as `rule:line`.
    private static List<String> treeFindings(RunResult result, Path file) {
        return result.files()
                     .stream()
                     .filter(candidate -> candidate.file().equals(file.toAbsolutePath().normalize()))
                     .flatMap(candidate -> candidate.lint() instanceof Linted(var diagnostics)
                                           ? diagnostics.stream()
                                           : Stream.<Diagnostic> empty())
                     .filter(diagnostic -> diagnostic.ruleId().startsWith("JBCT-TREE-"))
                     .map(diagnostic -> diagnostic.ruleId() + ":" + diagnostic.line())
                     .sorted()
                     .toList();
    }

    private static List<String> refusals(RootPlan plan) {
        return plan.roots()
                   .stream()
                   .flatMap(root -> root.treeRefusal()
                                        .stream())
                   .map(Cause::message)
                   .toList();
    }

    private static ResolvedRoot rootOf(RootPlan plan, Path path) {
        return plan.roots()
                   .stream()
                   .filter(root -> root.path().equals(path.toAbsolutePath().normalize()))
                   .findFirst()
                   .orElseGet(() -> fail("no root " + path + " in " + plan.roots().stream().map(ResolvedRoot::path).toList()));
    }

    @Test
    void fileLintedAlone_getsTheTreeFindingsItGetsWithItsModule() throws IOException {
        var module = module("app", ROOTED);
        var place = source(module, "com.example.order.place", "PlaceOrder.java", PLACE);
        var stray = source(module, "org.stray", "Stray.java", STRAY);

        source(module, "com.example.order.cancel", "Refund.java", CANCEL);
        var whole = linted(plan(module));

        assertEquals(List.of("JBCT-TREE-01:2"), treeFindings(whole, place));
        assertEquals(List.of("JBCT-TREE-02:1"), treeFindings(whole, stray));
        assertEquals(treeFindings(whole, place), treeFindings(linted(plan(place)), place));
        assertEquals(treeFindings(whole, stray), treeFindings(linted(plan(stray)), stray));
    }

    /// The refusal reads ALL main files of the root on disk, so the file linted alone gets the same refusal, its
    /// suggestion the common prefix of the whole root (`com.example.order`), not of the one file.
    @Test
    void fileLintedAlone_getsTheRefusalAndSuggestionOfItsWholeRoot() throws IOException {
        var module = module("app", BUSINESS);
        var place = source(module, "com.example.order.place", "PlaceOrder.java", PLACE);

        source(module, "com.example.order.cancel", "Refund.java", CANCEL);
        var whole = plan(module);
        var alone = plan(place);

        assertEquals(1, refusals(whole).size());
        assertEquals(refusals(whole), refusals(alone));
        assertTrue(refusals(alone).getFirst().contains("\"com.example.order\""), refusals(alone).getFirst());
        assertEquals(Kind.INCOMPLETE, linted(alone).verdict().kind());
    }

    /// The refusal is per source root: a module without `basePackage` is refused, its neighbour in the same run is
    /// still judged, and the coverage line counts only the judged root's files.
    @Test
    void refusedRoot_leavesTheOtherRootOfTheRunJudged() throws IOException {
        var rooted = module("rooted", ROOTED);
        var bare = module("bare", BUSINESS);
        var place = source(rooted, "com.example.order.place", "PlaceOrder.java", PLACE);
        var barePlace = source(bare, "com.example.order.place", "PlaceOrder.java", PLACE);

        source(rooted, "com.example.order.cancel", "Refund.java", CANCEL);
        var plan = plan(temp);
        var result = linted(plan);

        assertTrue(rootOf(plan, rooted.resolve("src/main/java")).treeRefusal().isEmpty());
        assertFalse(rootOf(plan, bare.resolve("src/main/java")).treeRefusal().isEmpty());
        assertEquals(List.of("JBCT-TREE-01:2"), treeFindings(result, place));
        assertEquals(List.of(), treeFindings(result, barePlace));
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
        assertTrue(result.before()
                         .stream()
                         .map(Line::text)
                         .anyMatch("JBCT-TREE-01: judged 2 of 3 file(s)"::equals),
                   () -> "" + result.before());
    }
}
