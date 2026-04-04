package org.pragmatica.jbct.format.flow;

import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;
import org.pragmatica.jbct.parser.Java25Parser.Trivia;
import org.pragmatica.lang.Option;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// Flow-based CST printer that formats purely from code structure and width.
///
/// This printer never inspects trivia (comments, whitespace) from the original source
/// for layout decisions. All formatting decisions are based on:
/// - The syntactic structure (RuleId dispatch)
/// - Width measurement (does it fit on the current line?)
/// - Alignment rules (chains, arguments, parameters)
///
/// Comments are emitted inline alongside their associated tokens but never
/// influence layout decisions (breaks, alignment, width measurement).
///
/// **Thread Safety:** Not thread-safe. Create a new instance per formatting operation.
@SuppressWarnings("JBCT-PAT-01")
final class FlowPrinter {

    // ===== Configuration and state =====
    private final FormatterConfig config;
    private final String source;
    private final StringBuilder output;
    private int currentColumn;
    private int indentLevel;
    private char lastChar;
    private char prevChar;
    private String lastWord = "";

    // Measurement mode
    private boolean measuringMode;
    private int measureBuffer;

    // Token tracking for trivia insertion
    private int tokenIndex;
    private final Map<Integer, Integer> tokenLineMap = new HashMap<>();
    private int currentLine;

    // Alignment tracking
    private final Deque<Integer> lambdaAlignStack = new ArrayDeque<>();
    private int chainColumn = -1;
    private boolean inBreakingChain;

    // Pattern for detecting method calls in chains
    private static final Pattern METHOD_CALL_PATTERN = Pattern.compile("\\.[a-zA-Z_][a-zA-Z0-9_]*\\s*\\(");

