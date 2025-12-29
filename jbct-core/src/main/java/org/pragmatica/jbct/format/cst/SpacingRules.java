package org.pragmatica.jbct.format.cst;

import java.util.Set;

/**
 * Encapsulates Java syntax-aware spacing rules for the CST printer.
 * Determines when spaces are needed between tokens.
 */
final class SpacingRules {
    // Control flow keywords that need space before (
    private static final Set<String>SPACE_BEFORE_PAREN_KEYWORDS = Set.of(
    "if", "else", "for", "while", "do", "try", "catch", "finally", "switch", "synchronized", "assert");

    // Binary operators that need space around them (excluding <> which are also used for generics)
    private static final Set<String>BINARY_OPS = Set.of(
    "=",
    "==",
    "!=",
    "<=",
    ">=",
    "+",
    "-",
    "*",
    "/",
    "%",
    "&",
    "|",
    "^",
    "&&",
    "||",
    "->",
    "?",
    ":",
    "+=",
    "-=",
    "*=",
    "/=",
    "%=",
    "&=",
    "|=",
    "^=",
    "<<=",
    ">>=",
    ">>>=");

    // Single-char binary operators for checking output endings
    private static final Set<Character>BINARY_OP_CHARS = Set.of(
    '=', '+', '-', '*', '/', '%', '&', '|', '^', '?', ':');

    private SpacingRules() {}

    /**
     * Determine if a space is needed before the given text based on Java syntax rules.
     */
    static boolean needsSpaceBefore(SpacingContext ctx, String text) {
        if (ctx.lastChar() == 0 || ctx.lastChar() == '\n' || ctx.lastChar() == ' ' || ctx.lastChar() == '\t') {
            return false;
        }
        char firstChar = text.charAt(0);
        // Check rules in order of frequency/importance
        return checkCommaRule(ctx) || checkParenthesesRules(ctx, text, firstChar) || checkBracketRules(ctx, firstChar) || checkDotRules(ctx,
                                                                                                                                        text,
                                                                                                                                        firstChar) || checkAnnotationRules(ctx,
                                                                                                                                                                           firstChar) || checkMethodReferenceRule(ctx,
                                                                                                                                                                                                                  text) || checkAngleBracketRules(ctx,
                                                                                                                                                                                                                                                  text,
                                                                                                                                                                                                                                                  firstChar) || checkBinaryOperatorRules(ctx,
                                                                                                                                                                                                                                                                                         text,
                                                                                                                                                                                                                                                                                         firstChar) || checkAlphanumericRule(ctx,
                                                                                                                                                                                                                                                                                                                             firstChar) || checkClosingParenRule(ctx,
                                                                                                                                                                                                                                                                                                                                                                 firstChar) || checkGenericClosingRule(ctx,
                                                                                                                                                                                                                                                                                                                                                                                                       firstChar);
    }

    private static boolean checkCommaRule(SpacingContext ctx) {
        return ctx.lastChar() == ',';
    }

    private static boolean checkParenthesesRules(SpacingContext ctx, String text, char firstChar) {
        // No space after '(' or before ')'
        if (ctx.lastChar() == '(' || firstChar == ')') {
            return false;
        }
        // No space before ';' or ','
        if (firstChar == ';' || firstChar == ',') {
            return false;
        }
        // Space before '(' after control flow keywords or binary operators
        if (firstChar == '(') {
            return isBinaryOpLastChar(ctx) || SPACE_BEFORE_PAREN_KEYWORDS.contains(ctx.lastWord());
        }
        return false;
    }

    private static boolean checkBracketRules(SpacingContext ctx, char firstChar) {
        // No space after '[' or before ']'
        if (ctx.lastChar() == '[' || firstChar == ']') {
            return false;
        }
        // Space before '[' when following ')' (type-use annotation arrays)
        if (firstChar == '[' && ctx.lastChar() == ')') {
            return true;
        }
        // Space after ']' when followed by identifier (array type before variable name)
        // e.g., String[] LONG_ARRAY, String[] _field
        if (ctx.lastChar() == ']' && isIdentifierStart(firstChar)) {
            return true;
        }
        return false;
    }

