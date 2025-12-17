package org.pragmatica.jbct.format.cst;

import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.Trivia;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * CST printer for JBCT formatting.
 *
 * Key formatting rules:
 * - Chain alignment: `.` aligns to end of first method call
 * - Arguments: align to opening `(`
 * - Imports: grouped (pragmatica, java/javax, static)
 * - 120 char max line length, 4 space indent
 */
public class CstPrinter {

    private final FormatterConfig config;
    private final String source;
    private final StringBuilder output;
    private int currentColumn;
    private int indentLevel;
    private char lastChar = 0;  // Track last printed character for spacing
    private char prevChar = 0;  // Track second-to-last character
    private String lastWord = "";  // Track last printed identifier/keyword

    // Measurement mode - calculates width without actually printing
    private boolean measuringMode = false;
    private int measureBuffer = 0;

    // Alignment tracking
    private final Deque<Integer> alignmentStack = new ArrayDeque<>();
    private final Deque<Integer> argumentAlignStack = new ArrayDeque<>();
    private final Deque<Integer> lambdaAlignStack = new ArrayDeque<>();
    private int chainAlignColumn = -1;

    // Keywords that shouldn't have preceding newlines removed
    private static final Set<String> BLOCK_KEYWORDS = Set.of(
        "if", "else", "for", "while", "do", "try", "catch", "finally", "switch", "case", "default"
    );

    // Control flow keywords that need space before (
    private static final Set<String> SPACE_BEFORE_PAREN_KEYWORDS = Set.of(
        "if", "else", "for", "while", "do", "try", "catch", "finally", "switch", "synchronized", "assert"
    );

    // Binary operators that need space around them (excluding <> which are also used for generics)
    private static final Set<String> BINARY_OPS = Set.of(
        "=", "==", "!=", "<=", ">=", "+", "-", "*", "/", "%",
        "&", "|", "^", "&&", "||", "->", "?", ":", "+=", "-=", "*=", "/=",
        "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>="
    );

    // Comparison operators (need special handling vs generics)
    private static final Set<String> COMPARISON_OPS = Set.of("<", ">");

    // Operators/punctuation that need space after (comma, semicolon in for)
    private static final Set<String> SPACE_AFTER = Set.of(",");

    public CstPrinter(FormatterConfig config, String source) {
        this.config = config;
        this.source = source;
        this.output = new StringBuilder();
        this.currentColumn = 0;
        this.indentLevel = 0;
    }

    // ===== Measurement methods =====

    /**
     * Measure the single-line width of a node without printing.
     * This traverses the node and counts characters as if printed on one line.
     */
    private int measureWidth(CstNode node) {
        boolean wasMeasuring = measuringMode;
        int oldBuffer = measureBuffer;
        char oldLastChar = lastChar;
        char oldPrevChar = prevChar;
        String oldLastWord = lastWord;
        measuringMode = true;
        measureBuffer = 0;

        printNode(node);

        int width = measureBuffer;
        measuringMode = wasMeasuring;
        measureBuffer = oldBuffer;
        lastChar = oldLastChar;
        prevChar = oldPrevChar;
        lastWord = oldLastWord;
        return width;
    }

    /**
     * Check if a node would fit on current line.
     */
    private boolean fitsOnLine(CstNode node) {
        int width = measureWidth(node);
        return currentColumn + width <= config.maxLineLength();
    }

    /**
     * Check if text would fit on current line.
     */
    private boolean fitsOnLine(String text) {
        return currentColumn + text.length() <= config.maxLineLength();
    }

    public String print(CstNode root) {
        printNode(root);
        return output.toString().stripTrailing() + "\n";
    }

    private void printNode(CstNode node) {
        // Print leading trivia
        printTrivia(node.leadingTrivia());

        switch (node) {
            case CstNode.Terminal t -> printTerminal(t);
            case CstNode.Token tok -> printToken(tok);
            case CstNode.NonTerminal nt -> printNonTerminal(nt);
        }

        // Print trailing trivia
        printTrivia(node.trailingTrivia());
    }

    private void printNodeSkipTrivia(CstNode node) {
        // Skip whitespace trivia but preserve comments
        printCommentsOnly(node.leadingTrivia());

        switch (node) {
            case CstNode.Terminal t -> printTerminal(t);
            case CstNode.Token tok -> printToken(tok);
            case CstNode.NonTerminal nt -> printNonTerminal(nt);
        }

        printCommentsOnly(node.trailingTrivia());
    }