    // Spacing rule constants
    private static final Set<String> SPACE_BEFORE_PAREN_KEYWORDS = Set.of("if", "else", "for", "while", "do",
        "try", "catch", "finally", "switch", "synchronized", "assert");
    private static final Set<String> SPACE_AFTER_BRACE_KEYWORDS = Set.of("else", "catch", "finally", "while");
    private static final Set<String> SPACE_AFTER_KEYWORDS = Set.of("case", "return", "throw", "new", "yield", "assert");
    private static final Set<String> BINARY_OPS = Set.of("=", "==", "!=", "<=", ">=", "+", "-", "*", "/", "%",
        "&", "|", "^", "&&", "||", "->", "?", ":", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=");
    private static final Set<Character> BINARY_OP_CHARS = Set.of('=', '+', '-', '*', '/', '%', '&', '|', '^', '?', ':');

    FlowPrinter(FormatterConfig config, String source) {
        this.config = config;
        this.source = source;
        this.output = new StringBuilder();
        this.currentColumn = 0;
        this.indentLevel = 0;
        this.lastChar = 0;
        this.prevChar = 0;
        this.measuringMode = false;
        this.measureBuffer = 0;
        this.tokenIndex = 0;
        this.currentLine = 0;
        this.inBreakingChain = false;
    }

    /// Result of flow printing: formatted text and token-to-line mapping.
    record FlowResult(String formatted, Map<Integer, Integer> tokenLineMap) {}

    /// Pre-computed info about an operand in an additive expression.
    record OperandInfo(boolean startsWithString, int width) {}

    /// Print the CST root and return formatted text with token mapping.
    FlowResult print(CstNode root) {
        printNode(root);
        var result = output.toString()
            .lines()
            .map(String::stripTrailing)
            .collect(Collectors.joining("\n"))
            .stripTrailing() + "\n";
        return new FlowResult(result, Map.copyOf(tokenLineMap));
    }

    // ===== Measurement =====

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

    private boolean fitsOnLine(CstNode node) {
        return currentColumn + measureWidth(node) <= config.maxLineLength();
    }

    // ===== Node dispatch =====

    private void printNode(CstNode node) {
        // Emit leading comments inline (but not during measurement)
        if (!measuringMode) {
            emitLeadingComments(node);
        }
        switch (node) {
            case CstNode.Terminal t -> emitToken(t.text());
            case CstNode.Token tok -> emitToken(tok.text());
            case CstNode.NonTerminal nt -> printNonTerminal(nt);
            case CstNode.Error err -> emitToken(err.skippedText());
        }
    }

    private void printNonTerminal(CstNode.NonTerminal nt) {
        switch (nt.rule()) {
            case RuleId.CompilationUnit _ -> printOrdinaryUnit(nt);
            case RuleId.OrdinaryUnit _ -> printOrdinaryUnit(nt);
            case RuleId.ImportDecl _ -> printImportDecl(nt);
            case RuleId.EnumBody _ -> printEnumBody(nt);
            case RuleId.RecordBody _ -> printRecordBody(nt);
            case RuleId.Member _ -> printMember(nt);
            case RuleId.FieldDecl _ -> printFieldDecl(nt);
            case RuleId.ClassBody _ -> printClassBody(nt);
            case RuleId.AnnotationBody _ -> printAnnotationBody(nt);
            case RuleId.Block _ -> printBlock(nt);
            case RuleId.SwitchBlock _ -> printSwitchBlock(nt);
            case RuleId.Unary _ -> printUnary(nt);
            case RuleId.Postfix _ -> printPostfix(nt);
            case RuleId.PostOp _ -> printPostOp(nt);
            case RuleId.Args _ -> printArgs(nt);
            case RuleId.Lambda _ -> printLambda(nt);
            case RuleId.LambdaParam _ -> printLambdaParam(nt);
            case RuleId.Param _ -> printParam(nt);
            case RuleId.Params _ -> printParams(nt);
            case RuleId.Primary _ -> printPrimary(nt);
            case RuleId.RecordDecl _ -> printRecordDecl(nt);
            case RuleId.RecordComponents _ -> printRecordComponents(nt);
            case RuleId.ResourceSpec _ -> printResourceSpec(nt);
            case RuleId.Ternary _ -> printTernary(nt);
            case RuleId.Additive _ -> printAdditive(nt);
            case RuleId.TypeArgs _ -> printTypeArgs(nt);
            case RuleId.TypeParams _ -> printTypeParams(nt);
            case RuleId.MethodDecl _ -> printMethodDecl(nt);
            default -> printChildren(nt);
        }
    }

    // ===== Compilation unit and imports =====

    private void printOrdinaryUnit(CstNode.NonTerminal ou) {
        var hasPackage = childByRule(ou, RuleId.PackageDecl.class)
            .onPresent(this::printNode)
            .isPresent();

        var imports = childrenByRule(ou, RuleId.ImportDecl.class);
        var hasImports = !imports.isEmpty();
        if (hasImports) {
            newline();
            newline();
            printOrganizedImports(imports);
        }

        var types = childrenByRule(ou, RuleId.TypeDecl.class);
        boolean first = true;
        for (var type : types) {
            if (first) {
                if (hasPackage || hasImports) {
                    newline();
                    newline();
                }
            } else {
                newline();
                newline();
            }
            printNode(type);
            first = false;
        }
    }

    private void printOrganizedImports(List<CstNode> imports) {
        var pragmatica = filterImports(imports, "org.pragmatica", false);
        var javaImports = filterJavaImports(imports);
        var otherImports = filterOtherImports(imports);
        var staticImports = filterImports(imports, "static", true);

        boolean needsBlank = false;
        needsBlank = printImportGroup(pragmatica, needsBlank);
        needsBlank = printImportGroup(javaImports, needsBlank);
        needsBlank = printImportGroup(otherImports, needsBlank);
        printImportGroup(staticImports, needsBlank);
    }

    private List<CstNode> filterImports(List<CstNode> imports, String contains, boolean isStatic) {
        return imports.stream()
            .filter(i -> matchesImportFilter(i, contains, isStatic))
            .toList();
    }

    private boolean matchesImportFilter(CstNode i, String contains, boolean isStatic) {
        var t = text(i, source);
        return t.contains(contains) && (isStatic || !t.contains("static"));
    }

    private List<CstNode> filterJavaImports(List<CstNode> imports) {
        return imports.stream()
            .filter(this::isJavaImport)
            .toList();
    }

    private boolean isJavaImport(CstNode i) {
        var t = text(i, source);
        return (t.contains("java.") || t.contains("javax.")) && !t.contains("static");
    }

    private List<CstNode> filterOtherImports(List<CstNode> imports) {
        return imports.stream()
            .filter(this::isOtherImport)
            .toList();
    }

    private boolean isOtherImport(CstNode i) {
        var t = text(i, source);
        return !t.contains("org.pragmatica") && !t.contains("java.") && !t.contains("javax.") && !t.contains("static");
    }

    private boolean printImportGroup(List<CstNode> group, boolean needsBlank) {
        if (group.isEmpty()) {
            return needsBlank;
        }
        if (needsBlank) {
            newline();
        }
        for (var imp : group) {
            printImportDecl((CstNode.NonTerminal) imp);
        }
        return true;
    }

    private void printImportDecl(CstNode.NonTerminal imp) {
        var importText = text(imp, source).trim();
        emit(importText);
        newline();
    }

    // ===== Type bodies =====

    private void printClassBody(CstNode.NonTerminal classBody) {
        printBracedBody(children(classBody), RuleId.ClassMember.class);
    }

    private void printAnnotationBody(CstNode.NonTerminal annotBody) {
        printBracedBody(children(annotBody), RuleId.AnnotationMember.class);
    }

    private void printRecordBody(CstNode.NonTerminal recordBody) {
        var allChildren = children(recordBody);
        boolean hasContent = allChildren.stream()
            .anyMatch(c -> !isTerminalWithText(c, "{") && !isTerminalWithText(c, "}"));
        if (!hasContent) {
            emit("{}");
        } else {
            printBracedBody(allChildren, RuleId.RecordMember.class);
        }
    }

    private void printBracedBody(List<CstNode> children, Class<? extends RuleId> memberRule) {
        var hasMembers = children.stream().anyMatch(c -> memberRule.isInstance(c.rule()));

        emitTerminalFrom(children, "{");

        if (hasMembers) {
            indentLevel++;
            newline();
            boolean first = true;
            Option<CstNode> prevMember = Option.none();
            for (var child : children) {
                if (memberRule.isInstance(child.rule())) {
                    if (!first && BlankLineRules.needsBlankLineBetween(child, prevMember, source)) {
                        newline();
                    }
                    printIndent();
                    printNodeContent(child);
                    newline();
                    first = false;
                    prevMember = Option.some(child);
                } else if (!isTerminalWithText(child, "{") && !isTerminalWithText(child, "}")) {
                    printNode(child);
                }
            }
            indentLevel--;
            printIndent();
        }

        emitBare("}");
    }

    private void emitTerminalFrom(List<CstNode> children, String text) {
        for (var child : children) {
            if (isTerminalWithText(child, text)) {
                emitToken(text);
                return;
            }
        }
    }

    // ===== Members =====

    private void printMember(CstNode.NonTerminal member) {
        var kids = children(member);
        boolean hasRecordComponents = kids.stream()
            .anyMatch(c -> c.rule() instanceof RuleId.RecordComponents);

        if (hasRecordComponents) {
            printRecordDecl(member);
            return;
        }

        boolean hasBlock = kids.stream().anyMatch(c -> c.rule() instanceof RuleId.Block);
        boolean hasParams = kids.stream().anyMatch(c -> c.rule() instanceof RuleId.Params);

        if (hasBlock || hasParams) {
            printMethodDeclContent(member);
        } else {
            // Print children content directly to avoid recursion (Member -> printNodeContent -> printMember)
            for (var child : kids) {
                printNodeContent(child);
            }
        }
    }

    private void printFieldDecl(CstNode.NonTerminal field) {
        for (var child : children(field)) {
            printNodeContent(child);
        }
    }

    // ===== Enum body =====

    private void printEnumBody(CstNode.NonTerminal enumBody) {
        var children = children(enumBody);
        var classMembers = childrenByRule(enumBody, RuleId.ClassMember.class);

        emitTerminalFrom(children, "{");
        indentLevel++;
        newline();

        childByRule(enumBody, RuleId.EnumConsts.class)
            .onPresent(this::printEnumConstsWithIndent);

        if (!classMembers.isEmpty()) {
            emit(";");
        }

        for (var member : classMembers) {
            newline();
            printIndent();
            printNodeContent(member);
        }

        indentLevel--;
        newline();
        printIndent();
        emitBare("}");
    }

    private void printEnumConstsWithIndent(CstNode consts) {
        printIndent();
        printEnumConsts((CstNode.NonTerminal) consts);
    }

    private void printEnumConsts(CstNode.NonTerminal enumConsts) {
        var childList = children(enumConsts);
        for (int i = 0; i < childList.size(); i++) {
            var child = childList.get(i);
            if (isTerminalWithText(child, ",")) {
                if (hasEnumConstAfter(childList, i)) {
                    emit(",");
                    newline();
                    printIndent();
                }
            } else if (child.rule() instanceof RuleId.EnumConst) {
                printNodeContent(child);
            }
        }
    }

    private boolean hasEnumConstAfter(List<CstNode> childList, int fromIndex) {
        for (int j = fromIndex + 1; j < childList.size(); j++) {
            if (childList.get(j).rule() instanceof RuleId.EnumConst) {
                return true;
            }
        }
        return false;
    }

    // ===== Block =====

    private void printBlock(CstNode.NonTerminal block) {
        var children = children(block);

        boolean useLambdaAlign = !lambdaAlignStack.isEmpty();
        int lambdaAlignCol = lambdaAlignStack.isEmpty() ? -1 : lambdaAlignStack.peek();
        boolean useChainAlign = !useLambdaAlign && chainColumn >= 0;
        int chainAlignCol = chainColumn;

        // Opening brace
        for (var child : children) {
            if (isTerminalWithText(child, "{")) {
                emitToken("{");
                break;
            }
        }

        var stmts = children.stream()
            .filter(c -> c.rule() instanceof RuleId.BlockStmt)
            .toList();

        if (!stmts.isEmpty()) {
            newline();
            if (useLambdaAlign) {
                printAlignedBlockStatements(stmts, lambdaAlignCol);
                printAlignedTo(lambdaAlignCol);
            } else if (useChainAlign) {
                printAlignedBlockStatements(stmts, chainAlignCol);
                printAlignedTo(chainAlignCol);
            } else {
                indentLevel++;
                for (var stmt : stmts) {
                    printIndent();
                    printNodeContent(stmt);
                    newline();
                }
                indentLevel--;
                printIndent();
            }
        }

        // Closing brace
        for (var child : children) {
            if (isTerminalWithText(child, "}")) {
                emitBare("}");
                break;
            }
        }
    }

    private void printAlignedBlockStatements(List<CstNode> stmts, int alignCol) {
        int bodyCol = alignCol + config.indentSize();
        int savedChainCol = chainColumn;
        boolean savedBreaking = inBreakingChain;
        lambdaAlignStack.push(bodyCol);
        try {
            for (var stmt : stmts) {
                printAlignedTo(bodyCol);
                printNodeContent(stmt);
                newline();
            }
        } finally {
            if (!lambdaAlignStack.isEmpty()) {
                lambdaAlignStack.pop();
            }
            chainColumn = savedChainCol;
            inBreakingChain = savedBreaking;
        }
    }

    private void printSwitchBlock(CstNode.NonTerminal switchBlock) {
        var children = children(switchBlock);
        emit("{");
        indentLevel++;

        var rules = children.stream()
            .filter(c -> c.rule() instanceof RuleId.SwitchRule)
            .toList();

        if (!rules.isEmpty()) {
            newline();
            for (var rule : rules) {
                printIndent();
                printNodeContent(rule);
                newline();
            }
        }

        indentLevel--;
        printIndent();
        emit("}");
    }

    // ===== Chains and postfix =====

    private void printUnary(CstNode.NonTerminal unary) {
        var children = children(unary);
        CstNode primary = null;
        CstNode.NonTerminal postfix = null;
        var directPostOps = new ArrayList<CstNode>();
        var prefixChildren = new ArrayList<CstNode>();

        // Classify children, preserving order for prefix operators (!, ~, -, +, ++, --)
        boolean foundPrimary = false;
        for (var child : children) {
            if (child.rule() instanceof RuleId.Primary) {
                primary = child;
                foundPrimary = true;
            } else if (child.rule() instanceof RuleId.Postfix && child instanceof CstNode.NonTerminal nt) {
                postfix = nt;
            } else if (child.rule() instanceof RuleId.PostOp) {
                directPostOps.add(child);
            } else if (!foundPrimary) {
                prefixChildren.add(child);
            }
        }

        // Print prefix operators first (!, ~, etc.)
        for (var prefix : prefixChildren) {
            printNode(prefix);
        }

        if (primary != null && postfix != null) {
            printPostfixWithPrimary(primary, postfix);
        } else if (primary != null && !directPostOps.isEmpty()) {
            printNode(primary);
            for (var postOp : directPostOps) {
                printNode(postOp);
            }
        } else if (primary != null) {
            printNode(primary);
        } else {
            printChildren(unary);
        }
    }

    private void printPostfixWithPrimary(CstNode primary, CstNode.NonTerminal postfix) {
        var postOps = new ArrayList<CstNode>();
        for (var child : children(postfix)) {
            if (child.rule() instanceof RuleId.PostOp) {
                postOps.add(child);
            }
        }

        var dotPlusParenPostOps = postOps.stream().filter(this::isDotMethodPostOp).toList();
        boolean primaryHasMethodAccess = hasMethodAccessInPrimary(primary);
        boolean hasInvocationOfMethodInPrimary = primaryHasMethodAccess
            && postOps.stream().anyMatch(this::isBareInvocationPostOp);
        int chainLinkCount = dotPlusParenPostOps.size() + (hasInvocationOfMethodInPrimary ? 1 : 0);
        // Break chains with 3+ links always; break 2-link chains only if they don't fit
        boolean shouldBreakChain = chainLinkCount >= 3
            || (chainLinkCount >= 2 && !fitsOnLineUnary(primary, postOps));

        if (shouldBreakChain && !measuringMode) {
            printMethodChainAligned(primary, postOps, dotPlusParenPostOps, hasInvocationOfMethodInPrimary);
        } else {
            boolean canInline = !measuringMode && fitsOnLineUnary(primary, postOps);
            printNode(primary);
            for (var postOp : postOps) {
                if (canInline) {
                    printNodeContent(postOp);
                } else {
                    printNode(postOp);
                }
            }
        }
    }

    private boolean fitsOnLineUnary(CstNode primary, List<CstNode> postOps) {
        int width = measureWidth(primary);
        for (var postOp : postOps) {
            width += measureWidth(postOp);
        }
        return currentColumn + width <= config.maxLineLength();
    }

    private void printPostfix(CstNode.NonTerminal postfix) {
        var children = children(postfix);
        CstNode primary = null;
        var postOps = new ArrayList<CstNode>();
        for (var child : children) {
            if (child.rule() instanceof RuleId.Primary) {
                primary = child;
            } else if (child.rule() instanceof RuleId.PostOp) {
                postOps.add(child);
            }
        }

        var dotPlusParenPostOps = postOps.stream().filter(this::isDotMethodPostOp).toList();
        boolean primaryHasMethodAccess = primary != null && hasMethodAccessInPrimary(primary);
        boolean hasInvocationOfMethodInPrimary = primaryHasMethodAccess
            && postOps.stream().anyMatch(this::isBareInvocationPostOp);
        int chainLinkCount = dotPlusParenPostOps.size() + (hasInvocationOfMethodInPrimary ? 1 : 0);
        // Break chains with 3+ links always; break 2-link chains only if they don't fit
        boolean shouldBreakChain = chainLinkCount >= 3
            || (chainLinkCount >= 2 && !fitsOnLine(postfix));

        if (shouldBreakChain && !measuringMode) {
            printMethodChainAligned(primary, postOps, dotPlusParenPostOps, hasInvocationOfMethodInPrimary);
        } else {
            boolean canInline = !measuringMode && fitsOnLine(postfix);
            if (primary != null) {
                printNode(primary);
            }
            for (var postOp : postOps) {
                if (canInline) {
                    printNodeContent(postOp);
                } else {
                    printNode(postOp);
                }
            }
        }
    }

    private void printMethodChainAligned(CstNode primary,
                                         List<CstNode> postOps,
                                         List<CstNode> methodCallPostOps,
                                         boolean primaryHasInvocation) {
        int startColumn = currentColumn;
        int alignColumn = startColumn;
        var methodCallSet = new HashSet<>(methodCallPostOps);

        if (primary != null) {
            printNodeContent(primary);
            alignColumn = currentColumn;
        }

        int savedChainCol = chainColumn;
        boolean savedBreaking = inBreakingChain;
        chainColumn = alignColumn;
        inBreakingChain = true;
        try {
            // For 3+ dot-method PostOps, keep the first inline and break from second.
            // For 2-link chains that don't fit, break at the first.
            boolean firstMethodCall = methodCallPostOps.size() >= 2;
            boolean previousWasMultiLineBareInvoke = false;
            for (var postOp : postOps) {
                boolean isMethodCall = methodCallSet.contains(postOp);
                if (isMethodCall && !firstMethodCall) {
                    newline();
                    // If the previous bare invocation had multi-line args,
                    // align to standard indent instead of chain column
                    int effectiveAlign = previousWasMultiLineBareInvoke
                        ? indentLevel * config.indentSize()
                        : alignColumn;
                    printAlignedTo(effectiveAlign);
                }
                int lineBefore = currentLine;
                printNodeContent(postOp);
                // Track if this was a multi-line bare invocation (not a dot-method call)
                if (!isMethodCall && currentLine - lineBefore > 0) {
                    previousWasMultiLineBareInvoke = true;
                }
                if (isMethodCall) {
                    firstMethodCall = false;
                    previousWasMultiLineBareInvoke = false;
                }
            }
        } finally {
            chainColumn = savedChainCol;
            inBreakingChain = savedBreaking;
        }
    }

    private boolean isDotMethodPostOp(CstNode postOp) {
        boolean hasDot = false;
        boolean hasParen = false;
        for (var child : children(postOp)) {
            if (isTerminalWithText(child, ".")) {
                hasDot = true;
            }
            if (isTerminalWithText(child, "(")) {
                hasParen = true;
            }
        }
        return hasDot && hasParen;
    }

    private boolean isBareInvocationPostOp(CstNode postOp) {
        boolean hasParen = false;
        boolean hasDot = false;
        for (var child : children(postOp)) {
            if (isTerminalWithText(child, ".")) {
                hasDot = true;
            }
            if (isTerminalWithText(child, "(")) {
                hasParen = true;
            }
        }
        return hasParen && !hasDot;
    }

    private boolean hasMethodAccessInPrimary(CstNode primary) {
        return containsDotInIdentifierChain(primary);
    }

    private boolean containsDotInIdentifierChain(CstNode node) {
        return switch (node) {
            case CstNode.Terminal t -> ".".equals(t.text());
            case CstNode.Token _ -> false;
            case CstNode.Error _ -> false;
            case CstNode.NonTerminal nt -> {
                if (nt.rule() instanceof RuleId.QualifiedName) {
                    for (var child : children(nt)) {
                        if (containsDotInIdentifierChain(child)) {
                            yield true;
                        }
                    }
                } else if (nt.rule() instanceof RuleId.Identifier) {
                    yield false;
                } else {
                    for (var child : children(nt)) {
                        if (child.rule() instanceof RuleId.QualifiedName && containsDotInIdentifierChain(child)) {
                            yield true;
                        }
                    }
                }
                yield false;
            }
        };
    }

    private int findFirstDotPosition(CstNode node) {
        return findDotPositionHelper(node, new int[]{0});
    }

    private int findDotPositionHelper(CstNode node, int[] position) {
        return switch (node) {
            case CstNode.Terminal t -> {
                if (".".equals(t.text())) {
                    yield position[0];
                }
                position[0] += t.text().length();
                yield -1;
            }
            case CstNode.Token tok -> {
                if (".".equals(tok.text())) {
                    yield position[0];
                }
                position[0] += tok.text().length();
                yield -1;
            }
            case CstNode.NonTerminal nt -> {
                for (var child : children(nt)) {
                    int result = findDotPositionHelper(child, position);
                    if (result >= 0) {
                        yield result;
                    }
                }
                yield -1;
            }
            case CstNode.Error err -> {
                position[0] += err.skippedText().length();
                yield -1;
            }
        };
    }

    private void printPostOp(CstNode.NonTerminal postOp) {
        var children = children(postOp);
        boolean afterTypeArgs = false;
        for (var child : children) {
            if (child.rule() instanceof RuleId.TypeArgs) {
                printNode(child);
                afterTypeArgs = true;
            } else if (afterTypeArgs && child.rule() instanceof RuleId.Identifier) {
                var identText = text(child, source).trim();
                emit(identText);
                afterTypeArgs = false;
            } else if (isTerminalWithText(child, "(") && child.rule() instanceof RuleId.Args == false) {
                printNode(child);
                afterTypeArgs = false;
            } else if (child.rule() instanceof RuleId.Args) {
                printNodeContent(child);
                afterTypeArgs = false;
            } else {
                printNode(child);
                afterTypeArgs = false;
            }
        }
    }

    // ===== Arguments =====

    private void printArgs(CstNode.NonTerminal args) {
        if (measuringMode) { printChildren(args); return; }

        boolean hasComplexArgs = hasComplexArguments(args);
        if (hasComplexArgs) {
            printBrokenArgs(args);
            return;
        }

        int argsWidth = measureWidth(args);
        if (currentColumn + argsWidth <= config.maxLineLength()) {
            for (var child : children(args)) {
                printNodeContent(child);
            }
        } else {
            printBrokenArgs(args);
        }
    }

    private boolean hasComplexArguments(CstNode.NonTerminal args) {
        var exprs = childrenByRule(args, RuleId.Expr.class);
        if (exprs.size() >= 2) {
            for (var expr : exprs) {
                var exprText = text(expr, source);
                if (containsMethodCall(exprText) || exprText.contains("-> {")) {
                    return true;
                }
                if (inBreakingChain && exprText.contains("(")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsMethodCall(String text) {
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
        int alignCol = currentColumn;

        lambdaAlignStack.push(alignCol);
        try {
            for (var child : children) {
                if (isTerminalWithText(child, ",")) {
                    emit(",");
                    newline();
                    printAlignedTo(alignCol);
                } else if (child.rule() instanceof RuleId.Expr) {
                    printNodeContent(child);
                } else {
                    printNode(child);
                }
            }
        } finally {
            if (!lambdaAlignStack.isEmpty()) {
                lambdaAlignStack.pop();
            }
        }
    }

    // ===== Lambda =====

    private void printLambda(CstNode.NonTerminal lambda) {
        var children = children(lambda);
        boolean afterArrow = false;
        for (var child : children) {
            if (isTerminalWithText(child, "->")) {
                emit(" -> ");
                afterArrow = true;
            } else if (afterArrow) {
                printNodeContent(child);
                afterArrow = false;
            } else {
                printNode(child);
            }
        }
    }

    // ===== Parameters =====

    private void printParams(CstNode.NonTerminal params) {
        if (measuringMode) {
            for (var child : children(params)) {
                printNodeContent(child);
            }
            return;
        }

        int paramsWidth = measureWidth(params);
        // Account for closing paren and typical suffix (") {" = 3 chars)
        if (currentColumn + paramsWidth + 3 <= config.maxLineLength()) {
            for (var child : children(params)) {
                printNodeContent(child);
            }
        } else {
            printBrokenParams(params);
        }
    }

    private void printBrokenParams(CstNode.NonTerminal params) {
        var children = children(params);
        int alignCol = currentColumn;
        for (var child : children) {
            if (isTerminalWithText(child, ",")) {
                emit(",");
                newline();
                printAlignedTo(alignCol);
            } else if (child.rule() instanceof RuleId.Param) {
                printNodeContent(child);
            }
        }
    }

    private void printParam(CstNode.NonTerminal param) {
        var children = children(param);
        var paramText = text(param, source);
        boolean isVarargs = paramText.contains("...");
        boolean printedDots = false;

        for (var child : children) {
            if (isTerminalWithText(child, "...")) {
                if (!printedDots) {
                    emitToken("...");
                    printedDots = true;
                }
            } else if (child.rule() instanceof RuleId.Type && isVarargs) {
                var typeText = text(child, source).trim();
                if (typeText.endsWith("...")) {
                    typeText = typeText.substring(0, typeText.length() - 3);
                }
                emitToken(typeText);
            } else {
                printNodeContent(child);
            }
        }
    }

    private void printLambdaParam(CstNode.NonTerminal param) {
        var children = children(param);
        var identifierTexts = children.stream()
            .filter(c -> c.rule() instanceof RuleId.Identifier)
            .map(c -> text(c, source).trim())
            .collect(Collectors.toSet());

        for (var child : children) {
            var rule = child.rule();
            var childText = text(child, source).trim();
            if (rule instanceof RuleId.Identifier) {
                emitToken(childText);
            } else if (rule instanceof RuleId.Type) {
                if (!identifierTexts.contains(childText)) {
                    printNodeContent(child);
                }
            } else if (rule instanceof RuleId.Annotation || rule instanceof RuleId.Modifier) {
                printNode(child);
            } else if (isTerminalWithText(child, "...")) {
                emitToken("...");
            } else {
                printNode(child);
            }
        }
    }

    // ===== Primary and record =====

    private void printPrimary(CstNode.NonTerminal primary) {
        var children = children(primary);
        boolean afterOpenParen = false;
        for (var child : children) {
            if (isTerminalWithText(child, "(")) {
                printNode(child);
                afterOpenParen = true;
            } else if (afterOpenParen && child.rule() instanceof RuleId.Args) {
                printNodeContent(child);
                afterOpenParen = false;
            } else {
                printNode(child);
                afterOpenParen = false;
            }
        }
    }

    private void printRecordDecl(CstNode.NonTerminal recordDecl) {
        var children = children(recordDecl);
        boolean afterOpenParen = false;
        for (var child : children) {
            if (isTerminalWithText(child, "(")) {
                printNode(child);
                afterOpenParen = true;
            } else if (afterOpenParen && child.rule() instanceof RuleId.RecordComponents) {
                printNodeContent(child);
                afterOpenParen = false;
            } else {
                printNode(child);
                afterOpenParen = false;
            }
        }
    }

    private void printRecordComponents(CstNode.NonTerminal components) {
        if (measuringMode) {
            for (var child : children(components)) {
                printNodeContent(child);
            }
            return;
        }

        int width = measureWidth(components);
        if (currentColumn + width + 3 <= config.maxLineLength()) {
            for (var child : children(components)) {
                printNodeContent(child);
            }
        } else {
            printBrokenRecordComponents(components);
        }
    }

    private void printBrokenRecordComponents(CstNode.NonTerminal components) {
        var children = children(components);
        int alignCol = currentColumn;
        for (var child : children) {
            if (isTerminalWithText(child, ",")) {
                emit(",");
                newline();
                printAlignedTo(alignCol);
            } else if (child.rule() instanceof RuleId.RecordComp) {
                printNodeContent(child);
            }
        }
    }

    // ===== Resource spec =====

    private void printResourceSpec(CstNode.NonTerminal resourceSpec) {
        var children = children(resourceSpec);
        // In measuring mode, just measure children sequentially — no breaking logic
        if (measuringMode) {
            printChildren(resourceSpec);
            return;
        }
        // Width-based decision instead of trivia-based
        int width = measureWidth(resourceSpec);
        if (currentColumn + width <= config.maxLineLength()) {
            printChildren(resourceSpec);
            return;
        }

        boolean afterOpen = false;
        int alignCol = 0;
        boolean first = true;
        for (var child : children) {
            if (isTerminalWithText(child, "(")) {
                emitToken("(");
                alignCol = currentColumn;
                afterOpen = true;
            } else if (isTerminalWithText(child, ")")) {
                emitToken(")");
            } else if (isTerminalWithText(child, ";")) {
                emitToken(";");
            } else if (child.rule() instanceof RuleId.Resource) {
                if (afterOpen) {
                    if (!first) {
                        newline();
                        printAlignedTo(alignCol);
                    }
                    printNodeContent(child);
                    first = false;
                } else {
                    printNode(child);
                }
            }
        }
    }

    // ===== Type generics =====

    private void printTypeArgs(CstNode.NonTerminal typeArgs) {
        var children = children(typeArgs);
        boolean first = true;
        for (var child : children) {
            if (first && isTerminalWithText(child, "<")) {
                emitToken("<");
                first = false;
            } else if (isTerminalWithText(child, ">")) {
                emitToken(">");
            } else {
                printNode(child);
            }
        }
    }

    private void printTypeParams(CstNode.NonTerminal typeParams) {
        var children = children(typeParams);
        boolean first = true;
        for (var child : children) {
            if (first && isTerminalWithText(child, "<")) {
                emitToken("<");
                first = false;
            } else if (isTerminalWithText(child, ">")) {
                emitToken(">");
            } else {
                printNode(child);
            }
        }
    }

    // ===== Method declarations =====

    private void printMethodDecl(CstNode.NonTerminal methodDecl) {
        var children = children(methodDecl);
        int typeParamsIndex = -1;
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).rule() instanceof RuleId.TypeParams) {
                typeParamsIndex = i;
                break;
            }
        }

        if (typeParamsIndex == -1) {
            printChildren(methodDecl);
            return;
        }

        for (int i = 0; i <= typeParamsIndex; i++) {
            printNode(children.get(i));
        }

        var signatureWidth = measureSignatureWidth(children, typeParamsIndex);
        if (currentColumn + 1 + signatureWidth <= config.maxLineLength()) {
            emit(" ");
        } else {
            newline();
            printIndent();
        }

        for (int i = typeParamsIndex + 1; i < children.size(); i++) {
            printNodeContent(children.get(i));
        }
    }

    private void printMethodDeclContent(CstNode.NonTerminal methodDecl) {
        var children = children(methodDecl);
        int typeParamsIndex = -1;
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).rule() instanceof RuleId.TypeParams) {
                typeParamsIndex = i;
                break;
            }
        }

