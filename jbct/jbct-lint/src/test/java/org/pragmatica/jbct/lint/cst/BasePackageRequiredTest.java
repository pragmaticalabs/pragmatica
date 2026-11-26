package org.pragmatica.jbct.lint.cst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.jbct.config.ConfigError;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.cst.RootResolver.Request;
import org.pragmatica.jbct.lint.cst.RootResolver.ResolvedRoot;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Kind;
import org.pragmatica.jbct.lint.cst.RunOutcome.Level;
import org.pragmatica.jbct.lint.cst.RunOutcome.Line;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.Surface;
import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// The refusal of a source root the TREE rules cannot judge (#2526), and the per-run coverage line of each TREE rule.
///
/// A root reads `[project] basePackage` when it is a main root with a collected file whose mode runs a TREE rule that
/// `[lint.rules]` leaves on. Such a root is refused when the key is absent ([ConfigError.BasePackageRequired]), has an
/// `io` or `shared` segment after its first, or covers no main file; the TREE rules do not run for it, its code rules
/// still run and report, the verdict is INCOMPLETE (CLI exit 2) and the failure carries the refusal text, whose
/// suggestion is the common package prefix of all main files of the root. Every fixture is a temp directory with its
/// own `.git` marker; `user.home` is redirected so no user config leaks in.
class BasePackageRequiredTest {
    private static final String TREE_01 = "JBCT-TREE-01";
    private static final String TREE_02 = "JBCT-TREE-02";
    private static final String RET_03 = "JBCT-RET-03";
    private static final String BUSINESS = "[lint]\nmode = \"business\"\n";
    private static final String NULL_RETURN = """
        package com.example.order.place;
        import com.example.order.cancel.Refund;
        class PlaceOrder {
            String name() {
                return null;
            }
        }
        """;
    private static final String SIBLING = """
        package com.example.order.cancel;
        class Refund {
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

    private Path module(String toml) throws IOException {
        var module = Files.createDirectories(temp.resolve("app"));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"), toml);
        return module;
    }

    private static Path source(Path module, String kind, String packageName, String name, String content)
    throws IOException {
        var directory = Files.createDirectories(module.resolve("src/" + kind + "/java/" + packageName.replace('.', '/')));

        return Files.writeString(directory.resolve(name), content);
    }

    private static Path mainSources(Path module) throws IOException {
        source(module, "main", "com.example.order.place", "PlaceOrder.java", NULL_RETURN);
        source(module, "main", "com.example.order.cancel", "Refund.java", SIBLING);
        return module.resolve("src/main/java");
    }

    private static RootPlan plan(Path... paths) {
        return RootResolver.resolve(Request.request(List.of(paths)))
                           .fold(cause -> fail("resolve failed: " + cause.message()), plan -> plan);
    }

    private static RunResult performed(RootPlan plan, Invocation invocation) {
        return LintRun.lintRun(plan)
                      .perform(invocation)
                      .fold(cause -> fail("perform failed: " + cause.message()), result -> result);
    }

    private static RunResult linted(Path... paths) {
        return performed(plan(paths), Invocation.lint(Surface.CLI));
    }

    private static Set<String> ruleIds(RunResult result) {
        return result.findings()
                     .stream()
                     .map(Diagnostic::ruleId)
                     .collect(Collectors.toSet());
    }

    private static Set<String> treeRuleIds(RunResult result) {
        return ruleIds(result).stream()
                              .filter(id -> id.startsWith("JBCT-TREE-"))
                              .collect(Collectors.toSet());
    }

    private static List<String> texts(RunResult result, Level level) {
        return result.before()
                     .stream()
                     .filter(line -> line.level() == level)
                     .map(Line::text)
                     .toList();
    }

    private static Cause refusalOf(RootPlan plan) {
        return plan.roots()
                   .stream()
                   .flatMap(root -> root.treeRefusal()
                                        .stream())
                   .findFirst()
                   .orElseGet(() -> fail("no root refused in " + plan.roots().stream().map(ResolvedRoot::path).toList()));
    }

    private static void assertNoRefusal(RootPlan plan) {
        assertTrue(plan.roots()
                       .stream()
                       .allMatch(root -> root.treeRefusal()
                                             .isEmpty()),
                   () -> "unexpected refusal: " + plan.roots().stream().map(ResolvedRoot::treeRefusal).toList());
    }

    private static void assertRefusedRun(RunResult result, Cause refusal) {
        assertEquals(Kind.INCOMPLETE, result.verdict().kind());
        assertTrue(result.verdict()
                         .failure()
                         .or("")
                         .contains(refusal.message()),
                   () -> "failure " + result.verdict().failure() + " does not carry " + refusal.message());
        assertTrue(texts(result, Level.ERROR).contains(refusal.message()), () -> "no ERROR line in " + result.before());
        assertTrue(ruleIds(result).contains(RET_03), () -> "code rules still run and report: " + ruleIds(result));
        assertEquals(Set.of(), treeRuleIds(result), "TREE rules do not run for a refused root");
    }

    @Test
    void absentBasePackage_refusesTheRoot_withTheCommonPrefixOfAllMainFiles() throws IOException {
        var root = mainSources(module(BUSINESS));
        var plan = plan(root);
        var refusal = refusalOf(plan);
        var result = performed(plan, Invocation.lint(Surface.CLI));

        assertInstanceOf(ConfigError.BasePackageRequired.class, refusal);
        assertEquals("com.example.order", ((ConfigError.BasePackageRequired) refusal).suggestion());
        assertRefusedRun(result, refusal);
        assertTrue(texts(result, Level.INFO).contains(TREE_01 + ": judged 0 of 2 file(s)"), () -> "" + result.before());
    }

    @Test
    void basePackageWithARoleSegment_refusesTheRoot() throws IOException {
        var root = mainSources(module(BUSINESS + "[project]\nbasePackage = \"com.shared.order\"\n"));
        var plan = plan(root);
        var refusal = refusalOf(plan);

        assertInstanceOf(ConfigError.BasePackageHasRole.class, refusal);
        assertTrue(refusal.message().contains("set basePackage = \"com.example.order\""), refusal.message());
        assertRefusedRun(performed(plan, Invocation.lint(Surface.CLI)), refusal);
    }

    @Test
    void basePackageCoveringNoMainFile_refusesTheRoot() throws IOException {
        var root = mainSources(module(BUSINESS + "[project]\nbasePackage = \"org.elsewhere\"\n"));
        var plan = plan(root);
        var refusal = refusalOf(plan);

        assertInstanceOf(ConfigError.BasePackageCoversNoFile.class, refusal);
        assertTrue(refusal.message().contains("set basePackage = \"com.example.order\""), refusal.message());
        assertRefusedRun(performed(plan, Invocation.lint(Surface.CLI)), refusal);
    }

    /// F2 (#3738): the base package covers a file by package ancestry, never by text: `com.shop` does not cover
    /// `com.shopping.order`.
    @Test
    void basePackageThatIsOnlyATextualPrefixOfTheFiles_refusesTheRoot() throws IOException {
        var module = module(BUSINESS + "[project]\nbasePackage = \"com.shop\"\n");

        source(module, "main", "com.shopping.order", "Order.java", "package com.shopping.order;\nclass Order {\n}\n");
        var refusal = refusalOf(plan(module.resolve("src/main/java")));

        assertInstanceOf(ConfigError.BasePackageCoversNoFile.class, refusal);
        assertEquals("com.shopping.order", ((ConfigError.BasePackageCoversNoFile) refusal).suggestion());
    }

    /// F3 (#3738): a declared main root that holds no file reads no base package, so it is not refused beside a populated
    /// root of the same module; the premise (two roots, one of them file-less) is asserted first.
    @Test
    void declaredRootWithoutFiles_isNotRefused_besideAPopulatedRoot() throws IOException {
        var module = module(BUSINESS + "[project]\nbasePackage = \"com.example\"\n");
        var populated = mainSources(module);
        var spare = Files.createDirectories(module.resolve("src/main/spare"));
        var plan = RootResolver.resolve(Request.request(List.of(populated, spare)).withDeclared(Set.of(populated, spare), Set.of()))
                               .fold(cause -> fail("resolve failed: " + cause.message()), resolved -> resolved);

        assertEquals(2, plan.roots().size());
        assertEquals(1, plan.roots().stream().filter(root -> root.files().isEmpty()).count());
        assertNoRefusal(plan);
    }

    /// F4 (#3738): a run both refused and holding an unparseable file fails with the refusal AND the parse failure.
    @Test
    void refusalAndUnparseableFile_bothReachTheFailureText() throws IOException {
        var module = module(BUSINESS);

        source(module, "main", "com.example.order.place", "Broken.java", "package com.example.order.place;\nclass Broken {{{\n");
        source(module, "main", "com.example.order.cancel", "Refund.java", SIBLING);
        var plan = plan(module.resolve("src/main/java"));
        var refusal = refusalOf(plan);
        var failure = performed(plan, Invocation.lint(Surface.CLI)).verdict().failure().or("");

        assertTrue(failure.contains(refusal.message()), () -> "refusal missing from: " + failure);
        assertTrue(failure.contains("could not be read or parsed and were NOT analysed"), () -> "parse failure missing from: " + failure);
    }

    /// F6 (#3738): the suggested base package is the common prefix segment by segment; segments equal at a later index
    /// do not extend it: `a.b.c` and `a.x.c` suggest `a`.
    @Test
    void suggestion_stopsAtTheFirstSegmentTheFilesDisagreeOn() throws IOException {
        var module = module(BUSINESS);

        source(module, "main", "a.b.c", "One.java", "package a.b.c;\nclass One {\n}\n");
        source(module, "main", "a.x.c", "Two.java", "package a.x.c;\nclass Two {\n}\n");
        var refusal = refusalOf(plan(module.resolve("src/main/java")));

        assertInstanceOf(ConfigError.BasePackageRequired.class, refusal);
        assertEquals("a", ((ConfigError.BasePackageRequired) refusal).suggestion());
    }

    /// F7 (#3738): `[lint.rules]` turning one TREE rule off gives `0 of N` for that rule only.
    @Test
    void treeRuleSetOff_judgesNoFile_whileTheOtherTreeRuleStillJudgesAll() throws IOException {
        var root = mainSources(module(BUSINESS + "[project]\nbasePackage = \"com.example\"\n[lint.rules]\nJBCT-TREE-01 = \"off\"\n"));
        var result = linted(root);

        assertEquals(List.of(TREE_01 + ": judged 0 of 2 file(s)", TREE_02 + ": judged 2 of 2 file(s)"),
                     texts(result, Level.INFO).stream().filter(text -> text.startsWith("JBCT-TREE-")).toList());
    }

    /// F8 (#3738): a file in an `excludePackages` package is in N and never in K.
    @Test
    void fileInAnExcludedPackage_countsInTheTotalButNotAsJudged() throws IOException {
        var root = mainSources(module("[lint]\nmode = \"business\"\nexcludePackages = [\"com.example.order.cancel\"]\n[project]\nbasePackage = \"com.example\"\n"));
        var result = linted(root);

        assertEquals(List.of(TREE_01 + ": judged 1 of 2 file(s)", TREE_02 + ": judged 1 of 2 file(s)"),
                     texts(result, Level.INFO).stream().filter(text -> text.startsWith("JBCT-TREE-")).toList());
    }

    @Test
    void usableBasePackage_runsTheTreeRules_andPrintsTheirCoverage() throws IOException {
        var root = mainSources(module(BUSINESS + "[project]\nbasePackage = \"com.example\"\n"));
        var plan = plan(root);
        var result = performed(plan, Invocation.lint(Surface.CLI));

        assertNoRefusal(plan);
        assertTrue(ruleIds(result).contains(RET_03), () -> "" + ruleIds(result));
        assertEquals(Set.of(TREE_01), treeRuleIds(result));
        assertEquals(List.of(TREE_01 + ": judged 2 of 2 file(s)", TREE_02 + ": judged 2 of 2 file(s)"),
                     texts(result, Level.INFO).stream().filter(text -> text.startsWith("JBCT-TREE-")).toList());
        assertTrue(result.verdict().kind() != Kind.INCOMPLETE, () -> "" + result.verdict());
    }

    @Test
    void infrastructureRoot_neverReadsTheKey_andItsCoverageIsZero() throws IOException {
        var root = mainSources(module("[lint]\nmode = \"infrastructure\"\n"));
        var plan = plan(root);
        var result = performed(plan, Invocation.lint(Surface.CLI));

        assertNoRefusal(plan);
        assertTrue(ruleIds(result).contains(RET_03), () -> "" + ruleIds(result));
        assertEquals(Set.of(), treeRuleIds(result));
        assertEquals(List.of(TREE_01 + ": judged 0 of 2 file(s)", TREE_02 + ": judged 0 of 2 file(s)"),
                     texts(result, Level.INFO).stream().filter(text -> text.startsWith("JBCT-TREE-")).toList());
    }

    @Test
    void formatOnlyAndDisabledRoots_neverReadTheKey() throws IOException {
        var formatOnly = mainSources(module("[lint]\nmode = \"format-only\"\n"));

        assertNoRefusal(plan(formatOnly));
        Files.writeString(temp.resolve("app/jbct.toml"), "[lint]\nmode = \"disabled\"\n");
        assertNoRefusal(plan(formatOnly));
    }

    @Test
    void bothTreeRulesOff_theRootDoesNotReadTheKey() throws IOException {
        var root = mainSources(module(BUSINESS + "[lint.rules]\nJBCT-TREE-01 = \"off\"\nJBCT-TREE-02 = \"off\"\n"));

        assertNoRefusal(plan(root));
    }

    @Test
    void oneTreeRuleLeftOn_stillReadsTheKey() throws IOException {
        var root = mainSources(module(BUSINESS + "[lint.rules]\nJBCT-TREE-01 = \"off\"\n"));

        assertInstanceOf(ConfigError.BasePackageRequired.class, refusalOf(plan(root)));
    }

    /// Test roots are judged by no TREE rule: a business test root without the key is not refused, and its files count
    /// in N but never in K.
    @Test
    void testRoot_isNeitherRefusedNorJudged() throws IOException {
        var module = module(BUSINESS);

        source(module, "test", "org.stray", "StrayTest.java", """
            package org.stray;
            import org.stray.other.Thing;
            class StrayTest {
            }
            """);
        var plan = plan(module.resolve("src/test/java"));
        var result = performed(plan, Invocation.lint(Surface.CLI));

        assertNoRefusal(plan);
        assertEquals(Set.of(), treeRuleIds(result));
        assertTrue(texts(result, Level.INFO).contains(TREE_02 + ": judged 0 of 1 file(s)"), () -> "" + result.before());
        assertTrue(result.verdict().kind() != Kind.INCOMPLETE, () -> "" + result.verdict());
    }

    /// A test root whose module declares the key still gets no tree: the sideways import of its test file is no finding
    /// and the file counts in N, never in K.
    @Test
    void testRoot_withABasePackage_isJudgedByNoTreeRule() throws IOException {
        var module = module(BUSINESS + "[project]\nbasePackage = \"com.example\"\n");

        source(module, "test", "com.example.order.place", "PlaceOrderTest.java", NULL_RETURN.replace("class PlaceOrder", "class PlaceOrderTest"));
        var result = linted(module.resolve("src/test/java"));

        assertEquals(Set.of(), treeRuleIds(result));
        assertTrue(texts(result, Level.INFO).contains(TREE_01 + ": judged 0 of 1 file(s)"), () -> "" + result.before());
    }

    /// The coverage count K is the files the rule READ: a file the run could not parse is in N and not in K.
    @Test
    void unparseableFile_countsInTheTotalButNotAsJudged() throws IOException {
        var module = module(BUSINESS + "[project]\nbasePackage = \"com.example\"\n");

        source(module, "main", "com.example.order.place", "Broken.java", "package com.example.order.place;\nclass Broken {{{\n");
        source(module, "main", "com.example.order.cancel", "Refund.java", SIBLING);
        var result = linted(module.resolve("src/main/java"));

        assertTrue(texts(result, Level.INFO).contains(TREE_01 + ": judged 1 of 2 file(s)"), () -> "" + result.before());
    }

    /// A run that does not lint runs no TREE rule: no refusal fails it and no coverage line is printed.
    @Test
    void formatRun_isNotFailedByTheRefusal() throws IOException {
        var root = mainSources(module(BUSINESS));
        var result = performed(plan(root), Invocation.formatCheck(Surface.CLI));

        assertTrue(result.verdict().kind() != Kind.INCOMPLETE, () -> "" + result.verdict());
        assertEquals(List.of(), texts(result, Level.ERROR));
        assertTrue(result.before().stream().noneMatch(line -> line.text().startsWith("JBCT-TREE-")), () -> "" + result.before());
    }
}