    private void printNodeSkipLeadingTrivia(CstNode node) {
        // Skip leading whitespace trivia but preserve comments
        printCommentsOnly(node.leadingTrivia());

        switch (node) {
            case CstNode.Terminal t -> printTerminal(t);
            case CstNode.Token tok -> printToken(tok);
            case CstNode.NonTerminal nt -> printNonTerminal(nt);
        }

        // Print trailing trivia normally
        printTrivia(node.trailingTrivia());
    }

    private void printCommentsOnly(List<Trivia> triviaList) {
        for (var trivia : triviaList) {
            switch (trivia) {
                case Trivia.LineComment lc -> {
                    print(lc.text());
                    println();
                    printIndent();  // Restore indent after line comment
                }
                case Trivia.BlockComment bc -> {
                    print(bc.text());
                }
                case Trivia.Whitespace _ -> {
                    // Skip whitespace - we control layout
                }
            }
        }
    }

    private void printTerminal(CstNode.Terminal terminal) {
        var text = terminal.text();
        printWithSpacing(text);
    }

    private void printToken(CstNode.Token token) {
        var text = token.text();
        printWithSpacing(text);
    }

    /**
     * Print text with appropriate spacing based on Java syntax rules.
     */
    private void printWithSpacing(String text) {
        if (!text.isEmpty() && needsSpaceBefore(text)) {
            print(" ");
        }
        print(text);
    }

    /**
     * Determine if a space is needed before the given text based on Java syntax rules.
     */
    private boolean needsSpaceBefore(String text) {
        if (lastChar == 0 || lastChar == '\n' || lastChar == ' ' || lastChar == '\t') {
            return false;
        }

        char firstChar = text.charAt(0);

        // Space after comma
        if (lastChar == ',') {
            return true;
        }

        // No space after '(' or before ')'
        if (lastChar == '(' || firstChar == ')') {
            return false;
        }

        // No space after '[' or before ']'
        if (lastChar == '[' || firstChar == ']') {
            return false;
        }

        // No space before '(' in method calls, but space after control flow keywords
        if (firstChar == '(') {
            return SPACE_BEFORE_PAREN_KEYWORDS.contains(lastWord);
        }

        // No space before ';'
        if (firstChar == ';') {
            return false;
        }

        // No space before ','
        if (firstChar == ',') {
            return false;
        }

        // No space before or after '.'
        if (lastChar == '.' || firstChar == '.') {
            return false;
        }

        // No space after '@'
        if (lastChar == '@') {
            return false;
        }

        // No space before or after '::'
        if (text.equals("::") || lastChar == ':' && text.charAt(0) == ':') {
            return false;
        }

        // Handle < and > specially (comparison vs generics)
        // In generics (after type name): no space - List<String>
        // In comparison (after variable): space - if (x < 5)
        if (text.equals("<") || text.equals(">")) {
            // If last char is alphanumeric, check if it's likely a type name (generics) or variable (comparison)
            if (Character.isLetterOrDigit(lastChar)) {
                // Type names typically start with uppercase, variables with lowercase
                if (!lastWord.isEmpty() && Character.isUpperCase(lastWord.charAt(0))) {
                    return false;  // Likely generics: List<String>
                }
                return true;  // Likely comparison: value < 5
            }
            // After non-alphanumeric (like ')'), it's comparison
            return true;
        }

        // Space around binary operators
        if (BINARY_OPS.contains(text)) {
            return true;
        }

        // Space after binary operators (check last printed was an operator)
        // This handles the space AFTER the operator
        if (isBinaryOpLastChar()) {
            return true;
        }

        // Space between alphanumeric tokens (keywords, identifiers, types)
        if (Character.isLetterOrDigit(lastChar) && Character.isLetterOrDigit(firstChar)) {
            return true;
        }

        // Space after > when:
        // 1. Followed by identifier (e.g., "Result<String> name")
        // 2. Part of -> (lambda arrow) followed by anything but {
        if (lastChar == '>') {
            // Check if it's after -> (lambda arrow)
            if (prevChar == '-') {
                // After -> in lambda, need space unless followed by {
                return firstChar != '{';
            }
            // After > in generics, space before identifier
            if (Character.isLetter(firstChar)) {
                return true;
            }
        }

        // No space after < (inside generics)
        if (lastChar == '<') {
            return false;
        }

        // No space before >
        if (firstChar == '>') {
            return false;
        }

        return false;
    }