    private static boolean checkDotRules(SpacingContext ctx, String text, char firstChar) {
        // No space before or after '.' (except varargs ...)
        if (firstChar == '.' && !text.equals("...")) {
            return false;
        }
        // Space after ... (varargs): Object... args
        if (ctx.lastChar() == '.' && ctx.prevChar() == '.' && Character.isLetter(firstChar)) {
            return true;
        }
        return ctx.lastChar() == '.' && !text.equals("...")
               ? false
               : false;
    }

    private static boolean checkAnnotationRules(SpacingContext ctx, char firstChar) {
        // No space after '@'
        if (ctx.lastChar() == '@') {
            return false;
        }
        // Space before '@' when following ')' (multiple annotations)
        return firstChar == '@' && ctx.lastChar() == ')';
    }

    private static boolean checkMethodReferenceRule(SpacingContext ctx, String text) {
        // No space before or after '::'
        return false;
    }

    private static boolean checkAngleBracketRules(SpacingContext ctx, String text, char firstChar) {
        if (!text.equals("<") && !text.equals(">")) {
            return false;
        }
        // No space between consecutive angle brackets: >> or <<
        if (ctx.lastChar() == '<' || ctx.lastChar() == '>') {
            return false;
        }
        // No space when forming -> (arrow operator)
        if (text.equals(">") && ctx.lastChar() == '-') {
            return false;
        }
        // If last char is alphanumeric, check type name vs variable
        if (Character.isLetterOrDigit(ctx.lastChar())) {
            // Type names start with uppercase (generics), variables with lowercase (comparison)
            if (!ctx.lastWord()
                    .isEmpty() && Character.isUpperCase(ctx.lastWord()
                                                           .charAt(0))) {
                return false;
            }
            return true;
        }
        // After ')' or ']' - likely comparison
        if (ctx.lastChar() == ')' || ctx.lastChar() == ']') {
            return true;
        }
        // After '.' - likely generics invocation: List.<String>of()
        return ctx.lastChar() != '.';
    }

    private static boolean checkBinaryOperatorRules(SpacingContext ctx, String text, char firstChar) {
        // Space around binary operators
        if (BINARY_OPS.contains(text)) {
            return true;
        }
        // Space after binary operators
        return isBinaryOpLastChar(ctx);
    }

    private static boolean checkAlphanumericRule(SpacingContext ctx, char firstChar) {
        // Space between alphanumeric tokens (keywords, identifiers, types)
        // Also handle underscore and dollar which are valid identifier start chars in Java
        return Character.isLetterOrDigit(ctx.lastChar()) && isIdentifierStart(firstChar);
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean checkClosingParenRule(SpacingContext ctx, char firstChar) {
        // Space after ) when followed by identifier
        return ctx.lastChar() == ')' && isIdentifierStart(firstChar);
    }

    private static boolean checkGenericClosingRule(SpacingContext ctx, char firstChar) {
        if (ctx.lastChar() != '>') {
            return false;
        }
        // Check if it's after -> (lambda arrow)
        if (ctx.prevChar() == '-') {
            // After -> in lambda, need space unless followed by {
            return firstChar != '{';
        }
        // After > in generics, space before lowercase identifier (variable name)
        // No space before uppercase (could be type name like <T>Result or constant - limitation)
        if (Character.isLetter(firstChar)) {
            return Character.isLowerCase(firstChar);
        }
        return false;
    }

    private static boolean isBinaryOpLastChar(SpacingContext ctx) {
        if (ctx.outputLength() < 1) {
            return false;
        }
        char lastChar = ctx.lastChar();
        if (!BINARY_OP_CHARS.contains(lastChar)) {
            return false;
        }
        // But not after :: (method reference)
        if (lastChar == ':' && ctx.outputLength() >= 2 && ctx.prevChar() == ':') {
            return false;
        }
        return true;
    }

    /**
     * Context needed for spacing decisions.
     */
    record SpacingContext(char lastChar, char prevChar, String lastWord, int outputLength) {
        static SpacingContext of(char lastChar, char prevChar, String lastWord, int outputLength) {
            return new SpacingContext(lastChar, prevChar, lastWord, outputLength);
        }
    }
}
