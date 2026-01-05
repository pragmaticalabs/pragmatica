package org.pragmatica.jbct.format.cst;

import java.util.Set;

/**
 * Encapsulates Java syntax-aware spacing rules for the CST printer.
 * Determines when spaces are needed between tokens.
 */
final class SpacingRules {
    // Control flow keywords that need space before (
    private static final Set<String> SPACE_BEFORE_PAREN_KEYWORDS = Set.of("if",
                                                                          "else",
                                                                          "for",
                                                                          "while",
                                                                          "do",
                                                                          "try",
                                                                          "catch",
                                                                          "finally",
                                                                          "switch",
                                                                          "synchronized",
                                                                          "assert");

    // Keywords that need space after }
    private static final Set<String> SPACE_AFTER_BRACE_KEYWORDS = Set.of("else", "catch", "finally", "while");

    // Keywords that need space after them (before literals, operators, etc.)
    private static final Set<String> SPACE_AFTER_KEYWORDS = Set.of("case", "return", "throw", "new", "yield", "assert");

    // Binary operators that need space around them (excluding <> which are also used for generics)
    private static final Set<String> BINARY_OPS = Set.of("=",
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
    private static final Set<Character> BINARY_OP_CHARS = Set.of('=', '+', '-', '*', '/', '%', '&', '|', '^', '?', ':');

    private SpacingRules() {}

    /**
     * Determine if a space is needed before the given text based on Java syntax rules.
     */
    static boolean needsSpaceBefore(SpacingContext ctx, String text) {
        if (ctx.lastChar() == 0 || ctx.lastChar() == '\n' || ctx.lastChar() == ' ' || ctx.lastChar() == '\t') {
            return false;
        }
        char firstChar = text.charAt(0);
        // VETO RULES - These must NOT have space, regardless of other rules
        if (mustNotHaveSpaceBefore(ctx, text, firstChar)) {
            return false;
        }
        // Check rules that add spaces
        return checkCommaRule(ctx) || checkClosingBraceKeywordRule(ctx, text) || checkKeywordBeforeLiteralRule(ctx,
                                                                                                               firstChar) || checkParenthesesRules(ctx,
                                                                                                                                                   text,
                                                                                                                                                   firstChar) || checkBracketRules(ctx,
                                                                                                                                                                                   firstChar) || checkDotRules(ctx,
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

    /**
     * Veto rules that prevent spaces regardless of other rules.
     * These handle cases where tokens like ++ before ) would otherwise get a space
     * due to binary operator rules.
     */
    private static boolean mustNotHaveSpaceBefore(SpacingContext ctx, String text, char firstChar) {
        // No space before ) regardless of previous token (fixes i++ ) issue)
        if (firstChar == ')') {
            return true;
        }
        // No space before ] regardless of previous token
        if (firstChar == ']') {
            return true;
        }
        // No space before ; or ,
        if (firstChar == ';' || firstChar == ',') {
            return true;
        }
        // No space after @
        if (ctx.lastChar() == '@') {
            return true;
        }
        // No space after ( or [
        if (ctx.lastChar() == '(' || ctx.lastChar() == '[') {
            return true;
        }
        // No space before . (except varargs ...)
        if (firstChar == '.' && !text.equals("...")) {
            return true;
        }
        // No space after . (except after ... varargs)
        if (ctx.lastChar() == '.' && ctx.prevChar() != '.') {
            return true;
        }
        // No space before or after ::
        if (text.equals("::") || (ctx.lastChar() == ':' && ctx.prevChar() == ':')) {
            return true;
        }
        // No space before > after ] (array in generics: Promise<float[]>)
        if (firstChar == '>' && ctx.lastChar() == ']') {
            return true;
        }
        return false;
    }

    private static boolean checkCommaRule(SpacingContext ctx) {
        return ctx.lastChar() == ',';
    }

    private static boolean checkClosingBraceKeywordRule(SpacingContext ctx, String text) {
        // Space between } and keywords like else, catch, finally, while (do-while)
        return ctx.lastChar() == '}' && SPACE_AFTER_BRACE_KEYWORDS.contains(text);
    }

    private static boolean checkKeywordBeforeLiteralRule(SpacingContext ctx, char firstChar) {
        // Space after keywords like case, return, throw before literals or expressions
        // e.g., case "value" or return 5 or throw new
        if (!SPACE_AFTER_KEYWORDS.contains(ctx.lastWord())) {
            return false;
        }
        // Need space before string literals, numbers, identifiers, minus (negative numbers)
        return firstChar == '"' || firstChar == '\'' || Character.isDigit(firstChar) || Character.isLetter(firstChar) || firstChar == '-' || firstChar == '(';
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
        // After ')' - likely comparison
        if (ctx.lastChar() == ')') {
            return true;
        }
        // After ']' - array in generics (no space): Promise<int[]>
        if (ctx.lastChar() == ']') {
            return false;
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
        // After > in generics, space before any identifier
        if (Character.isLetter(firstChar)) {
            return true;
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
        static SpacingContext spacingContext(char lastChar, char prevChar, String lastWord, int outputLength) {
            return new SpacingContext(lastChar, prevChar, lastWord, outputLength);
        }
    }
}
