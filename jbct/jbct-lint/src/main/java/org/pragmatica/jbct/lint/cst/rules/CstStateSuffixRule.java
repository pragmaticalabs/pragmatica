package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-NAM-03: `*State` suffix discipline.
///
/// The `*State` suffix is reserved for the sealed-interface sum that enumerates a state machine's
/// lifecycle states (`HoldState`, `BookingState`). Its variants stay bare (`Free`, `Held`,
/// `Confirmed` — never `HeldState`). This rule flags a record or class *variant* — one that
/// `implements` an interface whose name also ends in `State` — whose own name ends in `State`.
///
/// The sealed sum interface itself (an `interface HoldState`) is correct and never flagged; only
/// record/class variants are candidates. Matching is by the declared name's suffix and the
/// implemented type's suffix, both read from the declaration header, so it needs no cross-file
/// resolution.
///
/// FP surface: a record/class named `SomethingState` that implements an unrelated interface which
/// merely happens to end in `State`. FN surface (deliberate, to keep false positives near zero):
/// a standalone `*State` type that is neither a sealed interface nor a variant (for example a
/// mutable `SessionState` holder) is not flagged; and a declaration annotation containing a brace
/// truncates the parsed header (see [DeclSupport]), so its `implements` clause is missed.
public class CstStateSuffixRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-NAM-03";
    private static final String STATE_SUFFIX = "State";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return Stream.concat(findAllRecords(root).stream(), findAllClasses(root).stream())
                     .filter(this::isStateSuffixVariant)
                     .map(decl -> createDiagnostic(decl, ctx));
    }

    private boolean isStateSuffixVariant(Cursor decl) {
        return DeclSupport.declName(decl)
                          .endsWith(STATE_SUFFIX) && DeclSupport.implementedHeadNames(decl)
                                                                .stream()
                                                                .anyMatch(name -> name.endsWith(STATE_SUFFIX));
    }

    private Diagnostic createDiagnostic(Cursor decl, LintContext ctx) {
        var name = DeclSupport.declName(decl);

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(decl),
                                     startColumn(decl),
                                     "Variant '" + name + "' should have a bare name, not the '*State' suffix",
                                     "The '*State' suffix is reserved for the sealed lifecycle-sum interface. Its "
                                    + "variants stay bare (e.g. 'Held' implementing 'HoldState', not 'HeldState').");
    }
}