    private boolean isBinaryOpLastChar() {
        // Check if the output ends with a binary operator
        if (output.length() < 1) return false;

        // Check common single-char operators
        if (lastChar == '=' || lastChar == '+' || lastChar == '-' || lastChar == '*' ||
            lastChar == '/' || lastChar == '%' || lastChar == '&' || lastChar == '|' ||
            lastChar == '^' || lastChar == '?' || lastChar == ':') {
            // But not after :: (method reference)
            if (lastChar == ':' && output.length() >= 2 && output.charAt(output.length() - 2) == ':') {
                return false;
            }
            return true;
        }
        return false;
    }

    private void printNonTerminal(CstNode.NonTerminal nt) {
        var rule = nt.rule();

        // Handle special formatting rules
        switch (rule) {
            case "CompilationUnit" -> printCompilationUnit(nt);
            case "OrdinaryUnit" -> printOrdinaryUnit(nt);
            case "ImportDecl" -> printImportDecl(nt);
            case "ClassDecl", "InterfaceDecl", "EnumDecl", "RecordDecl" -> printTypeDecl(nt);
            case "EnumBody" -> printEnumBody(nt);
            case "RecordBody" -> printRecordBody(nt);
            case "MethodDecl", "ConstructorDecl" -> printMethodDecl(nt);
            case "FieldDecl" -> printFieldDecl(nt);
            case "ClassBody" -> printClassBody(nt);
            case "Block" -> printBlock(nt);
            case "Stmt" -> printStatement(nt);
            case "Expr", "Assignment" -> printExpression(nt);
            case "Postfix" -> printPostfix(nt);
            case "Primary" -> printPrimary(nt);
            case "PostOp" -> printPostOp(nt);
            case "Args" -> printArgs(nt);
            case "Lambda" -> printLambda(nt);
            case "Ternary" -> printTernary(nt);
            default -> printChildren(nt);
        }
    }

    private void printCompilationUnit(CstNode.NonTerminal cu) {
        // New grammar: CompilationUnit <- ModuleDecl / OrdinaryUnit
        // Just print children which will dispatch to the right handler
        printChildren(cu);
    }

    private void printOrdinaryUnit(CstNode.NonTerminal ou) {
        // Print package declaration
        childByRule(ou, "PackageDecl").fold(
            () -> null,
            pkg -> {
                printNode(pkg);
                return null;
            }
        );

        // Collect and organize imports
        var imports = childrenByRule(ou, "ImportDecl");
        if (!imports.isEmpty()) {
            println();
            println();
            printOrganizedImports(imports);
        }

        // Print type declarations (one blank line after imports)
        var types = childrenByRule(ou, "TypeDecl");
        boolean first = true;
        for (var type : types) {
            if (first) {
                println();  // Single blank line between imports and first type
            } else {
                println();
                println();  // Double blank line between types
            }
            printNodeSkipLeadingTrivia(type);  // Skip leading trivia - we control blank lines
            first = false;
        }
    }

    private void printOrganizedImports(List<CstNode> imports) {
        // Group imports: pragmatica, java/javax, other, static
        var pragmatica = imports.stream()
            .filter(i -> text(i, source).contains("org.pragmatica"))
            .filter(i -> !text(i, source).contains("static"))
            .toList();

        var javaImports = imports.stream()
            .filter(i -> text(i, source).contains("java.") || text(i, source).contains("javax."))
            .filter(i -> !text(i, source).contains("static"))
            .toList();

        var otherImports = imports.stream()
            .filter(i -> !text(i, source).contains("org.pragmatica"))
            .filter(i -> !text(i, source).contains("java."))
            .filter(i -> !text(i, source).contains("javax."))
            .filter(i -> !text(i, source).contains("static"))
            .toList();

        var staticImports = imports.stream()
            .filter(i -> text(i, source).contains("static"))
            .toList();

        boolean needsBlank = false;

        if (!pragmatica.isEmpty()) {
            for (var imp : pragmatica) {
                printImportDecl((CstNode.NonTerminal) imp);
            }
            needsBlank = true;
        }

        if (!javaImports.isEmpty()) {
            if (needsBlank) println();
            for (var imp : javaImports) {
                printImportDecl((CstNode.NonTerminal) imp);
            }
            needsBlank = true;
        }

        if (!otherImports.isEmpty()) {
            if (needsBlank) println();
            for (var imp : otherImports) {
                printImportDecl((CstNode.NonTerminal) imp);
            }
            needsBlank = true;
        }

        if (!staticImports.isEmpty()) {
            if (needsBlank) println();
            for (var imp : staticImports) {
                printImportDecl((CstNode.NonTerminal) imp);
            }
        }
    }

