package org.pragmatica.jbct.format.flow;

import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.text;

/// Static utility for determining blank line placement between class members.
///
/// In flow mode (no trivia inspection), blank lines are inserted between all members
/// except consecutive simple interface-style declarations (no body, no initializer).
final class BlankLineRules {

    private BlankLineRules() {}

    /// Determine if a blank line is needed between two consecutive members.
    static boolean needsBlankLineBetween(Cursor current, Option<Cursor> previous) {
        return previous
            .filter(prev -> !canPackTogether(current, prev))
            .isPresent();
    }

    /// Two members pack tightly together (no blank line) iff:
    /// - Both are simple declarations (no body, no annotation), AND
    /// - Both share the same `static` modifier status.
    private static boolean canPackTogether(Cursor current, Cursor previous) {
        if (!isSimpleNoBodyDeclaration(current) || !isSimpleNoBodyDeclaration(previous)) {
            return false;
        }
        return isStaticField(current) == isStaticField(previous);
    }

    /// A simple declaration: ends with semicolon, no block body, AND no annotation.
    private static boolean isSimpleNoBodyDeclaration(Cursor member) {
        if (hasAnnotationChild(member)) {
            return false;
        }
        var trimmed = text(member).trim();
        return trimmed.endsWith(";") && !trimmed.contains("{");
    }

    /// True if the member contains a `static` modifier token.
    private static boolean isStaticField(Cursor member) {
        if (!(member instanceof Cursor.Branch br)) {
            return false;
        }
        var tokens = br.cst().tokens();
        for (int t = br.firstTokenIdx(); t <= br.lastTokenIdx(); t++) {
            if (tokens.isTrivia(t)) continue;
            if ("static".equals(tokens.textAt(t).toString())) return true;
        }
        return false;
    }

    private static boolean hasAnnotationChild(Cursor member) {
        if (!(member instanceof Cursor.Branch br)) {
            return false;
        }
        return br.children().anyMatch(c -> c.kindIs(RuleKind.ANNOTATION));
    }
}
