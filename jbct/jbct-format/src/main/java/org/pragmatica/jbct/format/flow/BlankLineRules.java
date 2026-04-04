package org.pragmatica.jbct.format.flow;

import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.text;

/// Static utility for determining blank line placement between class members.
///
/// In flow mode (no trivia inspection), blank lines are inserted between all members
/// except consecutive simple interface-style declarations (no body, no initializer).
final class BlankLineRules {

    private BlankLineRules() {}

    /// Determine if a blank line is needed between two consecutive members.
    static boolean needsBlankLineBetween(CstNode current, Option<CstNode> previous, String source) {
        return previous
            .filter(prev -> !areBothSimpleNoInitDeclarations(current, prev, source))
            .isPresent();
    }

    private static boolean areBothSimpleNoInitDeclarations(CstNode current, CstNode previous, String source) {
        return isSimpleNoInitDeclaration(text(current, source))
            && isSimpleNoInitDeclaration(text(previous, source));
    }

    /// A simple declaration without initializer: ends with semicolon, no block body, no assignment.
    /// Matches interface methods like `String email();` and bare fields like `Request raw;`.
    private static boolean isSimpleNoInitDeclaration(String memberText) {
        var trimmed = memberText.trim();
        return trimmed.endsWith(";") && !trimmed.contains("{") && !trimmed.contains("=");
    }
}