    private void printImportDecl(CstNode.NonTerminal imp) {
        var importText = text(imp, source).trim();
        print(importText);
        println();
    }

    private void printTypeDecl(CstNode.NonTerminal type) {
        printChildren(type);
    }

    private void printMethodDecl(CstNode.NonTerminal method) {
        printChildren(method);
    }

    private void printFieldDecl(CstNode.NonTerminal field) {
        // FieldDecl <- Type VarDecls ';'
        // Print without trivia to avoid double spacing between Type and VarDecls
        for (var child : children(field)) {
            printNodeContent(child);
        }
    }

    private void printEnumBody(CstNode.NonTerminal enumBody) {
        // EnumBody <- '{' EnumConsts? (';' ClassMember*)? '}'
        var children = children(enumBody);

        // Find and print opening brace
        for (var child : children) {
            if (isTerminalWithText(child, "{")) {
                printNode(child);
                break;
            }
        }

        indentLevel++;
        println();

        // Print enum constants
        childByRule(enumBody, "EnumConsts").fold(
            () -> null,
            consts -> {
                printIndent();
                printEnumConsts((CstNode.NonTerminal) consts);
                return null;
            }
        );

        // Print class members if any (after semicolon)
        var classMembers = childrenByRule(enumBody, "ClassMember");
        for (var member : classMembers) {
            println();
            printIndent();
            printNodeSkipTrivia(member);
        }

        indentLevel--;
        println();
        printIndent();

        // Find and print closing brace
        for (var child : children) {
            if (isTerminalWithText(child, "}")) {
                printNode(child);
                break;
            }
        }
    }

    private void printRecordBody(CstNode.NonTerminal recordBody) {
        // RecordBody <- '{' RecordMember* '}'
        var allChildren = children(recordBody);

        // Check if body is actually empty (only braces, no content)
        boolean hasContent = allChildren.stream()
            .anyMatch(c -> !isTerminalWithText(c, "{") && !isTerminalWithText(c, "}"));

        if (!hasContent) {
            // Empty body - print {}
            print("{}");
        } else {
            // Non-empty - format like class body but with RecordMember
            for (var child : allChildren) {
                if (isTerminalWithText(child, "{")) {
                    printNode(child);
                    break;
                }
            }

            indentLevel++;
            println();

            boolean first = true;
            CstNode prevMember = null;
            for (var child : allChildren) {
                if (child.rule().equals("RecordMember")) {
                    boolean hasBlankLineInTrivia = hasBlankLineInLeadingTrivia(child);
                    if (!first && (needsBlankLineBefore(child, prevMember) || hasBlankLineInTrivia)) {
                        println();
                    }
                    printIndent();
                    printNodeSkipTrivia(child);
                    println();
                    first = false;
                    prevMember = child;
                }
            }

            indentLevel--;
            printIndent();

            for (var child : allChildren) {
                if (isTerminalWithText(child, "}")) {
                    printNode(child);
                    break;
                }
            }
        }
    }

    private void printEnumConsts(CstNode.NonTerminal enumConsts) {
        // EnumConsts <- EnumConst (',' EnumConst)* ','?
        var children = children(enumConsts);
        boolean first = true;
        for (var child : children) {
            if (isTerminalWithText(child, ",")) {
                print(",");
                println();
                printIndent();
            } else if ("EnumConst".equals(child.rule())) {
                if (!first) {
                    // Already printed newline after comma
                }
                printNodeContent(child);
                first = false;
            } else {
                printNode(child);
            }
        }
    }

