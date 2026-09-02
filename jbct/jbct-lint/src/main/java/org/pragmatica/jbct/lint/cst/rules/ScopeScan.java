package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.findAll;
import static org.pragmatica.jbct.parser.CstNodes.text;


/// Shared scanning support for the body-text rules that partition work per method / lambda
/// (JBCT-RET-06, JBCT-RET-08, JBCT-MUT-01).
///
/// A method or lambda that textually contains a nested type declaration (a local class/record/
/// enum, or an anonymous-class body) would otherwise be scanned twice — once by the enclosing
/// scope's body-text pass and once by the nested member's own pass — emitting duplicate
/// diagnostics on the same line. [#bodyTextExcludingNestedTypes] blanks the span of every nested
/// type body (class/record/enum body) inside the scope, so the enclosing pass sees only its own
/// statements; the nested members are still covered by their own scan pass. Blanking preserves
/// line offsets (newlines are kept), so callers can still map a match offset to a source line.
sealed interface ScopeScan permits ScopeScan.unused {
    record unused() implements ScopeScan {}

    RuleKind[] NESTED_TYPE_BODIES = {RuleKind.CLASS_BODY, RuleKind.INTERFACE_BODY, RuleKind.RECORD_BODY, RuleKind.ENUM_BODY};

    /// String- and comment-blanked text of `scope`, with the span of every nested type body
    /// additionally blanked to spaces (newlines preserved).
    static String bodyTextExcludingNestedTypes(Cursor scope) {
        var builder = new StringBuilder(MapperSafety.blankNonCode(text(scope)));
        var base = scope.spanStart();

        for (var nested : nestedTypeBodies(scope)) {
            blankRange(builder, nested.spanStart() - base, nested.spanEnd() - base);
        }

        return builder.toString();
    }

    private static List<Cursor> nestedTypeBodies(Cursor scope) {
        var bodies = new ArrayList<Cursor>();

        for (var kind : NESTED_TYPE_BODIES) {
            bodies.addAll(findAll(scope, kind));
        }

        return bodies;
    }

    private static void blankRange(StringBuilder builder, int start, int end) {
        var from = Math.max(0, start);
        var to = Math.min(builder.length(), end);

        for (var i = from; i < to; i++) {
            var c = builder.charAt(i);

            if (c != '\n' && c != '\r') {
                builder.setCharAt(i, ' ');
            }
        }
    }
}
