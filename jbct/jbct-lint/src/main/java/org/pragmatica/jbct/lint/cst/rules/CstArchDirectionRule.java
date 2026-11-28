package org.pragmatica.jbct.lint.cst.rules;

import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.LintRule;
import org.pragmatica.jbct.lint.layer.Layer;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-ARCH-01: a `domain` file does not depend on a framework package.
///
/// The domain layer stays pure: this rule classifies the file's package (see
/// [org.pragmatica.jbct.lint.layer.LayerClassifier]) and flags a reference — through an import or a
/// fully-qualified type node (see [ArchSupport]) — to a framework package (Spring, Jackson, jOOQ, JPA,
/// ...) from a file in the `domain` layer. Frameworks are third-party by definition, so no root-group
/// gate applies.
///
/// The layer-rank direction check (`domain <- application <- adapter <- bootstrap`) is retired: the
/// package tree rule JBCT-TREE-01 judges sideways imports from `[project] basePackage` instead (#2526).
/// The rule is silent when the file's own package is unclassifiable (never guess-flag).
///
/// Lineage: this rule absorbs the layering concern that [CstDomainIoRule] (JBCT-MIX-01) covers for
/// JDK I/O. MIX-01 keeps its own ID/severity and its precise `java.io` / `java.nio` / ... catalog;
/// ARCH-01's framework set is disjoint from it, so the two are complementary, not redundant.
///
/// FP surface: a same-root-group project package whose segment happens to be a layer keyword without
/// layering intent. FN surface (syntax only, no cross-file resolution): a framework reached through a
/// star import or a simple name whose package is not otherwise referenced; a framework not in the
/// curated prefix set. There is no transitive-dependency analysis.
public class CstArchDirectionRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ARCH-01";

    /// Framework / infrastructure package prefixes a `domain` file must not depend on. Curated and
    /// deliberately disjoint from [CstDomainIoRule]'s JDK-I/O catalog (which JBCT-MIX-01 owns).
    private static final Set<String> FRAMEWORK_PREFIXES = Set.of("org.springframework",
                                                                 "jakarta.persistence",
                                                                 "jakarta.ws",
                                                                 "jakarta.servlet",
                                                                 "javax.persistence",
                                                                 "javax.servlet",
                                                                 "com.fasterxml.jackson",
                                                                 "org.jooq",
                                                                 "org.hibernate",
                                                                 "reactor.core",
                                                                 "io.micrometer");

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

        if (!ctx.shouldLint(packageName)) {
            return Stream.empty();
        }

        return ctx.layers()
                  .layerOf(packageName)
                  .map(fileLayer -> diagnosticsFor(root, fileLayer, ctx))
                  .or(Stream.<Diagnostic> empty());
    }

    private Stream<Diagnostic> diagnosticsFor(Cursor root, Layer fileLayer, LintContext ctx) {
        var diagnostics = Stream.<Diagnostic> builder();

        for (var reference : ArchSupport.collectReferences(root)) {
            violationReason(fileLayer, reference.packageName()).onPresent(reason -> diagnostics.add(createDiagnostic(reference.node(),
                                                                                                                     reason,
                                                                                                                     ctx)));
        }

        return diagnostics.build();
    }

    private Option<String> violationReason(Layer fileLayer, String referencedPackage) {
        if (fileLayer == Layer.DOMAIN && isFramework(referencedPackage)) {
            return Option.some("domain must not depend on framework package '" + referencedPackage + "'");
        }

        return Option.none();
    }

    private boolean isFramework(String referencedPackage) {
        for (var prefix : FRAMEWORK_PREFIXES) {
            if (referencedPackage.equals(prefix) || referencedPackage.startsWith(prefix + ".")) {
                return true;
            }
        }

        return false;
    }

    private Diagnostic createDiagnostic(Cursor node, String reason, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "Illegal layer dependency: " + reason,
                                     "Keep the domain free of frameworks: depend on a port the adapter implements.");
    }
}