    private void printClassBody(CstNode.NonTerminal classBody) {
        var children = children(classBody);

        // Find and print opening brace with its trivia
        for (var child : children) {
            if (isTerminalWithText(child, "{")) {
                printNode(child);
                break;
            }
        }

        indentLevel++;
        println();

        boolean first = true;
        CstNode prevMember = null;
        for (var child : children) {
            if (child.rule().equals("ClassMember")) {
                // Check if original source has a blank line before this member
                boolean hasBlankLineInTrivia = hasBlankLineInLeadingTrivia(child);
                if (!first && (needsBlankLineBefore(child, prevMember) || hasBlankLineInTrivia)) {
                    println();
                }
                printIndent();
                printNodeSkipTrivia(child);  // Skip trivia - we control layout
                println();
                first = false;
                prevMember = child;
            } else if (!isTerminalWithText(child, "{") && !isTerminalWithText(child, "}")) {
                printNode(child);
            }
        }

        indentLevel--;
        printIndent();

        // Find and print closing brace
        for (var child : children) {
            if (isTerminalWithText(child, "}")) {
                printNode(child);
                break;
            }
        }
    }

    private boolean hasBlankLineInLeadingTrivia(CstNode node) {
        // Count total newlines across all leading whitespace trivia
        int totalNewlines = 0;
        for (var trivia : node.leadingTrivia()) {
            if (trivia instanceof Trivia.Whitespace ws) {
                totalNewlines += ws.text().chars().filter(c -> c == '\n').count();
                if (totalNewlines >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private void printBlock(CstNode.NonTerminal block) {
        var children = children(block);

        // Find and print opening brace with its trivia
        for (var child : children) {
            if (isTerminalWithText(child, "{")) {
                printNode(child);
                break;
            }
        }

        indentLevel++;

        var stmts = children.stream()
            .filter(c -> c.rule().equals("BlockStmt"))
            .toList();

        if (!stmts.isEmpty()) {
            println();
            for (var stmt : stmts) {
                printIndent();
                printNodeSkipTrivia(stmt);  // Skip trivia - we control layout
                println();
            }
        }

        indentLevel--;
        printIndent();

        // Find and print closing brace with its trivia
        for (var child : children) {
            if (isTerminalWithText(child, "}")) {
                printNode(child);
                break;
            }
        }
    }

    private void printStatement(CstNode.NonTerminal stmt) {
        printChildren(stmt);
    }

    private void printExpression(CstNode.NonTerminal expr) {
        printChildren(expr);
    }

    /**
     * Print a Postfix expression (Primary PostOp*).
     * This handles method chains with JBCT alignment rules.
     */
    private void printPostfix(CstNode.NonTerminal postfix) {
        var children = children(postfix);

        // Find Primary and all PostOp children
        CstNode primary = null;
        var postOps = new java.util.ArrayList<CstNode>();

        for (var child : children) {
            if ("Primary".equals(child.rule())) {
                primary = child;
            } else if ("PostOp".equals(child.rule())) {
                postOps.add(child);
            }
        }

        // Count method call PostOps (those with parentheses)
        var methodCallPostOps = postOps.stream()
            .filter(op -> text(op, source).contains("("))
            .toList();

        boolean shouldBreakChain = methodCallPostOps.size() >= 2;

        if (shouldBreakChain && !measuringMode) {
            printMethodChainAligned(primary, postOps);
        } else {
            // No chain or measuring - print normally
            if (primary != null) {
                printNode(primary);
            }
            for (var postOp : postOps) {
                printNode(postOp);
            }
        }
    }

    /**
     * Print a method chain with JBCT alignment.
     * Chain continuation dots align to the position after the Primary.
     *
     * Example:
     * return input.map(String::trim)
     *             .map(String::toUpperCase);
     *        ^--- alignColumn (position of first `.`)
     */
    private void printMethodChainAligned(CstNode primary, java.util.List<CstNode> postOps) {
        int startColumn = currentColumn;
        int alignColumn = startColumn;

        // Print primary and calculate alignment column
        if (primary != null) {
            var primaryText = text(primary, source);
            int dotPos = primaryText.indexOf('.');
            if (dotPos >= 0) {
                // First `.` is inside Primary - align to that position
                alignColumn = startColumn + dotPos;
            }
            printNodeContent(primary);
        }

        // Save chain align for nested lambdas
        int previousChainAlign = chainAlignColumn;
        chainAlignColumn = alignColumn;

        try {
            boolean firstMethodCall = true;
            for (var postOp : postOps) {
                var postOpText = text(postOp, source).trim();
                boolean isMethodCall = postOpText.contains("(");

                if (isMethodCall && !firstMethodCall) {
                    // Continuation - break and align
                    println();
                    printAlignedTo(alignColumn);
                }

                // Print PostOp content directly without trivia interference
                printNodeContent(postOp);

                if (isMethodCall) {
                    firstMethodCall = false;
                }
            }
        } finally {
            chainAlignColumn = previousChainAlign;
        }
    }

    /**
     * Print a node's content without trivia (for controlled layout).
     * Uses printWithSpacing for proper inter-token spacing.
     */
    private void printNodeContent(CstNode node) {
        switch (node) {
            case CstNode.Terminal t -> printWithSpacing(t.text());
            case CstNode.Token tok -> printWithSpacing(tok.text());
            case CstNode.NonTerminal nt -> {
                var rule = nt.rule();
                // For Lambda nodes, use printLambda to ensure proper arrow formatting
                if ("Lambda".equals(rule)) {
                    printLambdaContent(nt);
                } else if ("Args".equals(rule)) {
                    // Dispatch to printArgs for proper line breaking
                    printArgs(nt);
                } else {
                    for (var child : children(nt)) {
                        printNodeContent(child);
                    }
                }
            }
        }
    }

    /**
     * Print lambda content with proper arrow spacing (without trivia).
     */
    private void printLambdaContent(CstNode.NonTerminal lambda) {
        var children = children(lambda);
        for (var child : children) {
            if (isTerminalWithText(child, "->")) {
                print(" -> ");  // Ensure spacing around arrow
            } else {
                printNodeContent(child);
            }
        }
    }

    private void printPrimary(CstNode.NonTerminal primary) {
        printChildren(primary);
    }

    private void printPostOp(CstNode.NonTerminal postOp) {
        printChildren(postOp);
    }

    private void printArgs(CstNode.NonTerminal args) {
        // In measuring mode, just print children without trivia (avoid infinite recursion)
        if (measuringMode) {
            for (var child : children(args)) {
                printNodeContent(child);
            }
            return;
        }

        // Measure formatted width (single-line), not raw source width
        int argsWidth = measureWidth(args);
        int totalWidth = currentColumn + argsWidth;

        // DEBUG - uncomment to trace
        // System.out.println("printArgs: currentColumn=" + currentColumn + " argsWidth=" + argsWidth + " total=" + totalWidth + " lastChar='" + lastChar + "'");
        // new Exception("printArgs called from").printStackTrace(System.out);

        if (totalWidth <= config.maxLineLength()) {
            // Fits on one line - print without trivia to avoid extra spaces
            for (var child : children(args)) {
                printNodeContent(child);
            }
        } else {
            // Break arguments onto multiple lines
            printBrokenArgs(args);
        }
    }

    private void printBrokenArgs(CstNode.NonTerminal args) {
        var children = children(args);
        int alignCol = currentColumn;  // Align to current position (after opening paren)

        boolean first = true;
        for (var child : children) {
            if (isTerminalWithText(child, ",")) {
                print(",");
                println();
                printAlignedTo(alignCol);
            } else if ("Expr".equals(child.rule())) {
                if (first) {
                    printNode(child);
                } else {
                    printNodeSkipTrivia(child);  // Skip trivia - we control alignment
                }
                first = false;
            } else {
                printNode(child);
            }
        }
    }

    private void printLambda(CstNode.NonTerminal lambda) {
        var children = children(lambda);
        boolean afterArrow = false;
        for (var child : children) {
            if (isTerminalWithText(child, "->")) {
                print(" -> ");  // Ensure spacing around arrow
                afterArrow = true;
            } else if (afterArrow) {
                printNodeSkipTrivia(child);  // Skip trivia after arrow - we added space
                afterArrow = false;
            } else {
                printNode(child);
            }
        }
    }

    private void printTernary(CstNode.NonTerminal ternary) {
        var ternaryText = text(ternary, source);

        // Check if it's actually a ternary (has ? and :)
        if (ternaryText.contains("?") && ternaryText.contains(":")) {
            // Multi-line ternary formatting
            var children = children(ternary);
            int alignCol = currentColumn;

            boolean skipNextTrivia = false;
            for (var child : children) {
                if (isTerminalWithText(child, "?")) {
                    println();
                    printAlignedTo(alignCol);
                    print("? ");
                    skipNextTrivia = true;  // Skip trivia - we already added space
                } else if (isTerminalWithText(child, ":")) {
                    println();
                    printAlignedTo(alignCol);
                    print(": ");
                    skipNextTrivia = true;  // Skip trivia - we already added space
                } else if (skipNextTrivia) {
                    printNodeSkipLeadingTrivia(child);
                    skipNextTrivia = false;
                } else {
                    printNode(child);
                }
            }
        } else {
            printChildren(ternary);
        }
    }

    private void printChildren(CstNode.NonTerminal nt) {
        for (var child : children(nt)) {
            printNode(child);
        }
    }

    private void printTrivia(List<Trivia> triviaList) {
        for (var trivia : triviaList) {
            switch (trivia) {
                case Trivia.Whitespace ws -> {
                    // Normalize whitespace - preserve newlines but normalize spaces
                    var text = ws.text();
                    long newlines = text.chars().filter(c -> c == '\n').count();
                    if (newlines > 0) {
                        for (int i = 0; i < Math.min(newlines, 2); i++) {
                            println();
                        }
                    } else if (!text.isEmpty()) {
                        print(" ");
                    }
                }
                case Trivia.LineComment lc -> {
                    print(lc.text());
                }
                case Trivia.BlockComment bc -> {
                    print(bc.text());
                }
            }
        }
    }

    // ===== Helper methods =====

    private void print(String text) {
        if (measuringMode) {
            // In measuring mode, just count characters (no newlines in measurement)
            measureBuffer += text.length();
            updateLastChars(text);
            return;
        }
        output.append(text);
        int lastNewline = text.lastIndexOf('\n');
        if (lastNewline >= 0) {
            currentColumn = text.length() - lastNewline - 1;
        } else {
            currentColumn += text.length();
        }
        updateLastChars(text);
    }

    private void updateLastChars(String text) {
        if (!text.isEmpty()) {
            if (text.length() >= 2) {
                prevChar = text.charAt(text.length() - 2);
            } else {
                prevChar = lastChar;
            }
            lastChar = text.charAt(text.length() - 1);
            // Track last word (identifier/keyword)
            if (Character.isLetter(text.charAt(0))) {
                lastWord = text;
            }
        }
    }

    private void println() {
        if (measuringMode) {
            return; // Don't print newlines when measuring
        }
        output.append("\n");
        currentColumn = 0;
        lastChar = '\n';
    }

    private void printIndent() {
        if (measuringMode) {
            return; // Skip indent in measuring mode
        }
        String indent = " ".repeat(indentLevel * config.indentSize());
        print(indent);
    }

    private void printAlignedTo(int column) {
        if (measuringMode) {
            return; // Skip alignment in measuring mode
        }
        if (currentColumn < column) {
            print(" ".repeat(column - currentColumn));
        }
    }

    private boolean isTerminalWithText(CstNode node, String text) {
        return switch (node) {
            case CstNode.Terminal t -> text.equals(t.text());
            case CstNode.Token tok -> text.equals(tok.text());
            case CstNode.NonTerminal _ -> false;
        };
    }

    private boolean needsBlankLineBefore(CstNode member, CstNode prevMember) {
        // Check if member is a simple interface method (no body)
        var memberText = text(member, source);

        // Simple heuristic: if both current and previous are simple interface methods
        // (end with ; without { }), don't add blank line
        boolean isSimple = memberText.trim().endsWith(";") && !memberText.contains("{");
        boolean prevIsSimple = prevMember != null &&
            text(prevMember, source).trim().endsWith(";") &&
            !text(prevMember, source).contains("{");

        // No blank line between consecutive simple interface methods
        if (isSimple && prevIsSimple) {
            return false;
        }

        // Add blank line otherwise
        return true;
    }
}
