package org.pragmatica.jbct.format.cst;

import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;
import org.pragmatica.jbct.parser.Java25Parser.Trivia;

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
    private final AlignmentContext alignment = new AlignmentContext();

    // Keywords that shouldn't have preceding newlines removed
    private static final Set<String> BLOCK_KEYWORDS = Set.of(
        "if", "else", "for", "while", "do", "try", "catch", "finally", "switch", "case", "default"
    );

    // Pattern for detecting method calls in chains
    private static final Pattern METHOD_CALL_PATTERN = Pattern.compile("\\.[a-zA-Z_][a-zA-Z0-9_]*\\s*\\(");

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

    /**
     * How to handle trivia (whitespace/comments) when printing nodes.
     */
    private enum TriviaMode {
        /** Print all trivia (whitespace and comments) */
        FULL,
        /** Skip whitespace, keep comments only */
        COMMENTS_ONLY,
        /** Skip leading whitespace but print trailing trivia normally */
        SKIP_LEADING
    }

    private void printNode(CstNode node) {
        printNode(node, TriviaMode.FULL);
    }

    private void printNodeSkipTrivia(CstNode node) {
        printNode(node, TriviaMode.COMMENTS_ONLY);
    }

    private void printNodeSkipLeadingTrivia(CstNode node) {
        printNode(node, TriviaMode.SKIP_LEADING);
    }

    private void printNode(CstNode node, TriviaMode mode) {
        // Handle leading trivia
        switch (mode) {
            case FULL -> printTrivia(node.leadingTrivia());
            case COMMENTS_ONLY, SKIP_LEADING -> printCommentsOnly(node.leadingTrivia());
        }

        // Print node content
        switch (node) {
            case CstNode.Terminal t -> printTerminal(t);
            case CstNode.Token tok -> printToken(tok);
            case CstNode.NonTerminal nt -> printNonTerminal(nt);
        }

        // Handle trailing trivia
        switch (mode) {
            case FULL, SKIP_LEADING -> printTrivia(node.trailingTrivia());
            case COMMENTS_ONLY -> printCommentsOnly(node.trailingTrivia());
        }
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
                    // Javadoc and multi-line block comments should be followed by newline+indent
                    println();
                    printIndent();
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
        if (!text.isEmpty()) {
            var ctx = SpacingRules.SpacingContext.of(lastChar, prevChar, lastWord, output.length());
            if (SpacingRules.needsSpaceBefore(ctx, text)) {
                print(" ");
            }
        }
        print(text);
    }

    private void printNonTerminal(CstNode.NonTerminal nt) {
        // Handle special formatting rules using pattern matching on sealed RuleId
        switch (nt.rule()) {
            case RuleId.OrdinaryUnit _ -> printOrdinaryUnit(nt);
            case RuleId.ImportDecl _ -> printImportDecl(nt);
            case RuleId.EnumBody _ -> printEnumBody(nt);
            case RuleId.RecordBody _ -> printRecordBody(nt);
            case RuleId.FieldDecl _ -> printFieldDecl(nt);
            case RuleId.ClassBody _ -> printClassBody(nt);
            case RuleId.AnnotationBody _ -> printAnnotationBody(nt);
            case RuleId.Block _ -> printBlock(nt);
            case RuleId.SwitchBlock _ -> printSwitchBlock(nt);
            case RuleId.Postfix _ -> printPostfix(nt);
            case RuleId.Args _ -> printArgs(nt);
            case RuleId.Lambda _ -> printLambda(nt);
            case RuleId.LambdaParam _ -> printLambdaParam(nt);
            case RuleId.Param _ -> printParam(nt);
            case RuleId.Params _ -> printParams(nt);
            case RuleId.Ternary _ -> printTernary(nt);
            case RuleId.Additive _ -> printAdditive(nt);
            default -> printChildren(nt);
        }
    }

    private void printOrdinaryUnit(CstNode.NonTerminal ou) {
        // Print package declaration
        childByRule(ou, RuleId.PackageDecl.class).fold(
            () -> null,
            pkg -> {
                printNode(pkg);
                return null;
            }
        );

        // Collect and organize imports
        var imports = childrenByRule(ou, RuleId.ImportDecl.class);
        if (!imports.isEmpty()) {
            println();
            println();
            printOrganizedImports(imports);
        }

        // Print type declarations (one blank line after imports)
        var types = childrenByRule(ou, RuleId.TypeDecl.class);
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

        printTerminalFrom(children, "{");
        indentLevel++;
        println();

        // Print enum constants
        childByRule(enumBody, RuleId.EnumConsts.class).fold(
            () -> null,
            consts -> {
                var leadingTrivia = consts.leadingTrivia();
                boolean hasComments = leadingTrivia.stream().anyMatch(t ->
                    t instanceof Trivia.LineComment || t instanceof Trivia.BlockComment);
                printIndent();
                if (hasComments) {
                    printCommentsOnly(leadingTrivia);
                }
                printEnumConsts((CstNode.NonTerminal) consts);
                return null;
            }
        );

        // Print class members if any (after semicolon)
        for (var member : childrenByRule(enumBody, RuleId.ClassMember.class)) {
            println();
            printIndent();
            printNodeSkipTrivia(member);
        }

        indentLevel--;
        println();
        printIndent();
        printTerminalFrom(children, "}");
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
            // Non-empty - use common braced body printer
            printBracedBody(allChildren, RuleId.RecordMember.class,
                (child, prev) -> needsBlankLineBefore(child, prev) || hasBlankLineInLeadingTrivia(child));
        }
    }

    private void printEnumConsts(CstNode.NonTerminal enumConsts) {
        // EnumConsts <- EnumConst (',' EnumConst)* ','?
        for (var child : children(enumConsts)) {
            if (isTerminalWithText(child, ",")) {
                print(",");
                println();
                printIndent();
            } else if (child.rule() instanceof RuleId.EnumConst) {
                printNodeSkipTrivia(child);
            }
        }
    }

    private void printAnnotationBody(CstNode.NonTerminal annotBody) {
        printBracedBody(children(annotBody), RuleId.AnnotationMember.class,
            (child, prev) -> hasBlankLineInLeadingTrivia(child));
    }

    private void printClassBody(CstNode.NonTerminal classBody) {
        printBracedBody(children(classBody), RuleId.ClassMember.class,
            (child, prev) -> needsBlankLineBefore(child, prev) || hasBlankLineInLeadingTrivia(child));
    }

    /**
     * Common helper for printing braced bodies (class, annotation, record members).
     */
    private void printBracedBody(List<CstNode> children, Class<? extends RuleId> memberRule,
                                  java.util.function.BiPredicate<CstNode, CstNode> needsBlankLine) {
        var hasMembers = children.stream().anyMatch(c -> memberRule.isInstance(c.rule()));

        // Print opening brace
        printTerminalFrom(children, "{");

        if (hasMembers) {
            indentLevel++;
            println();

            boolean first = true;
            CstNode prevMember = null;
            for (var child : children) {
                if (memberRule.isInstance(child.rule())) {
                    if (!first && needsBlankLine.test(child, prevMember)) {
                        println();
                    }
                    printIndent();
                    printNodeSkipTrivia(child);
                    println();
                    first = false;
                    prevMember = child;
                } else if (!isTerminalWithText(child, "{") && !isTerminalWithText(child, "}")) {
                    printNode(child);
                }
            }

            indentLevel--;
            printIndent();
        }

        // Print closing brace
        printTerminalFrom(children, "}");
    }

    /**
     * Find and print the first terminal with given text from a list of children.
     */
    private void printTerminalFrom(List<CstNode> children, String text) {
        for (var child : children) {
            if (isTerminalWithText(child, text)) {
                printNode(child);
                return;
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

        // Check if we're inside broken arguments with lambda alignment
        boolean useLambdaAlign = alignment.hasLambdaAlign();
        int lambdaAlignCol = alignment.lambdaColumn();

        // Check if we're inside a breaking chain (for lambda blocks in chains)
        boolean useChainAlign = !useLambdaAlign && alignment.chainColumn() >= 0;
        int chainAlignCol = alignment.chainColumn();

        // Find and print opening brace with its trivia
        for (var child : children) {
            if (isTerminalWithText(child, "{")) {
                printNode(child);
                break;
            }
        }

        var stmts = children.stream()
            .filter(c -> c.rule() instanceof RuleId.BlockStmt)
            .toList();

        if (!stmts.isEmpty()) {
            println();
            if (useLambdaAlign) {
                // Lambda body in broken args: align to arg column + indent
                int bodyCol = lambdaAlignCol + config.indentSize();
                for (var stmt : stmts) {
                    printAlignedTo(bodyCol);
                    printNodeSkipTrivia(stmt);  // Skip trivia - we control layout
                    println();
                }
                // Close brace aligns with lambda arg
                printAlignedTo(lambdaAlignCol);
            } else if (useChainAlign) {
                // Lambda body in chain: body aligns to chain + indent, close aligns to chain
                int bodyCol = chainAlignCol + config.indentSize();
                for (var stmt : stmts) {
                    printAlignedTo(bodyCol);
                    printNodeSkipTrivia(stmt);  // Skip trivia - we control layout
                    println();
                }
                // Close brace aligns with chain
                printAlignedTo(chainAlignCol);
            } else {
                // Normal block indentation
                indentLevel++;
                for (var stmt : stmts) {
                    printIndent();
                    printNodeSkipTrivia(stmt);  // Skip trivia - we control layout
                    println();
                }
                indentLevel--;
                printIndent();
            }
        }
        // Empty block - no indent, just close immediately: {}

        // Find and print closing brace with its trivia
        for (var child : children) {
            if (isTerminalWithText(child, "}")) {
                printNode(child);
                break;
            }
        }
    }

    private void printSwitchBlock(CstNode.NonTerminal switchBlock) {
        var children = children(switchBlock);

        // Print opening brace (spacing comes from prior token)
        print("{");
        indentLevel++;

        // Find and print switch rules
        var rules = children.stream()
            .filter(c -> c.rule() instanceof RuleId.SwitchRule)
            .toList();

        if (!rules.isEmpty()) {
            println();
            for (var rule : rules) {
                printIndent();
                printNodeSkipTrivia(rule);
                println();
            }
        }

        indentLevel--;
        printIndent();
        print("}");
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
            if (child.rule() instanceof RuleId.Primary) {
                primary = child;
            } else if (child.rule() instanceof RuleId.PostOp) {
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

        // Enter chain context for nested lambdas
        try (var ignored = alignment.enterChain(alignColumn)) {
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
                // Dispatch to specialized handlers for proper formatting
                switch (nt.rule()) {
                    case RuleId.Lambda _ -> printLambdaContent(nt);
                    case RuleId.LambdaParam _ -> printLambdaParam(nt);
                    case RuleId.Args _ -> printArgs(nt);
                    case RuleId.Block _ -> printBlock(nt);
                    case RuleId.Postfix _ -> printPostfix(nt);
                    case RuleId.Ternary _ -> printTernary(nt);
                    case RuleId.Additive _ -> printAdditive(nt);
                    default -> {
                        for (var child : children(nt)) {
                            printNodeContent(child);
                        }
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

    private void printArgs(CstNode.NonTerminal args) {
        // In measuring mode, just print children without trivia (avoid infinite recursion)
        if (measuringMode) {
            for (var child : children(args)) {
                printNodeContent(child);
            }
            return;
        }

        // Check if arguments are "complex" (contain method chains or lambdas)
        boolean hasComplexArgs = hasComplexArguments(args);

        // Measure formatted width (single-line), not raw source width
        int argsWidth = measureWidth(args);
        int totalWidth = currentColumn + argsWidth;

        if (!hasComplexArgs && totalWidth <= config.maxLineLength()) {
            // Simple args that fit on one line - print without trivia to avoid extra spaces
            for (var child : children(args)) {
                printNodeContent(child);
            }
        } else {
            // Break arguments onto multiple lines
            printBrokenArgs(args);
        }
    }

    /**
     * Check if arguments contain complex expressions that should trigger line breaking.
     * - Method chains (.foo().bar()) = complex
     * - Lambda blocks (-> {) = complex
     * - Inside breaking chain with method call args = complex
     * - Simple identifiers (a, b, c) = NOT complex even in chains
     */
    private boolean hasComplexArguments(CstNode.NonTerminal args) {
        var exprs = childrenByRule(args, RuleId.Expr.class);

        // Multiple arguments where any contains a method chain or lambda block = complex
        if (exprs.size() >= 2) {
            for (var expr : exprs) {
                var exprText = text(expr, source);
                // Check for method call patterns: .identifier( or lambda blocks
                if (containsMethodCall(exprText) || exprText.contains("-> {")) {
                    return true;
                }
            }
            // Inside a breaking chain and args have method calls (even simple ones) = complex
            if (alignment.isInBreakingChain()) {
                for (var expr : exprs) {
                    var exprText = text(expr, source);
                    // Check if any arg is a method call: identifier()
                    if (exprText.contains("(")) {
                        return true;
                    }
                }
            }
        }
        // Single argument with lambda block uses normal block indentation, not alignment
        return false;
    }

    private boolean containsMethodCall(String text) {
        // Check for method CHAIN pattern: at least 2 method calls like .foo().bar()
        // Single method calls like .trim() or .message() should NOT trigger breaking
        var matcher = METHOD_CALL_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
            if (count >= 2) {
                return true;
            }
        }
        return false;
    }

    private void printBrokenArgs(CstNode.NonTerminal args) {
        var children = children(args);
        int alignCol = currentColumn;  // Align to current position (after opening paren)

        // Push alignment column for lambda body indentation
        try (var ignored = alignment.pushLambdaAlign(alignCol)) {
            boolean first = true;
            for (var child : children) {
                if (isTerminalWithText(child, ",")) {
                    print(",");
                    println();
                    printAlignedTo(alignCol);
                } else if (child.rule() instanceof RuleId.Expr) {
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

    private void printParams(CstNode.NonTerminal params) {
        // Params <- Param (',' Param)*
        // Similar to Args - check if fits on one line, otherwise break with alignment
        if (measuringMode) {
            for (var child : children(params)) {
                printNodeContent(child);
            }
            return;
        }

        int paramsWidth = measureWidth(params);
        int totalWidth = currentColumn + paramsWidth;

        // Account for closing paren and typical suffix (") {" = 3 chars)
        // Use <= to include lines that are exactly at the limit
        if (totalWidth + 3 <= config.maxLineLength()) {
            // Fits on one line - print children normally
            printChildren(params);
        } else {
            // Break parameters onto multiple lines aligned to current position
            printBrokenParams(params);
        }
    }

    private void printBrokenParams(CstNode.NonTerminal params) {
        var children = children(params);
        int alignCol = currentColumn;

        for (var child : children) {
            if (isTerminalWithText(child, ",")) {
                print(",");
                println();
                printAlignedTo(alignCol);
            } else if (child.rule() instanceof RuleId.Param) {
                printNodeSkipTrivia(child);
            }
        }
    }

    private void printParam(CstNode.NonTerminal param) {
        // Param <- Annotation* Modifier* Type '...'? Identifier Dims?
        // Print without trivia - we control layout in broken params context
        var children = children(param);
        var paramText = text(param, source);
        boolean isVarargs = paramText.contains("...");
        boolean printedDots = false;
        for (var child : children) {
            if (isTerminalWithText(child, "...")) {
                if (!printedDots) {
                    printWithSpacing("...");
                    printedDots = true;
                }
            } else if (child.rule() instanceof RuleId.Type && isVarargs) {
                // For varargs, Type may include trailing dots from parser quirk
                // Print type without trailing dots
                var typeText = text(child, source).trim();
                if (typeText.endsWith("...")) {
                    typeText = typeText.substring(0, typeText.length() - 3);
                }
                printWithSpacing(typeText);
            } else {
                printNodeSkipTrivia(child);
            }
        }
    }

    private void printLambdaParam(CstNode.NonTerminal param) {
        // LambdaParam <- Annotation* Modifier* (('var' / Type) &lookahead)? '...'? (Identifier / '_')
        // Due to grammar quirk with failed lookahead, parser may create duplicate content:
        // For (s) -> ..., we get Type="s" and Identifier="s" - both with same text.
        // We need to print just the Identifier, skip the duplicate Type.

        var children = children(param);

        // Find all identifiers (these are the actual parameters)
        var identifierTexts = children.stream()
            .filter(c -> c.rule() instanceof RuleId.Identifier)
            .map(c -> text(c, source).trim())
            .collect(java.util.stream.Collectors.toSet());

        for (var child : children) {
            var rule = child.rule();
            var childText = text(child, source).trim();

            if (rule instanceof RuleId.Identifier) {
                // Always print identifiers
                printWithSpacing(childText);
            } else if (rule instanceof RuleId.Type) {
                // Skip Type if its text matches an Identifier (it's duplicate noise)
                // Only print if it's genuinely a type annotation like (String s) -> ...
                if (!identifierTexts.contains(childText)) {
                    printNodeContent(child);
                }
            } else if (rule instanceof RuleId.Annotation || rule instanceof RuleId.Modifier) {
                printNode(child);
            } else if (isTerminalWithText(child, "...")) {
                printWithSpacing("...");
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

    /**
     * Print additive expression with string concatenation wrapping.
     * If expression exceeds max line length and contains string literals,
     * break at each `+ "..."` and align to expression start.
     */
    private void printAdditive(CstNode.NonTerminal additive) {
        // In measuring mode, just print all children
        if (measuringMode) {
            for (var child : children(additive)) {
                printNodeContent(child);
            }
            return;
        }

        // Check if this is string concatenation (contains StringLit)
        boolean hasStringLit = containsStringLit(additive);
        if (!hasStringLit) {
            // Not string concat - print normally
            for (var child : children(additive)) {
                printNodeContent(child);
            }
            return;
        }

        // Measure total width
        int width = measureWidth(additive);
        if (currentColumn + width <= config.maxLineLength()) {
            // Fits on one line - print normally
            for (var child : children(additive)) {
                printNodeContent(child);
            }
            return;
        }

        // Need to wrap - collect operands and operators
        var children = children(additive);
        int alignCol = currentColumn;  // Align continuation to expression start

        // First child is always printed on current line
        boolean firstPrinted = false;
        boolean pendingPlus = false;

        for (var child : children) {
            var childText = text(child, source).trim();

            if (childText.equals("+")) {
                pendingPlus = true;
            } else if (childText.equals("-")) {
                // Subtraction - print on same line with spaces
                print(" - ");
            } else {
                // Operand
                if (pendingPlus) {
                    // Check if this operand starts with a string literal
                    boolean startsWithString = startsWithStringLit(child);
                    int operandWidth = measureWidth(child);
                    // Break if: starts with string AND adding " + operand" would exceed max line
                    if (startsWithString && firstPrinted && currentColumn + 3 + operandWidth > config.maxLineLength()) {
                        // Break before + "..."
                        println();
                        printAlignedTo(alignCol);
                        print("+ ");
                    } else {
                        // Keep on same line
                        print(" + ");
                    }
                    pendingPlus = false;
                }
                printNodeContent(child);
                firstPrinted = true;
            }
        }
    }

    private boolean containsStringLit(CstNode node) {
        return switch (node) {
            case CstNode.Terminal _ -> false;
            case CstNode.Token _ -> false;
            case CstNode.NonTerminal nt -> {
                if (nt.rule() instanceof RuleId.StringLit) {
                    yield true;
                }
                for (var child : children(nt)) {
                    if (containsStringLit(child)) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }

    private boolean startsWithStringLit(CstNode node) {
        return switch (node) {
            case CstNode.Terminal _ -> false;
            case CstNode.Token _ -> false;
            case CstNode.NonTerminal nt -> {
                if (nt.rule() instanceof RuleId.StringLit) {
                    yield true;
                }
                var childList = children(nt);
                if (!childList.isEmpty()) {
                    yield startsWithStringLit(childList.getFirst());
                }
                yield false;
            }
        };
    }

    private void printTrivia(List<Trivia> triviaList) {
        for (var trivia : triviaList) {
            switch (trivia) {
                case Trivia.Whitespace ws -> {
                    // Normalize whitespace - preserve newlines and add proper indentation
                    var text = ws.text();
                    long newlines = text.chars().filter(c -> c == '\n').count();
                    if (newlines > 0) {
                        for (int i = 0; i < Math.min(newlines, 2); i++) {
                            println();
                        }
                        // After newlines, add proper indentation
                        printIndent();
                    } else if (!text.isEmpty() && lastChar != ' ' && lastChar != '\t') {
                        // Only add space if we don't already have one (avoid double spacing from nested trivia)
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
