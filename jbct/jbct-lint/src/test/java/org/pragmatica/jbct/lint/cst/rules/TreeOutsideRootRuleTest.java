package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.lint.tree.PackageTree;
import org.pragmatica.jbct.lint.tree.TreeConfig;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-TREE-02 (#2526): a main file is declared under `[project] basePackage`. One finding per file, at its package
/// declaration, naming the package and the root; none for an excluded package. That test roots are judged by no TREE
/// rule is a fact of the run (the resolver gives a test root no tree): [org.pragmatica.jbct.lint.cst.BasePackageRequiredTest].
class TreeOutsideRootRuleTest {
    private static final String RULE_ID = "JBCT-TREE-02";

    private static LintContext rooted(String root, String... excludes) {
        return LintContext.defaultContext()
                          .withTree(Option.some(PackageTree.packageTree(root,
                                                                        TreeConfig.treeConfig(List.of(excludes)))));
    }

    private static List<Diagnostic> findings(LintContext context, String source) {
        return CstLinter.cstLinter(context)
                        .lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                        .onFailure(cause -> fail("Parse failed: " + cause.message()))
                        .or(List.of())
                        .stream()
                        .filter(diagnostic -> diagnostic.ruleId()
                                                        .equals(RULE_ID))
                        .toList();
    }

    @Test
    void fileOutsideTheRoot_isFlaggedOnce_atItsPackageDeclaration_namingPackageAndRoot() {
        var found = findings(rooted("conduit"), """
            package legacy.util;
            class A {}
            class B {}
            """);

        assertEquals(1, found.size());
        assertEquals(1, found.getFirst().line());
        assertEquals(DiagnosticSeverity.WARNING, found.getFirst().severity());
        assertTrue(found.getFirst().message().contains("package 'legacy.util'"), found.getFirst().message());
        assertTrue(found.getFirst().message().contains("'conduit'"), found.getFirst().message());
    }

    @Test
    void defaultPackageFile_isFlagged() {
        var found = findings(rooted("conduit"), """
            class Main {}
            """);

        assertEquals(1, found.size());
        assertTrue(found.getFirst().message().contains("the default package"), found.getFirst().message());
    }

    /// A textual prefix is no ancestor: `com.shopping` is outside the root `com.shop`.
    @Test
    void textualPrefixOfTheRoot_isFlagged() {
        assertEquals(1, findings(rooted("com.shop"), """
            package com.shopping;
            class Cart {}
            """).size());
    }

    @Test
    void rootItselfAndBelow_areClean() {
        assertEquals(List.of(), findings(rooted("conduit"), """
            package conduit;
            class Conduit {}
            """));
        assertEquals(List.of(), findings(rooted("conduit"), """
            package conduit.login.io;
            class Store {}
            """));
    }

    @Test
    void excludedPackageOutsideTheRoot_isClean() {
        assertEquals(List.of(), findings(rooted("conduit", "legacy.**"), """
            package legacy.util;
            class A {}
            """));
    }

    @Test
    void contextWithoutTree_isSilent() {
        assertEquals(List.of(), findings(LintContext.defaultContext(), """
            package legacy.util;
            class A {}
            """));
    }

    @Test
    void infrastructureMode_doesNotRunTheRule() {
        assertEquals(List.of(), findings(rooted("conduit").withProject(JbctMode.INFRASTRUCTURE, Option.some("conduit")), """
            package legacy.util;
            class A {}
            """));
    }

    @Test
    void excludePackagesOfTheFile_isSilent() {
        assertEquals(List.of(), findings(rooted("conduit").withExcludePackages(List.of("legacy.util")), """
            package legacy.util;
            class A {}
            """));
    }
}
