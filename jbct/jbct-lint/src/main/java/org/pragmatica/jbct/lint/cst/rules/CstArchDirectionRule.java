package org.pragmatica.jbct.lint.cst.rules;

import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.layer.Layer;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-ARCH-01: dependency direction — imports point up only.
///
/// The book layering rule is `domain <- application <- adapter <- bootstrap`: a file may depend on
/// its own layer and every lower-ranked layer, never a higher one. This rule classifies the file's
/// package (see [org.pragmatica.jbct.lint.layer.LayerClassifier]) and each package it references —
/// through an import or a fully-qualified type node (see [ArchSupport]) — and flags a reference whose
/// target layer outranks the file's. In addition, a `domain` file may not depend on a framework
/// package (Spring, Jackson, jOOQ, JPA, ...), because the domain layer must stay pure.
///
/// **Root-group gate (false-positive guard):** the layer-rank check applies only when the referenced
/// package shares the file's own root group — the first two dotted segments (`com.example`). A
/// cross-group reference is third-party by definition for layering, so a coincidental layer keyword
/// in a third-party package (`org.springframework.integration.*`) never rank-flags. The curated
/// framework check for domain files is exempt from this gate — frameworks are always third-party.
///
/// The rule is silent when the file's own package is unclassifiable (never guess-flag) and when a
/// same-group referenced package is unclassifiable. Severity ships at WARNING pending corpus
/// validation; the design intent is ERROR.
///
/// Lineage: this rule absorbs the layering concern that [CstDomainIoRule] (JBCT-MIX-01) covers for
/// JDK I/O. MIX-01 keeps its own ID/severity and its precise `java.io` / `java.nio` / ... catalog;
/// ARCH-01's framework set is disjoint from it, so the two are complementary, not redundant.
///
/// FP surface: a same-root-group project package whose segment happens to be a layer keyword without
/// layering intent. FN surface (syntax only, no cross-file resolution): a higher-layer type reached
/// through a star import or a simple name whose package is not otherwise referenced; a framework not
/// in the curated prefix set; a cross-group in-house module (different second segment); sideways
/// same-layer coupling with no slice / use-case signal — that is delegated to JBCT-ARCH-03 and
/// JBCT-ARCH-04, which carry the signal needed to tell "different workflow" apart. There is no
/// transitive-dependency analysis.
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
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        var packageName = packageName(root);

        if (!ctx.shouldLint(packageName)) {
            return Stream.empty();
        }

        return ctx.layers()
                  .layerOf(packageName)
                  .map(fileLayer -> diagnosticsFor(root, packageName, fileLayer, ctx))
                  .or(Stream.<Diagnostic> empty());
    }

    private Stream<Diagnostic> diagnosticsFor(Cursor root, String filePackage, Layer fileLayer, LintContext ctx) {
        var diagnostics = Stream.<Diagnostic> builder();

        for (var reference : ArchSupport.collectReferences(root)) {
            violationReason(filePackage, fileLayer, reference.packageName(), ctx).onPresent(reason ->
                diagnostics.add(createDiagnostic(reference.node(), reason, ctx)));
        }

        return diagnostics.build();
    }

    private Option<String> violationReason(String filePackage,
                                           Layer fileLayer,
                                           String referencedPackage,
                                           LintContext ctx) {
        if (referencedPackage.isEmpty()) {
            return Option.none();
        }
        // Domain purity: frameworks are third-party by definition and always checked.
        if (fileLayer == Layer.DOMAIN && isFramework(referencedPackage)) {
            return Option.some("domain must not depend on framework package '" + referencedPackage + "'");
        }
        // Layer-rank direction applies only within the file's own root group. A cross-group import is
        // third-party for layering purposes, so a coincidental layer keyword must not rank-flag it.
        if (!sameRootGroup(filePackage, referencedPackage)) {
            return Option.none();
        }

        return ctx.layers()
                  .layerOf(referencedPackage)
                  .filter(referencedLayer -> referencedLayer.rank() > fileLayer.rank())
                  .map(referencedLayer -> layerMessage(fileLayer, referencedLayer, referencedPackage));
    }

    /// Whether two packages share the same root group — their first two dotted segments
    /// (e.g. `org.pragmatica`). Cross-group references are third-party for layering.
    private static boolean sameRootGroup(String filePackage, String referencedPackage) {
        return rootGroup(filePackage).equals(rootGroup(referencedPackage));
    }

    private static String rootGroup(String packageName) {
        var segments = packageName.split("\\.");

        if (segments.length <= 2) {
            return packageName;
        }

        return segments[0] + "." + segments[1];
    }

    private static String layerMessage(Layer fileLayer, Layer referencedLayer, String referencedPackage) {
        return name(fileLayer) + " must not depend on " + name(referencedLayer) + " layer (package '"
               + referencedPackage + "')";
    }

    private static String name(Layer layer) {
        return layer.name()
                    .toLowerCase();
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
                                     "Imports point up only: domain <- application <- adapter <- bootstrap. Depend on "
                                    + "the same or a lower layer, never a higher one; keep the domain free of frameworks.");
    }
}
