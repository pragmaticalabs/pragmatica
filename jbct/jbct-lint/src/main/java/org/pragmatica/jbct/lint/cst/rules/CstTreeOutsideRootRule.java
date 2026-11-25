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
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-TREE-02: a main file is declared under `[project] basePackage` (#2526).
///
/// A file whose package is neither the source root's [PackageTree#root] nor below it sits outside the tree every other
/// TREE judgement is made in: one finding per file, at its package declaration, naming the package and the root. A
/// package an `[lint.tree] exclude` glob matches is outside the tree on purpose and gets none.
///
/// Business mode only, and only for a root the run gave a tree: test roots are judged by no TREE rule, and a root
/// without `basePackage` or one the run refused gets none, so the rule stays silent there. Severity ships at WARNING.
public class CstTreeOutsideRootRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-TREE-02";

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
                    .filter(tree -> isOutside(tree, packageName))
                    .map(tree -> createDiagnostic(root, packageName, tree, ctx))
                    .stream()
               : Stream.empty();
    }

    private static boolean isOutside(PackageTree tree, String packageName) {
        return ! PackageTree.anc(tree.root(), packageName) && !tree.excluded(packageName);
    }

    private static Diagnostic createDiagnostic(Cursor root, String packageName, PackageTree tree, LintContext ctx) {
        var declaration = findFirst(root, RuleKind.PACKAGE_DECL).or(root);

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(declaration),
                                     startColumn(declaration),
                                     "Outside the package tree: " + described(packageName)
                                    + " is not under [project] basePackage '" + tree.root()
                                    + "'",
                                     "Move the file under '" + tree.root()
                                    + "', or list its package in [lint.tree] exclude if it is outside the tree on purpose.");
    }

    private static String described(String packageName) {
        return packageName.isEmpty()
               ? "the default package"
               : "package '" + packageName + "'";
    }
}
