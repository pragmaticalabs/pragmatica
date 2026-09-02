package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.layer.Zone;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.packageName;


/// JBCT-ARCH-02: `lift(...)` is confined to the adapter-boundary zone.
///
/// The `lift(...)` family (`Result.lift`, `Promise.lift`, `lift1`, ...) converts a foreign exception
/// into a typed `Cause`. That conversion is the defining job of the adapter boundary; business-zone
/// code (domain / application / bootstrap) should receive already-typed causes, never lift raw
/// exceptions itself. This rule classifies the file's zone (see
/// [org.pragmatica.jbct.lint.layer.LayerClassifier]) and, for a business-zone file, flags each
/// `lift(...)` call.
///
/// The scan is textual and therefore runs *masked* — string literals and comments are blanked via
/// [MapperSafety#blankNonCode] — so a `lift(` inside a string or comment never matches. The rule is
/// silent when the file's package is unclassifiable (never guess-flag).
///
/// FP surface: a domain method legitimately named `lift` (or `lift1`, ...) — its call matches the
/// textual pattern. FN surface (syntax only): a `lift` reached only through a method reference
/// (`Result::lift`) is not a call site and is not flagged.
public class CstArchLiftZoneRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ARCH-02";

    /// A `lift` / `lift1` / `lift2` ... call — the foreign-exception-to-Cause family.
    private static final Pattern LIFT_CALL = Pattern.compile("\\blift\\d*\\s*\\(");

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
                  .zoneOf(packageName)
                  .filter(zone -> zone != Zone.ADAPTER_BOUNDARY)
                  .map(zone -> liftDiagnostics(source, ctx))
                  .or(Stream.<Diagnostic> empty());
    }

    private Stream<Diagnostic> liftDiagnostics(String source, LintContext ctx) {
        var masked = MapperSafety.blankNonCode(source);
        var matcher = LIFT_CALL.matcher(masked);
        var diagnostics = Stream.<Diagnostic> builder();

        while (matcher.find()) {
            var line = MapperSafety.newlinesBefore(masked, matcher.start()) + 1;
            var column = matcher.start() - masked.lastIndexOf('\n', matcher.start() - 1);

            diagnostics.add(createDiagnostic(line, column, ctx));
        }

        return diagnostics.build();
    }

    private Diagnostic createDiagnostic(int line, int column, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     line,
                                     column,
                                     "lift(...) is only allowed in adapter-boundary code",
                                     "The lift(...) family converts foreign exceptions to typed Causes; keep it in the "
                                    + "adapter zone and pass typed Causes into business logic.");
    }
}
