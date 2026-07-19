package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-ARCH-04: a slice must not import another slice's internals.
///
/// A vertical slice exposes a public surface at its root package; everything strictly beneath the
/// root is that slice's internal. This rule classifies the slice a package belongs to (see
/// [org.pragmatica.jbct.lint.layer.LayerClassifier]) and flags every reference — import or
/// fully-qualified type node (see [ArchSupport]) — that reaches into a *different* slice's internal
/// package. Importing another slice's public root, or an internal of one's own slice, is allowed.
///
/// Slice roots come from explicit `[lint.layers] slices` globs or, by convention, from the immediate
/// child of a `usecase` segment (`com.example.usecase.registeruser`). With no slice classification
/// available for the referenced package, the rule is silent.
///
/// Lineage: this rule delivers the cross-slice import boundary that the retired no-op JBCT-SLICE-01
/// surface (removed in #450) never enforced; it is realised as a specialization of the #452
/// package-classification engine rather than by reviving the SLICE-01 ID. Severity ships at WARNING
/// pending corpus validation; the design intent is ERROR.
///
/// FP surface: a project using the `usecase.<name>` layout without vertical-slice intent sees each
/// immediate child treated as a slice root. FN surface (syntax only): a star import hides the exact
/// internal package; there is no cross-file resolution and no transitive analysis.
public class CstArchSliceInternalRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ARCH-04";

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

        var fileSliceRoot = ctx.layers()
                               .sliceRootOf(packageName);
        var diagnostics = Stream.<Diagnostic> builder();

        for (var reference : ArchSupport.collectReferences(root)) {
            crossSliceViolation(reference.packageName(), fileSliceRoot, ctx).onPresent(reason ->
                diagnostics.add(createDiagnostic(reference.node(), reason, ctx)));
        }

        return diagnostics.build();
    }

    private Option<String> crossSliceViolation(String referencedPackage,
                                               Option<String> fileSliceRoot,
                                               LintContext ctx) {
        if (referencedPackage.isEmpty()) {
            return Option.none();
        }

        return ctx.layers()
                  .sliceRootOf(referencedPackage)
                  .filter(referencedRoot -> !referencedPackage.equals(referencedRoot))
                  .filter(referencedRoot -> !belongsToSameSlice(fileSliceRoot, referencedRoot))
                  .map(referencedRoot -> "import reaches into slice '" + referencedRoot + "' internal package '"
                                         + referencedPackage + "'");
    }

    private boolean belongsToSameSlice(Option<String> fileSliceRoot, String referencedRoot) {
        return fileSliceRoot.map(referencedRoot::equals)
                            .or(false);
    }

    private Diagnostic createDiagnostic(Cursor node, String reason, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "Cross-slice internal dependency: " + reason,
                                     "A slice may depend only on another slice's public root package, never on its "
                                    + "internals. Expose the needed type at the slice root or extract a shared type.");
    }
}
