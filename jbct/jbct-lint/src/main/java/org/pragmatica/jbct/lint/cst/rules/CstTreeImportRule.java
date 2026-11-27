package org.pragmatica.jbct.lint.cst.rules;

import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.LintRule;
import org.pragmatica.jbct.lint.tree.PackageTree;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-TREE-01: no sideways import inside the package tree (#2526).
///
/// The tree is the source root's [PackageTree]: `[project] basePackage` and the `[lint.tree]` excludes. A file in
/// package `p` may reference package `q` when [PackageTree#allowed] says so: `q` is outside the tree, or one of the
/// two is an ancestor of the other, or `q` is an `io` or `shared` package whose prefix before its first role segment
/// is an ancestor of `p` (or `p` itself). Any other reference into the tree is sideways: one workflow reaching into a
/// sibling workflow. Every import and every fully-qualified type node counts, each cut to the package it points at
/// ([ArchSupport#collectTargets]), so a nested type is never read as a package.
///
/// Business mode only, and only for a root the run gave a tree: a test root, a root without `basePackage` and a root
/// whose tree the run refused get none, so the rule stays silent there. Severity ships at WARNING.
///
/// FN surface (syntax only): a sideways type reached through a simple name whose package is imported by a star import
/// of an allowed package; there is no transitive analysis.
public class CstTreeImportRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-TREE-01";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Set<JbctMode> modes() {
        return LintRule.BUSINESS_ONLY;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        var packageName = packageName(root);

        return ctx.shouldLint(packageName)
               ? ctx.tree()
                    .map(tree -> sideways(root, packageName, tree, ctx))
                    .or(Stream.<Diagnostic> empty())
               : Stream.empty();
    }

    private static Stream<Diagnostic> sideways(Cursor root, String filePackage, PackageTree tree, LintContext ctx) {
        return ArchSupport.collectTargets(root)
                          .stream()
                          .filter(reference -> !tree.allowed(filePackage,
                                                             reference.packageName()))
                          .map(reference -> createDiagnostic(reference, filePackage, ctx));
    }

    private static Diagnostic createDiagnostic(ArchSupport.Reference reference, String filePackage, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(reference.node()),
                                     startColumn(reference.node()),
                                     "Sideways import: package '" + filePackage
                                    + "' must not depend on sibling package '" + reference.packageName()
                                    + "'",
                                     "Depend on an ancestor or a descendant package, or on an io or shared package of a common "
                                    + "ancestor; move what both need up to their common ancestor.");
    }
}
