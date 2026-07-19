package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-RET-08: No null literal passed as a call argument.
///
/// Passing `null` as a call argument (`compute(null)`, `new Email(a, null)`) leaks absence
/// through the boundary into business logic; pass an empty `Option`, or restructure so the value
/// is always present. The one sanctioned null argument is the documented `.or(null)` adapter,
/// which converts an `Option` back to a nullable database column.
///
/// Detection scans string- and comment-blanked source (so a `null` inside a literal or comment is
/// never matched) for a `null` token that stands alone as a call argument — its nearest
/// non-whitespace neighbours are `(` / `,` on the left and `)` / `,` on the right — then resolves
/// the enclosing call name to apply the `.or(null)` exemption.
///
/// Scope note: an earlier draft also flagged defensive null *comparisons* (`x == null`) of
/// non-parameter locals. The corpus audit found that arm was ~90% boundary noise — a local
/// null-check of a JDK-nullable return (`Map.get`, `getenv`, `putIfAbsent`, `getResourceAsStream`,
/// array slots) is correct practice, and several such values cannot be `Option`-wrapped. The
/// doctrinal anti-pattern is null *escaping* — returns (JBCT-RET-03), parameters (JBCT-RET-06),
/// and call arguments (this rule); flow-aware comparison analysis belongs to the #455 hard tier,
/// so the comparison arm was dropped.
///
/// FP surface: an annotation argument `@Ann(null)`. FN surface: a cast argument `foo((T) null)`
/// and an array element `new T[]{null}` are not flagged.
public class CstNullLiteralRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-08";
    private static final Pattern NULL_WORD = Pattern.compile("\\bnull\\b");

    /// Method whose single `null` argument is the sanctioned Option-to-nullable-column adapter.
    private static final String NULLABLE_ADAPTER_CALL = "or";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        var masked = MapperSafety.blankNonCode(source);
        var matcher = NULL_WORD.matcher(masked);
        var diagnostics = Stream.<Diagnostic> builder();

        while (matcher.find()) {
            if (isBareArgument(masked, matcher.start(), matcher.end())) {
                addNullArgument(masked, matcher.start(), diagnostics, ctx);
            }
        }

        return diagnostics.build();
    }

    private boolean isBareArgument(String masked, int start, int end) {
        var before = lastNonWhitespaceBefore(masked, start);
        var after = firstNonWhitespaceAfter(masked, end);

        return (before == '(' || before == ',') && (after == ')' || after == ',');
    }

    private void addNullArgument(String masked, int start, Stream.Builder<Diagnostic> diagnostics, LintContext ctx) {
        var callName = MapperSafety.enclosingCallName(masked, start)
                                   .or("");

        if (callName.isEmpty() || NULLABLE_ADAPTER_CALL.equals(callName)) {
            return;
        }

        diagnostics.add(createDiagnostic(MapperSafety.newlinesBefore(masked, start) + 1, ctx));
    }

    private char lastNonWhitespaceBefore(String text, int index) {
        for (var i = index - 1; i >= 0; i--) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return text.charAt(i);
            }
        }

        return '\0';
    }

    private char firstNonWhitespaceAfter(String text, int index) {
        for (var i = index; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return text.charAt(i);
            }
        }

        return '\0';
    }

    private Diagnostic createDiagnostic(int line, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     line,
                                     1,
                                     "null passed as an argument; pass Option (empty) or restructure",
                                     "A null argument leaks absence through the call boundary. Use Option for optional "
                                    + "inputs; the only sanctioned null argument is the .or(null) nullable-column adapter.");
    }
}
