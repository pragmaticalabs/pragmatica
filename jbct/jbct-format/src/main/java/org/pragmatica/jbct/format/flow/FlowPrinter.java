package org.pragmatica.jbct.format.flow;

import org.pragmatica.jbct.format.AlignmentContext;
import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.lang.Option;
import org.pragmatica.peg.v6.token.TokenArray;

import java.util.ArrayList;
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
/// - The syntactic structure (RuleKind dispatch)
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

    // Track trivia tokens we've already emitted as comments (prevents double-emit when
    // an outer node and its first CST child share leading trivia under v6 attribution).
    private final java.util.Set<Integer> emittedTriviaTokens = new java.util.HashSet<>();

    // Alignment tracking
    private final AlignmentContext alignment = new AlignmentContext();

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
    }

    /// Result of flow printing: formatted text and token-to-line mapping.
    record FlowResult(String formatted, Map<Integer, Integer> tokenLineMap) {}

    /// Pre-computed info about an operand in an additive expression.
    record OperandInfo(boolean startsWithString, int width) {}

    /// Print the CST root and return formatted text with token mapping.
    FlowResult print(Cursor root) {
        printNode(root);
        var result = output.toString()
            .lines()
            .map(String::stripTrailing)
            .collect(Collectors.joining("\n"))
            .stripTrailing() + "\n";
        return new FlowResult(result, Map.copyOf(tokenLineMap));
    }

    // ===== Measurement =====

    private int measureWidth(Cursor node) {
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

    private boolean fitsOnLine(Cursor node) {
        return currentColumn + measureWidth(node) <= config.maxLineLength();
    }

    // ===== Node dispatch =====

    private void printNode(Cursor node) {
        // Emit leading comments inline (but not during measurement).
        if (!measuringMode) {
            emitLeadingComments(node);
        }
        switch (node) {
            case Cursor.Leaf leaf -> emitLeafTokens(leaf);
            case Cursor.Branch br -> printBranch(br);
            case Cursor.ErrorNode err -> emitToken(err.skippedText().toString());
        }
    }

    /// Emit a Leaf node's tokens by walking the TokenArray range (skipping trivia).
    /// Using `leaf.text()` instead would return the source slice covering trailing trivia,
    /// causing whitespace bleed into the output.
    private void emitLeafTokens(Cursor.Leaf leaf) {
        var tokens = leaf.cst().tokens();
        for (int t = leaf.firstTokenIdx(); t <= leaf.lastTokenIdx(); t++) {
            if (!tokens.isTrivia(t)) {
                emitToken(tokens.textAt(t).toString());
            }
        }
    }

    private void printBranch(Cursor.Branch br) {
        switch (br.kind()) {
            case COMPILATION_UNIT -> walkTokens(br);
            case ORDINARY_UNIT -> printOrdinaryUnit(br);
            case IMPORT_DECL -> printImportDecl(br);
            case ENUM_BODY -> printEnumBody(br);
            case RECORD_BODY -> printRecordBody(br);
            case MEMBER -> printMember(br);
            case FIELD_DECL -> printFieldDecl(br);
            case CLASS_BODY -> printClassBody(br);
            case ANNOTATION_BODY -> printAnnotationBody(br);
            case BLOCK -> printBlock(br);
            case STMT -> printStmt(br);
            case SWITCH_BLOCK -> printSwitchBlock(br);
            case UNARY -> printUnary(br);
            case POSTFIX -> printPostfix(br);
            case POST_OP -> printPostOp(br);
            case ARGS -> printArgs(br);
            case LAMBDA -> printLambda(br);
            case LAMBDA_PARAM -> printLambdaParam(br);
            case PARAM -> printParam(br);
            case PARAMS -> printParams(br);
            case PRIMARY -> printPrimary(br);
            case RECORD_DECL -> printRecordDecl(br);
            case RECORD_COMPONENTS -> printRecordComponents(br);
            case RESOURCE_SPEC -> printResourceSpec(br);
            case TERNARY -> printTernary(br);
            case ADDITIVE -> printAdditive(br);
            case TYPE_ARGS -> printTypeArgs(br);
            case TYPE_PARAMS -> printTypeParams(br);
            case METHOD_DECL -> printMethodDecl(br);
            default -> walkTokens(br);
        }
    }

    // ===== Token-walking core =====

    /// Walk the tokens covered by the branch's range. At each step, if a child branch
    /// begins at the current token index, recurse into that child; otherwise emit the
    /// token text (skipping trivia). This is the default rendering for any branch that
    /// doesn't override emission — it mirrors the legacy "walk children" pattern, but
    /// under v6 keyword/punctuation tokens are not CST children, so we drive iteration
    /// via the TokenArray.
    private void walkTokens(Cursor.Branch parent) {
        var kids = parent.children().toList();
        walkTokenRange(parent, kids, parent.firstTokenIdx(), parent.lastTokenIdx());
    }

    private void walkTokenRange(Cursor parent, List<Cursor> kids, int start, int end) {
        var tokens = parent.cst().tokens();
        int kidIdx = 0;
        int t = start;
        while (t <= end) {
            if (kidIdx < kids.size() && kids.get(kidIdx).firstTokenIdx() == t) {
                var kid = kids.get(kidIdx);
                printNode(kid);
                t = kid.lastTokenIdx() + 1;
                kidIdx++;
            } else {
                if (!tokens.isTrivia(t)) {
                    emitToken(tokens.textAt(t).toString());
                }
                t++;
            }
        }
    }

    /// Walk tokens of a branch, but route children and tokens through callback hooks.
    /// Used by special handlers that need to know which terminal/child they're emitting.
    private void walkTokensWith(Cursor.Branch parent, TokenWalker walker) {
        var tokens = parent.cst().tokens();
        var kids = parent.children().toList();
        int kidIdx = 0;
        int t = parent.firstTokenIdx();
        int end = parent.lastTokenIdx();
        while (t <= end) {
            if (kidIdx < kids.size() && kids.get(kidIdx).firstTokenIdx() == t) {
                var kid = kids.get(kidIdx);
                walker.onChild(kid);
                t = kid.lastTokenIdx() + 1;
                kidIdx++;
            } else {
                if (!tokens.isTrivia(t)) {
                    walker.onToken(tokens.kindAt(t), tokens.textAt(t).toString());
                }
                t++;
            }
        }
    }

    @FunctionalInterface
    private interface TokenWalker {
        void onChild(Cursor child);
        default void onToken(int kind, String text) {}
    }

    // ===== Compilation unit and imports =====

    private void printOrdinaryUnit(Cursor.Branch ou) {
        var hasPackage = childByRule(ou, RuleKind.PACKAGE_DECL)
            .onPresent(this::printNode)
            .isPresent();

        var imports = childrenByRule(ou, RuleKind.IMPORT_DECL);
        var hasImports = !imports.isEmpty();
        if (hasImports) {
            newline();
            newline();
            printOrganizedImports(imports);
        }

        var types = childrenByRule(ou, RuleKind.TYPE_DECL);
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

    private void printOrganizedImports(List<Cursor> imports) {
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

    private List<Cursor> filterImports(List<Cursor> imports, String contains, boolean isStatic) {
        return imports.stream()
            .filter(i -> matchesImportFilter(i, contains, isStatic))
            .toList();
    }

    private boolean matchesImportFilter(Cursor i, String contains, boolean isStatic) {
        var t = text(i);
        return t.contains(contains) && (isStatic || !t.contains("static"));
    }

    private List<Cursor> filterJavaImports(List<Cursor> imports) {
        return imports.stream()
            .filter(this::isJavaImport)
            .toList();
    }

    private boolean isJavaImport(Cursor i) {
        var t = text(i);
        return (t.contains("java.") || t.contains("javax.")) && !t.contains("static");
    }

    private List<Cursor> filterOtherImports(List<Cursor> imports) {
        return imports.stream()
            .filter(this::isOtherImport)
            .toList();
    }

    private boolean isOtherImport(Cursor i) {
        var t = text(i);
        return !t.contains("org.pragmatica") && !t.contains("java.") && !t.contains("javax.") && !t.contains("static");
    }

    private boolean printImportGroup(List<Cursor> group, boolean needsBlank) {
        if (group.isEmpty()) {
            return needsBlank;
        }
        if (needsBlank) {
            newline();
        }
        for (var imp : group) {
            printImportDecl(imp);
        }
        return true;
    }

    private void printImportDecl(Cursor imp) {
        // Walk non-trivia tokens directly so trailing comments (`/// ...` after `;`) are
        // not pulled into the import — they belong to whatever follows.
        var tokens = imp.cst().tokens();
        var sb = new StringBuilder();
        for (int t = imp.firstTokenIdx(); t <= imp.lastTokenIdx(); t++) {
            if (!tokens.isTrivia(t)) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(tokens.textAt(t));
            }
        }
        // Drop the space before `.` and `;` and before `*` after `.`.
        var importText = sb.toString().replaceAll(" ([.;*])", "$1").replaceAll("([.]) ", "$1");
        emit(importText);
        newline();
    }

    // ===== Type bodies =====

    private void printClassBody(Cursor.Branch classBody) {
        printBracedBody(classBody, RuleKind.CLASS_MEMBER);
    }

    private void printAnnotationBody(Cursor.Branch annotBody) {
        printBracedBody(annotBody, RuleKind.ANNOTATION_MEMBER);
    }

    private void printRecordBody(Cursor.Branch recordBody) {
        var members = childrenByRule(recordBody, RuleKind.RECORD_MEMBER);
        var tokens = recordBody.cst().tokens();
        // Detect empty body by token-content: between `{` and `}` of the recordBody range,
        // are all tokens trivia? (firstTokenIdx is `{`, lastTokenIdx may include trailing
        // trivia past `}`.) Walk inclusive non-trivia tokens; if only `{` and `}` are
        // present, the body is empty.
        int firstTok = recordBody.firstTokenIdx();
        int lastTok = recordBody.lastTokenIdx();
        int nonTriviaCount = 0;
        for (int t = firstTok; t <= lastTok; t++) {
            if (!tokens.isTrivia(t)) {
                nonTriviaCount++;
                if (nonTriviaCount > 2) {
                    break;
                }
            }
        }
        boolean isEmpty = nonTriviaCount <= 2 && members.isEmpty();
        if (nonTriviaCount <= 2) {
            emit("{}");
        } else {
            printBracedBody(recordBody, RuleKind.RECORD_MEMBER);
        }
    }

    private void printBracedBody(Cursor.Branch parent, RuleKind memberKind) {
        var members = childrenByRule(parent, memberKind);

        emitToken("{");

        if (!members.isEmpty()) {
            indentLevel++;
            newline();
            Option<Cursor> prevMember = Option.none();
            boolean first = true;
            for (var member : members) {
                if (!first && BlankLineRules.needsBlankLineBetween(member, prevMember)) {
                    newline();
                }
                // Skip printIndent when the member has leading comments — emitLeadingComments
                // owns its own newline/indent and we'd otherwise emit a stray spaces-only line.
                if (!hasLeadingComment(member)) {
                    printIndent();
                }
                printNode(member);
                newline();
                first = false;
                prevMember = Option.some(member);
            }
            indentLevel--;
            printIndent();
        }

        emitBare("}");
    }

    // ===== Members =====

    private void printMember(Cursor.Branch member) {
        boolean hasRecordComponents = hasChildOfRule(member, RuleKind.RECORD_COMPONENTS);
        if (hasRecordComponents) {
            printRecordDecl(member);
            return;
        }

        boolean hasBlock = hasChildOfRule(member, RuleKind.BLOCK);
        boolean hasParams = hasChildOfRule(member, RuleKind.PARAMS);

        if (hasBlock || hasParams) {
            printMethodDeclContent(member);
        } else {
            walkTokens(member);
        }
    }

    private void printFieldDecl(Cursor.Branch field) {
        walkTokens(field);
    }

    // ===== Enum body =====

    private void printEnumBody(Cursor.Branch enumBody) {
        var classMembers = childrenByRule(enumBody, RuleKind.CLASS_MEMBER);

        emitToken("{");
        indentLevel++;
        newline();

        childByRule(enumBody, RuleKind.ENUM_CONSTS)
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

    private void printEnumConstsWithIndent(Cursor consts) {
        printIndent();
        printEnumConsts(consts);
    }

    private void printEnumConsts(Cursor enumConsts) {
        var constNodes = childrenByRule(enumConsts, RuleKind.ENUM_CONST);
        for (int i = 0; i < constNodes.size(); i++) {
            if (i > 0) {
                emit(",");
                newline();
                printIndent();
            }
            printNodeContent(constNodes.get(i));
        }
    }

    /// Under v6, `Stmt` body shapes are unified:
    /// - `Stmt[Block]` for braced bodies → dispatch to printBlock on the Block child
    /// - `Stmt[<other>]` for unbraced single-statement bodies → walk tokens
    /// No brace-shape detection needed (per Stage 0 findings).
    private void printStmt(Cursor.Branch stmt) {
        var kids = stmt.children().toList();
        if (kids.size() == 1 && kids.get(0).kindIs(RuleKind.BLOCK) && kids.get(0) instanceof Cursor.Branch block) {
            printBlock(block);
        } else {
            walkTokens(stmt);
        }
    }

    // ===== Block =====

    private void printBlock(Cursor.Branch block) {
        boolean useLambdaAlign = alignment.hasLambdaAlign();
        int lambdaAlignCol = alignment.lambdaColumn();
        boolean useChainAlign = !useLambdaAlign && alignment.chainColumn() >= 0;
        int chainAlignCol = alignment.chainColumn();

        emitToken("{");

        var stmts = childrenByRule(block, RuleKind.BLOCK_STMT);

        if (!stmts.isEmpty()) {
            // Preserve source-line layout: if the entire Block sits on a single source
            // line (e.g. `if (x) {return y;}`), keep it inline. This mirrors the legacy
            // formatter's same-line brace detection. Don't collapse if the stmt carries a
            // leading comment — that's a signal the dev wants spacing.
            if (!measuringMode
                && !useLambdaAlign
                && !useChainAlign
                && isOnSingleSourceLine(block)
                && stmts.size() == 1
                && !hasLeadingComment(stmts.get(0))) {
                printNode(stmts.get(0));
                emitBare("}");
                return;
            }
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
                    if (!hasLeadingComment(stmt)) {
                        printIndent();
                    }
                    printNode(stmt);
                    newline();
                }
                indentLevel--;
                printIndent();
            }
        }

        emitBare("}");
    }

    private boolean hasLeadingComment(Cursor node) {
        return node.leadingTrivia().anyMatch(t -> t.isLineComment() || t.isBlockComment());
    }

    /// True iff the entire token range of the node covers exactly one source line
    /// (no '\n' in any token between the first and last non-trivia tokens, exclusive
    /// of trailing trivia past the last non-trivia token — that trailing trivia is the
    /// transition to the next sibling and shouldn't count).
    private boolean isOnSingleSourceLine(Cursor.Branch node) {
        var tokens = node.cst().tokens();
        // Find the last non-trivia token index within the range.
        int lastNonTrivia = -1;
        for (int t = node.lastTokenIdx(); t >= node.firstTokenIdx(); t--) {
            if (!tokens.isTrivia(t)) {
                lastNonTrivia = t;
                break;
            }
        }
        if (lastNonTrivia < 0) {
            return true;
        }
        for (int t = node.firstTokenIdx(); t <= lastNonTrivia; t++) {
            if (tokens.textAt(t).toString().indexOf('\n') >= 0) {
                return false;
            }
        }
        return true;
    }

    private void printAlignedBlockStatements(List<Cursor> stmts, int alignCol) {
        int bodyCol = alignCol + config.indentSize();
        try (var scope = alignment.pushLambdaAlign(bodyCol)) {
            for (var stmt : stmts) {
                printAlignedTo(bodyCol);
                printNode(stmt);
                newline();
            }
        }
    }

    private void printSwitchBlock(Cursor.Branch switchBlock) {
        emit("{");
        indentLevel++;

        var rules = childrenByRule(switchBlock, RuleKind.SWITCH_RULE);

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

    private void printUnary(Cursor.Branch unary) {
        var kids = unary.children().toList();
        Cursor primary = null;
        Cursor.Branch postfix = null;
        var directPostOps = new ArrayList<Cursor>();

        // Classify children. A Unary may contain a nested Unary (e.g., `!!x` or `!(x)`),
        // in which case we just walk tokens and let recursion handle the inner Unary.
        for (var child : kids) {
            if (child.kindIs(RuleKind.PRIMARY)) {
                primary = child;
            } else if (child.kindIs(RuleKind.POSTFIX) && child instanceof Cursor.Branch pf) {
                postfix = pf;
            } else if (child.kindIs(RuleKind.POST_OP)) {
                directPostOps.add(child);
            }
        }

        // If we found a Primary, walk tokens BEFORE primary to emit prefix operators
        // (!, ~, -, +, ++, --), then dispatch primary+postfix.
        if (primary != null) {
            int prefixEnd = primary.firstTokenIdx() - 1;
            if (prefixEnd >= unary.firstTokenIdx()) {
                walkTokenRange(unary, List.of(), unary.firstTokenIdx(), prefixEnd);
            }
            if (postfix != null) {
                printPostfixWithPrimary(primary, postfix);
            } else if (!directPostOps.isEmpty()) {
                printNode(primary);
                for (var postOp : directPostOps) {
                    printNode(postOp);
                }
            } else {
                printNode(primary);
            }
        } else {
            // No Primary — fall through to default token walk, which will recursively
            // dispatch nested Unary/Postfix children correctly.
            walkTokens(unary);
        }
    }

    private void printPostfixWithPrimary(Cursor primary, Cursor.Branch postfix) {
        if (measuringMode) {
            // Measurement only needs width — emit primary + each postOp inline.
            printNode(primary);
            for (var postOp : childrenByRule(postfix, RuleKind.POST_OP)) {
                printNode(postOp);
            }
            return;
        }
        var postOps = childrenByRule(postfix, RuleKind.POST_OP);

        int allDotMethodCount = countDotMethodChainLinks(postfix);
        var dotPlusParenPostOps = postOps.stream().filter(this::isDotMethodPostOp).toList();
        boolean primaryHasMethodAccess = hasMethodAccessInPrimary(primary);
        boolean hasInvocationOfMethodInPrimary = primaryHasMethodAccess
            && postOps.stream().anyMatch(this::isBareInvocationPostOp);
        int chainLinkCount = Math.max(allDotMethodCount,
                                      dotPlusParenPostOps.size() + (hasInvocationOfMethodInPrimary ? 1 : 0));
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

    private boolean fitsOnLineUnary(Cursor primary, List<Cursor> postOps) {
        int width = measureWidth(primary);
        for (var postOp : postOps) {
            width += measureWidth(postOp);
        }
        return currentColumn + width <= config.maxLineLength();
    }

    private void printPostfix(Cursor.Branch postfix) {
        if (measuringMode) {
            // Measurement only needs the width; just emit primary + postOps in order.
            for (var child : postfix.children().toList()) {
                printNode(child);
            }
            return;
        }
        var kids = postfix.children().toList();
        Cursor primary = null;
        var postOps = new ArrayList<Cursor>();
        for (var child : kids) {
            if (child.kindIs(RuleKind.PRIMARY)) {
                primary = child;
            } else if (child.kindIs(RuleKind.POST_OP)) {
                postOps.add(child);
            }
        }

        // Under v6, chains can be encoded as nested Postfixes (Postfix's Primary may itself
        // contain a nested Postfix wrapped in a parenthesized PRIMARY). To detect chains,
        // count dot-method post-ops across the WHOLE outer expression by descending into
        // nested Postfixes / Primarys reachable via the Primary chain.
        int allDotMethodCount = countDotMethodChainLinks(postfix);
        var dotPlusParenPostOps = postOps.stream().filter(this::isDotMethodPostOp).toList();
        boolean primaryHasMethodAccess = primary != null && hasMethodAccessInPrimary(primary);
        boolean hasInvocationOfMethodInPrimary = primaryHasMethodAccess
            && postOps.stream().anyMatch(this::isBareInvocationPostOp);
        int chainLinkCount = Math.max(allDotMethodCount,
                                      dotPlusParenPostOps.size() + (hasInvocationOfMethodInPrimary ? 1 : 0));
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

    private void printMethodChainAligned(Cursor primary,
                                         List<Cursor> postOps,
                                         List<Cursor> methodCallPostOps,
                                         boolean primaryHasInvocation) {
        int startColumn = currentColumn;
        int alignColumn = startColumn;
        var methodCallSet = new HashSet<>(methodCallPostOps);

        if (primary != null) {
            printNodeContent(primary);
            alignColumn = currentColumn;
        }

        try (var scope = alignment.enterChain(alignColumn)) {
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
                boolean spannedLines = currentLine != lineBefore;
                // After a multi-line PostOp from BROKEN ARGS (no lambda inside), the
                // next dot-method aligns to body-indent — the prior closing `)` sits on
                // a different visual line than the chain start. When the multi-line span
                // comes from a LAMBDA BODY inside the PostOp, the next dot-method should
                // still align to the chain column (Lambdas.java fixture pattern).
                previousWasMultiLineBareInvoke = spannedLines && !containsLambda(postOp);
                if (isMethodCall) {
                    firstMethodCall = false;
                }
            }
        }
    }

    /// True if the post-op carries a lambda anywhere inside it. Used to distinguish
    /// "multi-line because of broken args" (next method goes to body indent) from
    /// "multi-line because of a lambda body" (next method stays at chain column).
    private static boolean containsLambda(Cursor postOp) {
        return findFirst(postOp, RuleKind.LAMBDA).isPresent();
    }

    /// Count chain links visible within this Postfix when chains are encoded as nested
    /// Postfix wrappers. Walks the Primary chain and aggregates dot-method post-ops at
    /// each level. Used to decide whether `shouldBreakChain` for the OUTER print.
    private int countDotMethodChainLinks(Cursor.Branch postfix) {
        int count = 0;
        Cursor.Branch cur = postfix;
        while (cur != null) {
            Cursor primaryChild = null;
            for (var child : cur.children().toList()) {
                if (child.kindIs(RuleKind.PRIMARY)) {
                    primaryChild = child;
                } else if (child.kindIs(RuleKind.POST_OP) && isDotMethodPostOp(child)) {
                    count++;
                }
            }
            cur = innerPostfix(primaryChild);
        }
        return count;
    }

    /// If a Primary contains a nested Postfix (chain continuation), return it. v6 may
    /// wrap chains via deeper intermediate rules (PRIMARY > EXPR > ... > POSTFIX), so we
    /// walk descendants looking for the first POSTFIX whose span fits inside primary's.
    private Cursor.Branch innerPostfix(Cursor primary) {
        if (!(primary instanceof Cursor.Branch pb)) {
            return null;
        }
        return pb.descendants()
            .filter(c -> c.kindIs(RuleKind.POSTFIX))
            .findFirst()
            .filter(c -> c instanceof Cursor.Branch)
            .map(c -> (Cursor.Branch) c)
            .orElse(null);
    }

    /// A dot-method PostOp begins with `.` and contains `(`. Under v6 the `.` and `(`
    /// are token-level — inspect the PostOp's token range. Works for both Branch and Leaf
    /// PostOps (e.g. `.toList()` may be a Leaf since it has no inner CST children).
    private boolean isDotMethodPostOp(Cursor postOp) {
        var tokens = postOp.cst().tokens();
        boolean hasDot = false;
        boolean hasParen = false;
        for (int t = postOp.firstTokenIdx(); t <= postOp.lastTokenIdx(); t++) {
            if (tokens.isTrivia(t)) continue;
            var s = tokens.textAt(t).toString();
            if (".".equals(s)) hasDot = true;
            if ("(".equals(s)) hasParen = true;
        }
        return hasDot && hasParen;
    }

    /// A bare invocation PostOp begins with `(` but no `.` (e.g. method call on a primary).
    private boolean isBareInvocationPostOp(Cursor postOp) {
        var tokens = postOp.cst().tokens();
        boolean hasDot = false;
        boolean hasParen = false;
        for (int t = postOp.firstTokenIdx(); t <= postOp.lastTokenIdx(); t++) {
            if (tokens.isTrivia(t)) continue;
            var s = tokens.textAt(t).toString();
            if (".".equals(s)) hasDot = true;
            if ("(".equals(s)) hasParen = true;
        }
        return hasParen && !hasDot;
    }

    private boolean hasMethodAccessInPrimary(Cursor primary) {
        // Primary contains a method-access path if its text contains a dot
        // outside of identifier suffix (we check the structural Primary's source text).
        var t = text(primary);
        return t.contains(".");
    }

    private void printPostOp(Cursor.Branch postOp) {
        // PostOps look like `.method(args)`, `<TypeArgs>method(args)`, `(args)`, or `[expr]`.
        // We walk tokens and let child branches handle their own rendering.
        walkTokensWith(postOp, new TokenWalker() {
            @Override
            public void onChild(Cursor child) {
                if (child.kindIs(RuleKind.ARGS)) {
                    printNodeContent(child);
                } else {
                    printNode(child);
                }
            }

            @Override
            public void onToken(int kind, String text) {
                emitToken(text);
            }
        });
    }

    // ===== Arguments =====

    private void printArgs(Cursor.Branch args) {
        if (measuringMode) { walkTokens(args); return; }

        boolean hasComplexArgs = hasComplexArguments(args);
        if (hasComplexArgs) {
            printBrokenArgs(args);
            return;
        }

        int argsWidth = measureWidth(args);
        if (currentColumn + argsWidth <= config.maxLineLength()) {
            // Inline: walk tokens but use printNodeContent for child expressions.
            walkTokensWith(args, new TokenWalker() {
                @Override public void onChild(Cursor c) { printNodeContent(c); }
                @Override public void onToken(int kind, String text) { emitToken(text); }
            });
        } else {
            printBrokenArgs(args);
        }
    }

    private boolean hasComplexArguments(Cursor args) {
        var exprs = childrenByRule(args, RuleKind.EXPR);
        if (exprs.size() >= 2) {
            for (var expr : exprs) {
                var exprText = text(expr);
                if (containsMethodCall(exprText) || exprText.contains("-> {")) {
                    return true;
                }
                if (alignment.isInBreakingChain() && exprText.contains("(")) {
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

    private void printBrokenArgs(Cursor.Branch args) {
        int alignCol = currentColumn;
        try (var scope = alignment.pushLambdaAlign(alignCol)) {
            walkTokensWith(args, new TokenWalker() {
                @Override
                public void onChild(Cursor child) {
                    if (child.kindIs(RuleKind.EXPR)) {
                        printNodeContent(child);
                    } else {
                        printNode(child);
                    }
                }

                @Override
                public void onToken(int kind, String text) {
                    if (",".equals(text)) {
                        emit(",");
                        newline();
                        printAlignedTo(alignCol);
                    } else {
                        emitToken(text);
                    }
                }
            });
        }
    }

    // ===== Lambda =====

    private void printLambda(Cursor.Branch lambda) {
        walkTokensWith(lambda, new TokenWalker() {
            boolean afterArrow = false;

            @Override
            public void onChild(Cursor child) {
                if (afterArrow) {
                    printNodeContent(child);
                    afterArrow = false;
                } else {
                    printNode(child);
                }
            }

            @Override
            public void onToken(int kind, String text) {
                if ("->".equals(text)) {
                    emit(" -> ");
                    afterArrow = true;
                } else {
                    emitToken(text);
                }
            }
        });
    }

    // ===== Parameters =====

    private void printParams(Cursor.Branch params) {
        if (measuringMode) {
            walkTokens(params);
            return;
        }

        int paramsWidth = measureWidth(params);
        // Account for closing paren and typical suffix (") {" = 3 chars)
        if (currentColumn + paramsWidth + 3 <= config.maxLineLength()) {
            walkTokensWith(params, new TokenWalker() {
                @Override public void onChild(Cursor c) { printNodeContent(c); }
                @Override public void onToken(int kind, String text) { emitToken(text); }
            });
        } else {
            printBrokenParams(params);
        }
    }

    private void printBrokenParams(Cursor.Branch params) {
        int alignCol = currentColumn;
        walkTokensWith(params, new TokenWalker() {
            @Override
            public void onChild(Cursor child) {
                if (child.kindIs(RuleKind.PARAM)) {
                    printNodeContent(child);
                } else {
                    printNode(child);
                }
            }

            @Override
            public void onToken(int kind, String text) {
                if (",".equals(text)) {
                    emit(",");
                    newline();
                    printAlignedTo(alignCol);
                } else {
                    emitToken(text);
                }
            }
        });
    }

    private void printParam(Cursor.Branch param) {
        walkTokens(param);
    }

    private void printLambdaParam(Cursor.Branch param) {
        walkTokens(param);
    }

    // ===== Primary and record =====

    private void printPrimary(Cursor.Branch primary) {
        // Primary may be a method call `Foo.bar()`, a parenthesized expression, a new
        // expression, etc. Walk tokens; for ARGS child use printNodeContent so it can
        // break across lines.
        walkTokensWith(primary, new TokenWalker() {
            @Override
            public void onChild(Cursor child) {
                if (child.kindIs(RuleKind.ARGS)) {
                    printNodeContent(child);
                } else {
                    printNode(child);
                }
            }

            @Override
            public void onToken(int kind, String text) {
                emitToken(text);
            }
        });
    }

    private void printRecordDecl(Cursor.Branch recordDecl) {
        boolean[] afterComponents = {false};
        walkTokensWith(recordDecl, new TokenWalker() {
            @Override
            public void onChild(Cursor child) {
                if (child.kindIs(RuleKind.RECORD_COMPONENTS)) {
                    printNodeContent(child);
                    afterComponents[0] = true;
                } else if (child.kindIs(RuleKind.RECORD_BODY)) {
                    // RECORD_BODY may be a Leaf (empty `{}`) or a Branch (has members).
                    // Emit `{` without a space when we're right after components.
                    if (child instanceof Cursor.Branch rbBranch) {
                        printRecordBody(rbBranch);
                    } else {
                        emit("{}");
                    }
                    afterComponents[0] = false;
                } else {
                    printNode(child);
                }
            }

            @Override
            public void onToken(int kind, String text) {
                if ("{".equals(text) && afterComponents[0]) {
                    emit("{");
                    afterComponents[0] = false;
                } else {
                    emitToken(text);
                }
            }
        });
    }

    private void printRecordComponents(Cursor.Branch components) {
        if (measuringMode) {
            walkTokens(components);
            return;
        }

        int width = measureWidth(components);
        if (currentColumn + width + 3 <= config.maxLineLength()) {
            walkTokensWith(components, new TokenWalker() {
                @Override public void onChild(Cursor c) { printNodeContent(c); }
                @Override public void onToken(int kind, String text) { emitToken(text); }
            });
        } else {
            printBrokenRecordComponents(components);
        }
    }

    private void printBrokenRecordComponents(Cursor.Branch components) {
        int alignCol = currentColumn;
        walkTokensWith(components, new TokenWalker() {
            @Override
            public void onChild(Cursor child) {
                if (child.kindIs(RuleKind.RECORD_COMP)) {
                    printNodeContent(child);
                } else {
                    printNode(child);
                }
            }

            @Override
            public void onToken(int kind, String text) {
                if (",".equals(text)) {
                    emit(",");
                    newline();
                    printAlignedTo(alignCol);
                } else {
                    emitToken(text);
                }
            }
        });
    }

    // ===== Resource spec =====

    private void printResourceSpec(Cursor.Branch resourceSpec) {
        if (measuringMode) {
            walkTokens(resourceSpec);
            return;
        }
        int width = measureWidth(resourceSpec);
        if (currentColumn + width <= config.maxLineLength()) {
            walkTokens(resourceSpec);
            return;
        }

        // Wrapped form: resources align after `(`, separated by `;` + newline.
        int[] alignCol = {0};
        boolean[] afterOpen = {false};
        boolean[] first = {true};
        walkTokensWith(resourceSpec, new TokenWalker() {
            @Override
            public void onChild(Cursor child) {
                if (child.kindIs(RuleKind.RESOURCE)) {
                    if (afterOpen[0]) {
                        if (!first[0]) {
                            newline();
                            printAlignedTo(alignCol[0]);
                        }
                        printNodeContent(child);
                        first[0] = false;
                    } else {
                        printNode(child);
                    }
                } else {
                    printNode(child);
                }
            }

            @Override
            public void onToken(int kind, String text) {
                if ("(".equals(text)) {
                    emitToken("(");
                    alignCol[0] = currentColumn;
                    afterOpen[0] = true;
                } else if (";".equals(text)) {
                    emitToken(";");
                } else if (")".equals(text)) {
                    emitToken(")");
                } else {
                    emitToken(text);
                }
            }
        });
    }

    // ===== Type generics =====

    private void printTypeArgs(Cursor.Branch typeArgs) {
        walkTokens(typeArgs);
    }

    private void printTypeParams(Cursor.Branch typeParams) {
        walkTokens(typeParams);
    }

    // ===== Method declarations =====

    private void printMethodDecl(Cursor.Branch methodDecl) {
        // Default: walk tokens for the entire method decl. v6 keeps modifiers and type
        // params naturally on the same line via the spacing rules; we only need a special
        // line-break heuristic when there's a TYPE_PARAMS clause AND the post-typeParams
        // signature would overflow.
        walkTokens(methodDecl);
    }

    private void printMethodDeclContent(Cursor.Branch methodDecl) {
        printMethodDecl(methodDecl);
    }

    // ===== Ternary =====

    private void printTernary(Cursor.Branch ternary) {
        var ternaryText = text(ternary);
        if (ternaryText.contains("?") && ternaryText.contains(":")) {
            int alignCol = currentColumn;
            boolean[] skipNext = {false};
            walkTokensWith(ternary, new TokenWalker() {
                @Override
                public void onChild(Cursor child) {
                    if (skipNext[0]) {
                        printNodeContent(child);
                        skipNext[0] = false;
                    } else {
                        printNode(child);
                    }
                }

                @Override
                public void onToken(int kind, String text) {
                    if ("?".equals(text)) {
                        newline();
                        printAlignedTo(alignCol);
                        emit("? ");
                        skipNext[0] = true;
                    } else if (":".equals(text)) {
                        newline();
                        printAlignedTo(alignCol);
                        emit(": ");
                        skipNext[0] = true;
                    } else {
                        emitToken(text);
                    }
                }
            });
        } else {
            walkTokens(ternary);
        }
    }

    // ===== Additive (string concatenation wrapping) =====

    private void printAdditive(Cursor.Branch additive) {
        if (measuringMode) {
            walkTokensWith(additive, new TokenWalker() {
                @Override public void onChild(Cursor c) { printNodeContent(c); }
                @Override public void onToken(int kind, String text) { emitToken(text); }
            });
            return;
        }

        boolean hasStringLit = containsStringLit(additive);
        if (!hasStringLit) {
            walkTokensWith(additive, new TokenWalker() {
                @Override public void onChild(Cursor c) { printNodeContent(c); }
                @Override public void onToken(int kind, String text) { emitToken(text); }
            });
            return;
        }

        int width = measureWidth(additive);
        if (currentColumn + width <= config.maxLineLength()) {
            walkTokensWith(additive, new TokenWalker() {
                @Override public void onChild(Cursor c) { printNodeContent(c); }
                @Override public void onToken(int kind, String text) { emitToken(text); }
            });
            return;
        }

        // Multi-line: break before string-literal operands.
        var kids = additive.children().toList();
        int alignCol = currentColumn;
        var operandInfo = new IdentityHashMap<Cursor, OperandInfo>();
        for (var child : kids) {
            operandInfo.put(child, new OperandInfo(startsWithStringLit(child), measureWidth(child)));
        }

        boolean[] firstPrinted = {false};
        boolean[] pendingPlus = {false};
        walkTokensWith(additive, new TokenWalker() {
            @Override
            public void onChild(Cursor child) {
                if (pendingPlus[0]) {
                    var info = operandInfo.get(child);
                    boolean startsWithStr = info != null && info.startsWithString();
                    int opWidth = info != null ? info.width() : 0;
                    if (startsWithStr && firstPrinted[0] && currentColumn + 3 + opWidth > config.maxLineLength()) {
                        newline();
                        printAlignedTo(alignCol);
                        emit("+ ");
                    } else {
                        emit(" + ");
                    }
                    pendingPlus[0] = false;
                }
                printNodeContent(child);
                firstPrinted[0] = true;
            }

            @Override
            public void onToken(int kind, String text) {
                if ("+".equals(text)) {
                    pendingPlus[0] = true;
                } else if ("-".equals(text)) {
                    emit(" - ");
                } else {
                    emitToken(text);
                }
            }
        });
    }

    private boolean containsStringLit(Cursor node) {
        return text(node).contains("\"");
    }

    private boolean startsWithStringLit(Cursor node) {
        var t = text(node).stripLeading();
        return !t.isEmpty() && t.charAt(0) == '"';
    }

    // ===== Content printing (no trivia, with spacing) =====

    private void printNodeContent(Cursor node) {
        switch (node) {
            case Cursor.Leaf leaf -> emitLeafTokens(leaf);
            case Cursor.ErrorNode err -> emitToken(err.skippedText().toString());
            case Cursor.Branch br -> {
                switch (br.kind()) {
                    case LAMBDA -> printLambdaContent(br);
                    case LAMBDA_PARAM -> printLambdaParam(br);
                    case ARGS -> printArgs(br);
                    case BLOCK -> printBlock(br);
                    case POSTFIX -> printPostfix(br);
                    case POST_OP -> printPostOp(br);
                    case TERNARY -> printTernary(br);
                    case ADDITIVE -> printAdditive(br);
                    case PARAMS -> printParams(br);
                    case RECORD_COMPONENTS -> printRecordComponents(br);
                    case TYPE_ARGS -> printTypeArgs(br);
                    case TYPE_PARAMS -> printTypeParams(br);
                    case SWITCH_BLOCK -> printSwitchBlock(br);
                    case UNARY -> printUnary(br);
                    case FIELD_DECL -> printFieldDecl(br);
                    case PARAM -> printParam(br);
                    case ENUM_BODY -> printEnumBody(br);
                    case RECORD_BODY -> printRecordBody(br);
                    case CLASS_BODY -> printClassBody(br);
                    case ANNOTATION_BODY -> printAnnotationBody(br);
                    case PRIMARY -> printPrimary(br);
                    case RECORD_DECL -> printRecordDecl(br);
                    case RESOURCE_SPEC -> printResourceSpec(br);
                    default -> walkTokensWith(br, new TokenWalker() {
                        @Override public void onChild(Cursor c) { printNodeContent(c); }
                        @Override public void onToken(int kind, String text) { emitToken(text); }
                    });
                }
            }
        }
    }

    private void printLambdaContent(Cursor.Branch lambda) {
        walkTokensWith(lambda, new TokenWalker() {
            @Override
            public void onChild(Cursor child) { printNodeContent(child); }

            @Override
            public void onToken(int kind, String text) {
                if ("->".equals(text)) {
                    emit(" -> ");
                } else {
                    emitToken(text);
                }
            }
        });
    }

    // ===== Comment emission (inline, but never affects layout decisions) =====

    private void emitLeadingComments(Cursor node) {
        boolean emittedAny = false;
        // Iterate by token index so we can dedupe (under v6, leading trivia is sometimes
        // attributed to both the outer node and its first CST child — emit each token at
        // most once across the whole format pass).
        var triviaTokenIdxs = node.leadingTriviaTokens().toArray();
        var tokens = node.cst().tokens();
        for (int tokIdx : triviaTokenIdxs) {
            if (emittedTriviaTokens.contains(tokIdx)) {
                continue;
            }
            int kind = tokens.kindAt(tokIdx);
            boolean isLine = kind == 1 || kind == 3;        // LINE_COMMENT or DOC_LINE_COMMENT
            boolean isBlock = kind == 2 || kind == 4;       // BLOCK_COMMENT or DOC_BLOCK_COMMENT
            if (isLine) {
                if (currentColumn > 0) {
                    newline();
                }
                printIndent();
                var text = tokens.textAt(tokIdx).toString().stripTrailing();
                output.append(text);
                currentColumn += text.length();
                newline();
                emittedAny = true;
                emittedTriviaTokens.add(tokIdx);
            } else if (isBlock) {
                if (currentColumn > 0) {
                    newline();
                }
                var lines = tokens.textAt(tokIdx).toString().split("\n", -1);
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
                emittedAny = true;
                emittedTriviaTokens.add(tokIdx);
            }
            // Whitespace trivia ignored — flow formatter controls all whitespace
        }
        if (emittedAny && currentColumn == 0) {
            printIndent();
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
