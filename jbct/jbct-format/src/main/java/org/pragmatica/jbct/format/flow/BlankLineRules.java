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
            .filter(prev -> !areBothSimpleNoInitDeclarations(current, prev))
            .isPresent();
    }

    private static boolean areBothSimpleNoInitDeclarations(Cursor current, Cursor previous) {
        return isSimpleNoInitDeclaration(current)
            && isSimpleNoInitDeclaration(previous);
    }

    /// A simple declaration: ends with semicolon, no block body, AND no annotation.
    /// Field declarations with or without initializers count as simple — consecutive fields
    /// pack tightly without blank-line separators. Annotations promote a declaration to
    /// "full"; a blank line separates it from neighbours regardless of body shape.
    private static boolean isSimpleNoInitDeclaration(Cursor member) {
        if (hasAnnotationChild(member)) {
            return false;
        }
        var trimmed = text(member).trim();
        return trimmed.endsWith(";") && !trimmed.contains("{");
    }

    private static boolean hasAnnotationChild(Cursor member) {
        if (!(member instanceof Cursor.Branch br)) {
            return false;
        }
        return br.children().anyMatch(c -> c.kindIs(RuleKind.ANNOTATION));
    }
}