        if (typeParamsIndex == -1) {
            printNodeContent(methodDecl);
            return;
        }

        printNodeContent(children.get(0));
        for (int i = 1; i <= typeParamsIndex; i++) {
            printNode(children.get(i));
        }

        var signatureWidth = measureSignatureWidth(children, typeParamsIndex);
        if (currentColumn + 1 + signatureWidth <= config.maxLineLength()) {
            emit(" ");
        } else {
            newline();
            printIndent();
        }

        for (int i = typeParamsIndex + 1; i < children.size(); i++) {
            printNodeContent(children.get(i));
        }
    }

    private int measureSignatureWidth(List<CstNode> children, int typeParamsIndex) {
        var signatureText = new StringBuilder();
        for (int i = typeParamsIndex + 1; i < children.size(); i++) {
            var childText = text(children.get(i), source);
            signatureText.append(childText);
            if (childText.contains("(")) {
                break;
            }
        }
        return signatureText.toString().replaceAll("\\s+", " ").trim().length();
    }

    // ===== Ternary =====

    private void printTernary(CstNode.NonTerminal ternary) {
        var ternaryText = text(ternary, source);
        if (ternaryText.contains("?") && ternaryText.contains(":")) {
            var children = children(ternary);
            int alignCol = currentColumn;
            boolean skipNext = false;
            for (var child : children) {
                if (isTerminalWithText(child, "?")) {
                    newline();
                    printAlignedTo(alignCol);
                    emit("? ");
                    skipNext = true;
                } else if (isTerminalWithText(child, ":")) {
                    newline();
                    printAlignedTo(alignCol);
                    emit(": ");
                    skipNext = true;
                } else if (skipNext) {
                    printNodeContent(child);
                    skipNext = false;
                } else {
                    printNode(child);
                }
            }
        } else {
            printChildren(ternary);
        }
    }

    // ===== Additive (string concatenation wrapping) =====

    private void printAdditive(CstNode.NonTerminal additive) {
        if (measuringMode) {
            for (var child : children(additive)) {
                printNodeContent(child);
            }
            return;
        }

        boolean hasStringLit = containsStringLit(additive);
        if (!hasStringLit) {
            for (var child : children(additive)) {
                printNodeContent(child);
            }
            return;
        }

        int width = measureWidth(additive);
        if (currentColumn + width <= config.maxLineLength()) {
            for (var child : children(additive)) {
                printNodeContent(child);
            }
            return;
        }

        var children = children(additive);
        int alignCol = currentColumn;
        var operandInfo = new IdentityHashMap<CstNode, OperandInfo>();
        for (var child : children) {
            if (!isTerminalWithText(child, "+") && !isTerminalWithText(child, "-")) {
                operandInfo.put(child, new OperandInfo(startsWithStringLit(child), measureWidth(child)));
            }
        }

        boolean firstPrinted = false;
        boolean pendingPlus = false;
        for (var child : children) {
            if (isTerminalWithText(child, "+")) {
                pendingPlus = true;
            } else if (isTerminalWithText(child, "-")) {
                emit(" - ");
            } else {
                if (pendingPlus) {
                    var info = operandInfo.get(child);
                    boolean startsWithStr = info != null && info.startsWithString();
                    int opWidth = info != null ? info.width() : 0;
                    if (startsWithStr && firstPrinted && currentColumn + 3 + opWidth > config.maxLineLength()) {
                        newline();
                        printAlignedTo(alignCol);
                        emit("+ ");
                    } else {
                        emit(" + ");
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
            case CstNode.Error _ -> false;
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
            case CstNode.Error _ -> false;
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

    // ===== Content printing (no trivia, with spacing) =====

    private void printNodeContent(CstNode node) {
        switch (node) {
            case CstNode.Terminal t -> emitToken(t.text());
            case CstNode.Token tok -> emitToken(tok.text());
            case CstNode.Error err -> emitToken(err.skippedText());
            case CstNode.NonTerminal nt -> {
                switch (nt.rule()) {
                    case RuleId.Lambda _ -> printLambdaContent(nt);
                    case RuleId.LambdaParam _ -> printLambdaParam(nt);
                    case RuleId.Args _ -> printArgs(nt);
                    case RuleId.Block _ -> printBlock(nt);
                    case RuleId.Postfix _ -> printPostfix(nt);
                    case RuleId.PostOp _ -> printPostOp(nt);
                    case RuleId.Ternary _ -> printTernary(nt);
                    case RuleId.Additive _ -> printAdditive(nt);
                    case RuleId.Params _ -> printParams(nt);
                    case RuleId.RecordComponents _ -> printRecordComponents(nt);
                    case RuleId.TypeArgs _ -> printTypeArgs(nt);
                    case RuleId.TypeParams _ -> printTypeParams(nt);
                    case RuleId.SwitchBlock _ -> printSwitchBlock(nt);
                    case RuleId.Unary _ -> printUnary(nt);
                    case RuleId.FieldDecl _ -> printFieldDecl(nt);
                    case RuleId.Param _ -> printParam(nt);
                    case RuleId.EnumBody _ -> printEnumBody(nt);
                    case RuleId.RecordBody _ -> printRecordBody(nt);
                    case RuleId.ClassBody _ -> printClassBody(nt);
                    case RuleId.AnnotationBody _ -> printAnnotationBody(nt);
                    case RuleId.Primary _ -> printPrimary(nt);
                    case RuleId.RecordDecl _ -> printRecordDecl(nt);
                    case RuleId.ResourceSpec _ -> printResourceSpec(nt);
                    case RuleId.LambdaParams _ -> {
                        for (var child : children(nt)) {
                            printNodeContent(child);
                        }
                    }
                    default -> {
                        for (var child : children(nt)) {
                            printNodeContent(child);
                        }
                    }
                }
            }
        }
    }

    private void printLambdaContent(CstNode.NonTerminal lambda) {
        var children = children(lambda);
        for (var child : children) {
            if (isTerminalWithText(child, "->")) {
                emit(" -> ");
            } else {
                printNodeContent(child);
            }
        }
    }

    private void printChildren(CstNode.NonTerminal nt) {
        for (var child : children(nt)) {
            printNode(child);
        }
    }

    // ===== Comment emission (inline, but never affects layout decisions) =====

    private void emitLeadingComments(CstNode node) {
        for (var trivia : node.leadingTrivia()) {
            switch (trivia) {
                case Trivia.LineComment lc -> {
                    if (currentColumn > 0) {
                        newline();
                    }
                    printIndent();
                    var text = lc.text().stripTrailing();
                    output.append(text);
                    currentColumn += text.length();
                    newline();
                }
                case Trivia.BlockComment bc -> {
                    if (currentColumn > 0) {
                        newline();
                    }
                    var lines = bc.text().split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (i == 0) {
                            printIndent();
                        }
                        var line = lines[i].stripTrailing();
                        output.append(line);
                        currentColumn += line.length();
                        if (i < lines.length - 1) {
                            output.append("\n");
                            currentColumn = 0;
                            currentLine++;
                        }
                    }
                    newline();
                }
                case Trivia.Whitespace _ -> {
                    // Ignored — flow formatter controls all whitespace
                }
            }
        }
    }

    // ===== Output helpers =====

    private void emitToken(String text) {
        if (text.isEmpty()) {
            return;
        }
        if (needsSpaceBefore(text)) {
            emit(" ");
        }
        emit(text);
        if (!measuringMode) {
            tokenLineMap.put(tokenIndex, currentLine);
            tokenIndex++;
        }
    }

    /// Emit raw text without spacing check. Used for controlled output like "{" after emit.
    private void emitBare(String text) {
        emit(text);
    }

    private void emit(String text) {
        if (measuringMode) {
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
            if (Character.isLetter(text.charAt(0))) {
                lastWord = text;
            }
        }
    }

    private void newline() {
        if (measuringMode) {
            return;
        }
        output.append("\n");
        currentColumn = 0;
        lastChar = '\n';
        currentLine++;
    }

    private void printIndent() {
        if (measuringMode) {
            return;
        }
        emit(" ".repeat(indentLevel * config.indentSize()));
    }

    private void printAlignedTo(int column) {
        if (measuringMode) {
            return;
        }
        if (currentColumn < column) {
            emit(" ".repeat(column - currentColumn));
        }
    }

    private boolean isTerminalWithText(CstNode node, String text) {
        return switch (node) {
            case CstNode.Terminal t -> text.equals(t.text());
            case CstNode.Token tok -> text.equals(tok.text());
            case CstNode.NonTerminal _ -> false;
            case CstNode.Error _ -> false;
        };
    }

    // ===== Spacing rules (inlined from SpacingRules — package-private in cst) =====

    private boolean needsSpaceBefore(String text) {
        if (lastChar == 0 || lastChar == '\n' || lastChar == ' ' || lastChar == '\t') {
            return false;
        }
        char firstChar = text.charAt(0);
        if (mustNotHaveSpaceBefore(text, firstChar)) {
            return false;
        }
        return checkSpaceRules(text, firstChar);
    }

    private boolean mustNotHaveSpaceBefore(String text, char firstChar) {
        if (firstChar == ')' || firstChar == ']' || firstChar == ';' || firstChar == ',') {
            return true;
        }
        if (lastChar == '@' || lastChar == '(' || lastChar == '[') {
            return true;
        }
        if (firstChar == '.' && !text.equals("...")) {
            return true;
        }
        if (lastChar == '.' && prevChar != '.') {
            return true;
        }
        if (text.equals("::") || (lastChar == ':' && prevChar == ':')) {
            return true;
        }
        if (firstChar == '>' && lastChar == ']') {
            return true;
        }
        if (lastChar == '<') {
            return true;
        }
        if (firstChar == '?' && lastChar == '<') {
            return true;
        }
        if (firstChar == '>' && lastChar == '?') {
            return true;
        }
        return false;
    }

    private boolean checkSpaceRules(String text, char firstChar) {
        // Comma rule
        if (lastChar == ',') {
            return true;
        }
        // Closing brace keyword
        if (lastChar == '}' && SPACE_AFTER_BRACE_KEYWORDS.contains(text)) {
            return true;
        }
        // Opening brace
        if (firstChar == '{' && (lastChar == ')' || lastChar == '>' || Character.isLetterOrDigit(lastChar))) {
            return true;
        }
        // Keyword before literal
        if (SPACE_AFTER_KEYWORDS.contains(lastWord) && isLiteralStart(firstChar)) {
            return true;
        }
        // Parentheses
        if (firstChar == '(' && (isBinaryOpLastChar() || SPACE_BEFORE_PAREN_KEYWORDS.contains(lastWord))) {
            return true;
        }
        // Brackets
        if (firstChar == '[' && lastChar == ')') {
            return true;
        }
        if (lastChar == ']' && isIdentifierStart(firstChar)) {
            return true;
        }
        // Dot rules for varargs
        if (lastChar == '.' && prevChar == '.' && Character.isLetter(firstChar)) {
            return true;
        }
        // Annotation rules
        if (firstChar == '@' && lastChar == ')') {
            return true;
        }
        // Angle bracket rules
        if (text.equals("<") || text.equals(">")) {
            return checkAngleBracketRules(text, firstChar);
        }
        // Binary operators
        if (BINARY_OPS.contains(text) || isBinaryOpLastChar()) {
            return true;
        }
        // Alphanumeric / identifier boundary (includes _ and $)
        if (isIdentifierChar(lastChar) && isIdentifierStart(firstChar)) {
            return true;
        }
        // Closing paren before identifier
        if (lastChar == ')' && isIdentifierStart(firstChar)) {
            return true;
        }
        // Generic closing
        if (lastChar == '>') {
            return checkGenericClosing(firstChar);
        }
        return false;
    }

    private boolean isLiteralStart(char c) {
        return c == '"' || c == '\'' || Character.isDigit(c) || Character.isLetter(c) || c == '-' || c == '(' || c == '!';
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private boolean isBinaryOpLastChar() {
        if (output.isEmpty() && !measuringMode) {
            return false;
        }
        if (!BINARY_OP_CHARS.contains(lastChar)) {
            return false;
        }
        return !(lastChar == ':' && prevChar == ':');
    }

    private boolean checkAngleBracketRules(String text, char firstChar) {
        if (lastChar == '<' || lastChar == '>') {
            return false;
        }
        if (text.equals(">") && lastChar == '-') {
            return false;
        }
        if (Character.isLetterOrDigit(lastChar)) {
            if (!lastWord.isEmpty() && Character.isUpperCase(lastWord.charAt(0))) {
                return false;
            }
            return true;
        }
        if (lastChar == ')') {
            return true;
        }
        if (lastChar == ']') {
            return false;
        }
        return lastChar != '.';
    }

    private boolean checkGenericClosing(char firstChar) {
        if (prevChar == '-') {
            return firstChar != '{';
        }
        // Space after > before identifiers (generics) and digits (comparisons)
        return Character.isLetterOrDigit(firstChar);
    }

    /// Check if a character can appear in a Java identifier (letters, digits, _, $).
    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
