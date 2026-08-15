package org.pragmatica.jbct.format.flow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.jbct.format.AlignmentContext;
import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.jbct.shared.ImportGroups;
import org.pragmatica.lang.Option;
import org.pragmatica.peg.token.TokenArray;

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
    private StringBuilder output;
    private int currentColumn;
    private int indentLevel;
    private char lastChar;
    private char prevChar;
    private String lastWord = "";
    /// >0 while emitting tokens inside a TYPE_ARGS / TYPE_PARAMS node. Disambiguates a generic
    /// '<'/'>' (glued punctuation) from a relational/shift operator '<'/'>' (spaced) — the parser
    /// already separates them into distinct rules, so we key spacing off that rather than guessing
    /// from characters (which cannot tell `static <T>` from `a < b`).
    private int typeContextDepth = 0;
    // Measurement mode
    private boolean measuringMode;
    private int measureBuffer;
    // Token tracking for trivia insertion
    private int tokenIndex;
    private final Map<Integer, Integer> tokenLineMap = new HashMap<>();
    private int currentLine;
    // Track trivia tokens we've already emitted as comments (prevents double-emit when
    // an outer node and its first CST child share leading trivia under v6 attribution).
    private final Set<Integer> emittedTriviaTokens = new HashSet<>();
    // Alignment tracking
    private final AlignmentContext alignment = new AlignmentContext();
    // When >= 0, emitLeadingComments uses printAlignedTo(forcedIndentCol) instead of printIndent().
    // Set by printAlignedBlockStatements so comments in aligned lambda bodies land at bodyCol.
    private int forcedIndentCol = -1;

    // Pattern for detecting method calls in chains
    private static final Pattern METHOD_CALL_PATTERN = Pattern.compile("\\.[a-zA-Z_][a-zA-Z0-9_]*\\s*\\(");

    // Spacing rule constants
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

    private static final Set<String> SPACE_AFTER_BRACE_KEYWORDS = Set.of("else", "catch", "finally", "while");

    private static final Set<String> SPACE_AFTER_KEYWORDS = Set.of("case", "return", "throw", "new", "yield", "assert");

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
                           .stripTrailing()
                   + "\n";

        return new FlowResult(result, Map.copyOf(tokenLineMap));
    }

    // ===== Measurement =====
    private int measureWidth(Cursor node) {
        boolean wasMeasuring = measuringMode;
        int oldBuffer = measureBuffer;
        char oldLastChar = lastChar;
        char oldPrevChar = prevChar;
        String oldLastWord = lastWord;
        int oldTypeContextDepth = typeContextDepth;

        measuringMode = true;
        measureBuffer = 0;
        printNode(node);
        int width = measureBuffer;

        measuringMode = wasMeasuring;
        measureBuffer = oldBuffer;
        lastChar = oldLastChar;
        prevChar = oldPrevChar;
        lastWord = oldLastWord;
        typeContextDepth = oldTypeContextDepth;

        return width;
    }

    private boolean fitsOnLine(Cursor node) {
        return currentColumn + measureWidth(node) <= config.maxLineLength();
    }

    /// Complete snapshot of all mutable render state, captured before a speculative trial
    /// render (see `inlineBlockFits`) and restored afterwards. Capturing EVERYTHING — the
    /// scalar cursor state, the two mutable collections (defensively copied), AND the
    /// alignment context — guarantees the trial cannot leak state even if `printNode`
    /// follows an unbalanced force-break/early-return path inside a scope guard.
    private record SavedState(StringBuilder output,
                              int currentColumn,
                              int indentLevel,
                              char lastChar,
                              char prevChar,
                              String lastWord,
                              int typeContextDepth,
                              boolean measuringMode,
                              int measureBuffer,
                              int tokenIndex,
                              int currentLine,
                              int forcedIndentCol,
                              Map<Integer, Integer> tokenLineMap,
                              Set<Integer> emittedTriviaTokens,
                              AlignmentContext.Snapshot alignment) {}

    private SavedState saveState() {
        return new SavedState(output,
                              currentColumn,
                              indentLevel,
                              lastChar,
                              prevChar,
                              lastWord,
                              typeContextDepth,
                              measuringMode,
                              measureBuffer,
                              tokenIndex,
                              currentLine,
                              forcedIndentCol,
                              new HashMap<>(tokenLineMap),
                              new HashSet<>(emittedTriviaTokens),
                              alignment.snapshot());
    }

    private void restoreState(SavedState saved) {
        output = saved.output();
        currentColumn = saved.currentColumn();
        indentLevel = saved.indentLevel();
        lastChar = saved.lastChar();
        prevChar = saved.prevChar();
        lastWord = saved.lastWord();
        typeContextDepth = saved.typeContextDepth();
        measuringMode = saved.measuringMode();
        measureBuffer = saved.measureBuffer();
        tokenIndex = saved.tokenIndex();
        currentLine = saved.currentLine();
        forcedIndentCol = saved.forcedIndentCol();
        tokenLineMap.clear();
        tokenLineMap.putAll(saved.tokenLineMap());
        emittedTriviaTokens.clear();
        emittedTriviaTokens.addAll(saved.emittedTriviaTokens());
        alignment.restore(saved.alignment());
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

    /// Emit `node`'s own-line leading comments (if any) at the current indent level, then its
    /// content via the trivia-free `printNodeContent` path. The CALLER MUST be at column 0 (have
    /// just emitted a `newline()`) and MUST NOT pre-indent: when the node carries leading comments
    /// `emitLeadingComments` owns the indentation of both the comment lines and the following
    /// content — pre-indenting would leave a stripped spaces-only line (a spurious blank) ahead of
    /// the comment; when it does not, we indent here. Custom printers that place a child on its own
    /// line (enum-body members/constants, switch rules) route through this so a comment claimed as
    /// the child's leading trivia is not silently deleted — `printNodeContent` skips trivia by
    /// contract (bug report S1–S4). Output is byte-identical for comment-free nodes.
    private void printOwnLineChildContent(Cursor node) {
        if (!measuringMode && hasUnEmittedLeadingComment(node)) {
            emitLeadingComments(node);
        } else {
            printIndent();
        }

        printNodeContent(node);
    }

    /// As `printOwnLineChildContent`, but indents to an explicit column via `printAlignedTo` rather
    /// than the indent level — for aligned list layouts (broken record components). Caller must be
    /// at column 0.
    private void printOwnLineChildContentAligned(Cursor node, int col) {
        if (!measuringMode && hasUnEmittedLeadingComment(node)) {
            int savedForcedIndentCol = forcedIndentCol;

            forcedIndentCol = col;
            emitLeadingComments(node);
            forcedIndentCol = savedForcedIndentCol;
        } else {
            printAlignedTo(col);
        }

        printNodeContent(node);
    }

    /// Emit a Leaf node's tokens by walking the TokenArray range (skipping trivia).
    /// Using `leaf.text()` instead would return the source slice covering trailing trivia,
    /// causing whitespace bleed into the output.
    private void emitLeafTokens(Cursor.Leaf leaf) {
        var tokens = leaf.cst().tokens();
        // Position of the last `}` token in this leaf (or -1). Orphan line comments are emitted
        // ONLY when they sit BEFORE a `}` within the leaf — the bodyless `{ // note }` empty-body
        // case, where the comment is trivia on the bare `}` leaf and no node's leadingTrivia owns
        // it. A comment AFTER the `}` is the leading trivia of the FOLLOWING declaration and is
        // emitted by emitLeadingComments; re-emitting it here would re-place it (idempotency break).
        int lastBrace = -1;

        for (int t = leaf.firstTokenIdx(); t <= leaf.lastTokenIdx(); t++) {
            if (!tokens.isTrivia(t) && "}".contentEquals(tokens.textAt(t))) {
                lastBrace = t;
            }
        }

        for (int t = leaf.firstTokenIdx(); t <= leaf.lastTokenIdx(); t++) {
            if (!tokens.isTrivia(t)) {
                emitToken(tokens.textAt(t).toString());
                continue;
            }

            int kind = tokens.kindAt(t);
            boolean isLine = kind == 1 || kind == 3;  // LINE_COMMENT / DOC_LINE_COMMENT
            boolean isBlock = kind == 2 || kind == 4;  // BLOCK_COMMENT / DOC_BLOCK_COMMENT
            if (measuringMode || (!isLine && !isBlock) || t >= lastBrace || emittedTriviaTokens.contains(t)) {
                continue;
            }
            // Orphan comment enclosed before a `}` in this bare-leaf body — emit so it is not
            // silently deleted (bug B2 / report mechanism C). A single-line block comment stays
            // inline (`{ /* note */ }`); line comments and multi-line block comments go own-line.
            var text = tokens.textAt(t).toString().stripTrailing();
            boolean sameLine = precedingContentOnSameLine(tokens.startAt(t)) && currentColumn > 0;

            if (isBlock && sameLine && text.indexOf('\n') < 0) {
                emit(" " + text + " ");
            } else if (isLine && sameLine) {
                emit("  " + text);
            } else {
                if (currentColumn > 0) {
                    newline();
                }

                printIndent();
                emit(text);
                newline();
                printIndent();
            }

            emittedTriviaTokens.add(t);
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
            case FIELD_DECL, INTERFACE_FIELD_DECL -> printFieldDecl(br);
            case CLASS_BODY -> printClassBody(br);
            case INTERFACE_BODY -> printInterfaceBody(br);
            case ANNOTATION_BODY -> printAnnotationBody(br);
            case BLOCK -> printBlock(br);
            case STMT -> printStmt(br);
            case SWITCH_BLOCK -> printSwitchBlock(br);
            case UNARY -> printUnary(br);
            case POSTFIX -> printPostfix(br);
            case STMT_EXPR -> printStmtExpr(br);
            case POST_OP -> printPostOp(br);
            case ARGS -> printArgs(br);
            case LAMBDA -> printLambda(br);
            case TYPED_LAMBDA_PARAM, VAR_LAMBDA_PARAM -> printLambdaParam(br);
            case PLAIN_PARAM, LAST_PARAM, RECEIVER_PARAM -> printParam(br);
            // `Params` wraps its entries in `OrdinaryParams`, which carries the separating
            // commas. Route the wrapper through the same logic so break-on-comma still sees them.
            case PARAMS, ORDINARY_PARAMS -> printParams(br);
            case PRIMARY -> printPrimary(br);
            case RECORD_DECL -> printRecordDecl(br);
            case RECORD_COMPONENTS -> printRecordComponents(br);
            case RESOURCE_SPEC -> printResourceSpec(br);
            case TERNARY -> printTernary(br);
            case ADDITIVE -> printAdditive(br);
            case LOG_AND -> printLogAnd(br);
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
        boolean breakAfterAnnotation = parent instanceof Cursor.Branch pb && annotationsBreakOnNewlineInParent(pb.kind());
        boolean spaceAfterAnnotation = parent instanceof Cursor.Branch pb2 && annotationsForceSpaceAfterInParent(pb2.kind());
        int kidIdx = 0;
        int t = start;

        while (t <= end) {
            if (kidIdx < kids.size() && kids.get(kidIdx).firstTokenIdx() == t) {
                var kid = kids.get(kidIdx);

                printNode(kid);
                t = kid.lastTokenIdx() + 1;
                kidIdx++;
                // Rescue self-closing block comments inside the child's span (safe: cannot swallow).
                flushInSpanBlockComments(kid);
                if (breakAfterAnnotation && kid.kindIs(RuleKind.ANNOTATION)) {
                    // Flush a same-line trailing comment after the annotation (e.g. `@Ann // note`)
                    // BEFORE the break newline — safe here precisely because a newline immediately
                    // follows, so the comment cannot swallow trailing tokens (bug report mechanism
                    // B, annotation-trailing). The parser may attach it INSIDE the annotation span
                    // (flushInSpanTrailingComment) or as a gap token after it (the while-scan).
                    flushInSpanTrailingComment(kid);
                    while (t <= end && tokens.isTrivia(t)) {
                        int kind = tokens.kindAt(t);

                        if (kind >= 1 && kind <= 4 && !emitTrailingOrphanComment(tokens, t)) {
                            break;
                        }

                        t++;
                    }

                    newline();
                    printIndent();
                } else if (spaceAfterAnnotation && kid.kindIs(RuleKind.ANNOTATION)) {
                    emit(" ");
                }
            } else {
                if (!tokens.isTrivia(t)) {
                    emitToken(tokens.textAt(t).toString());
                } else {
                    emitInlineBlockComment(tokens, t);
                }

                t++;
            }
        }
    }

    /// True if ANNOTATION children of a parent of the given kind should be followed by a
    /// space rather than a newline. Applies to type-use positions (DIMS, ANNOTATED_TYPE_NAME)
    /// where the annotation is inline but separated from the next token by a space.
    private static boolean annotationsForceSpaceAfterInParent(RuleKind kind) {
        return switch (kind) {
            case DIMS, ANNOTATED_TYPE_NAME -> true;
            default -> false;
        };
    }

    /// True if ANNOTATION children of a parent of the given kind should each be followed
    /// by newline + indent. Applies to declaration-scope parents (type decls, class members,
    /// record members, enum constants, local var/type decls, annotation members).
    private static boolean annotationsBreakOnNewlineInParent(RuleKind kind) {
        return switch (kind) {
            case TYPE_DECL, CLASS_MEMBER, INTERFACE_MEMBER, ANNOTATION_MEMBER, ANNOTATION_ELEM_DECL, RECORD_MEMBER, ENUM_CONST, LOCAL_VAR, LOCAL_VAR_NO_SEMI, LOCAL_TYPE_DECL -> true;
            default -> false;
        };
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
                // Rescue self-closing block comments inside the child's span (safe: cannot swallow).
                flushInSpanBlockComments(kid);
            } else {
                if (!tokens.isTrivia(t)) {
                    walker.onToken(tokens.kindAt(t),
                                   tokens.textAt(t).toString());
                } else {
                    emitInlineBlockComment(tokens, t);
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
        var hasPackage = childByRule(ou, RuleKind.PACKAGE_DECL).onPresent(this::printNode).isPresent();
        var imports = childrenByRule(ou, RuleKind.IMPORT_DECL);
        var hasImports = !imports.isEmpty();

        if (hasImports) {
            newline();
            newline();
            printOrganizedImports(imports, ImportGroups.projectPackage(packageName(ou)));
        }

        var types = childrenByRule(ou, RuleKind.TYPE_DECL);
        boolean first = true;

        for (var type : types) {
            if (first) {
                if (hasImports || hasPackage) {
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

    private void printOrganizedImports(List<Cursor> imports, String projectPackage) {
        var jdk = importsInGroup(imports, projectPackage, ImportGroups.Group.JDK);
        var pragmatica = importsInGroup(imports, projectPackage, ImportGroups.Group.PRAGMATICA);
        var thirdParty = sortedByText(importsInGroup(imports, projectPackage, ImportGroups.Group.THIRD_PARTY));
        var project = importsInGroup(imports, projectPackage, ImportGroups.Group.PROJECT);
        var staticImports = staticImportsInOrder(imports, projectPackage);
        // De-duplicate against the rendered statement text, not the CST span. Some import
        // nodes absorb the following type's doc-comment as trailing trivia (e.g. a `java.*`
        // import whose doc mentions `org.pragmatica`); without dedup such an import is
        // emitted in two groups and re-formatting never reaches a fixpoint.
        var emitted = new HashSet<String>();
        boolean needsBlank = false;

        needsBlank = printImportGroup(jdk, needsBlank, emitted);
        needsBlank = printImportGroup(pragmatica, needsBlank, emitted);
        needsBlank = printImportGroup(thirdParty, needsBlank, emitted);
        needsBlank = printImportGroup(project, needsBlank, emitted);
        printImportGroup(staticImports, needsBlank, emitted);
    }

    /// Non-static imports classified into `group` by the shared {@link ImportGroups}
    /// scheme, preserving source order within the group.
    private List<Cursor> importsInGroup(List<Cursor> imports, String projectPackage, ImportGroups.Group group) {
        return imports.stream()
                      .filter(i -> !isStaticImport(i) && groupOf(i, projectPackage) == group)
                      .toList();
    }

    /// Static imports last, ordered by the same book grouping (JDK → pragmatica →
    /// third-party → project); the stable sort preserves source order within each group.
    private List<Cursor> staticImportsInOrder(List<Cursor> imports, String projectPackage) {
        return imports.stream()
                      .filter(this::isStaticImport)
                      .sorted(Comparator.comparingInt(i -> groupOf(i, projectPackage).ordinal()))
                      .toList();
    }

    private List<Cursor> sortedByText(List<Cursor> group) {
        return group.stream()
                    .sorted(Comparator.comparing(this::importStatementText))
                    .toList();
    }

    private boolean isStaticImport(Cursor imp) {
        return ImportGroups.isStatic(importStatementText(imp));
    }

    private ImportGroups.Group groupOf(Cursor imp, String projectPackage) {
        return ImportGroups.classify(ImportGroups.stripToPath(importStatementText(imp)), projectPackage);
    }

    private boolean printImportGroup(List<Cursor> group, boolean needsBlank, Set<String> emitted) {
        var fresh = group.stream().filter(imp -> emitted.add(importStatementText(imp))).toList();

        if (fresh.isEmpty()) {
            return needsBlank;
        }

        if (needsBlank) {
            newline();
        }

        for (var imp : fresh) {
            printImportDecl(imp);
        }

        return true;
    }

    private void printImportDecl(Cursor imp) {
        emit(importStatementText(imp));
        newline();
    }

    /// Render an import as just its statement text (`import a.b.C;`), built from the
    /// node's non-trivia tokens. Trailing comments (`/// ...` after `;`) are excluded —
    /// they belong to whatever follows. This is the single source of truth for both
    /// classifying imports into groups and de-duplicating them.
    private String importStatementText(Cursor imp) {
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
        return sb.toString()
                 .replaceAll(" ([.;*])",
                             "$1")
                 .replaceAll("([.]) ",
                             "$1");
    }

    // ===== Type bodies =====
    private void printClassBody(Cursor.Branch classBody) {
        printBracedBody(classBody, RuleKind.CLASS_MEMBER);
    }

    /// Interfaces used to reuse `ClassBody`. The grammar now gives them their own
    /// `InterfaceBody <- '{' InterfaceMember* '}'`, so they need the same braced-body
    /// treatment keyed on `InterfaceMember` — without this they print inline.
    private void printInterfaceBody(Cursor.Branch interfaceBody) {
        printBracedBody(interfaceBody, RuleKind.INTERFACE_MEMBER);
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

            for (int mi = 0; mi < members.size(); mi++) {
                var member = members.get(mi);

                if (!first && BlankLineRules.needsBlankLineBetween(member, prevMember)) {
                    newline();
                }
                // Skip printIndent when the member has unemitted leading comments — emitLeadingComments
                // owns its own newline/indent and we'd otherwise emit a stray spaces-only line.
                if (!hasUnEmittedLeadingComment(member)) {
                    printIndent();
                }

                printNode(member);
                // Emit trailing line comments from the NEXT member's leading trivia before
                // the newline, so they stay on the same line as the current member.
                if (mi + 1 < members.size()) {
                    emitTrailingCommentsFrom(members.get(mi + 1));
                }

                newline();
                first = false;
                prevMember = Option.some(member);
            }
            // Own-line comments dangling after the last member, before the body's `}` (mechanism
            // E3). Deduped against comments already emitted as members' leading trivia.
            emitTrailingBodyComments(parent,
                                     members.get(members.size() - 1));
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
        // Check for array initializer `{elem, ...}` that overflows the line. If found and
        // it doesn't fit, walk up to the `=` inline then break each element aligned to
        // the first element's column. Otherwise just walk everything inline.
        var varInitOpt = findFirst(field, RuleKind.VAR_INIT);

        if (!measuringMode && varInitOpt.isPresent()) {
            var varInit = varInitOpt.unwrap();
            var initText = text(varInit);

            if (initText.startsWith("{") && !fitsOnLine(field)) {
                printArrayFieldDecl(field, (Cursor.Branch) varInit);

                return;
            }
        }

        walkTokens(field);
    }

    private void printArrayFieldDecl(Cursor.Branch field, Cursor.Branch varInit) {
        // Emit everything up to and including `=` inline, then break the `{...}` initializer.
        // Each element is on its own line aligned to the column right after `{`.
        var tokens = field.cst().tokens();
        int initStart = varInit.firstTokenIdx();

        for (int t = field.firstTokenIdx(); t < initStart; t++) {
            if (!tokens.isTrivia(t)) emitToken(tokens.textAt(t).toString());
        }

        final int[] alignCol = { - 1};

        walkTokensWith(varInit,
                       new TokenWalker() {
            @Override
            public void onChild(Cursor c) {
                           printNodeContent(c);
                       }

            @Override
            public void onToken(int kind, String text) {
                           if ("{".equals(text)) {
                           emitToken("{");
                           alignCol[0] = currentColumn;
                       } else if ("}".equals(text)) {
                           emitToken("}");
                       } else if (",".equals(text)) {
                           emit(",");
                           newline();
                           printAlignedTo(alignCol[0]);
                       } else {
                           emitToken(text);
                       }
                       }
        }  // right after `{`
                      );
        for (int t = varInit.lastTokenIdx() + 1; t <= field.lastTokenIdx(); t++) {
            if (!tokens.isTrivia(t)) emitToken(tokens.textAt(t).toString());
        }
    }

    // ===== Enum body =====
    private void printEnumBody(Cursor.Branch enumBody) {
        var classMembers = childrenByRule(enumBody, RuleKind.CLASS_MEMBER);

        emitToken("{");
        indentLevel++;
        newline();
        childByRule(enumBody, RuleKind.ENUM_CONSTS).onPresent(this::printEnumConstsWithIndent);
        if (!classMembers.isEmpty()) {
            emit(";");
        }

        for (var member : classMembers) {
            newline();
            printOwnLineChildContent(member);
        }

        indentLevel--;
        newline();
        printIndent();
        emitBare("}");
    }

    private void printEnumConstsWithIndent(Cursor consts) {
        // No pre-indent: printOwnLineChildContent (called per constant) owns the indentation so a
        // constant carrying a leading doc comment isn't preceded by a spurious blank line.
        printEnumConsts(consts);
    }

    private void printEnumConsts(Cursor enumConsts) {
        var constNodes = childrenByRule(enumConsts, RuleKind.ENUM_CONST);

        for (int i = 0; i < constNodes.size(); i++) {
            if (i > 0) {
                emit(",");
                newline();
            }

            printOwnLineChildContent(constNodes.get(i));
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
        } else if (isReturnOrThrowStmt(stmt)) {
            try (var scope = alignment.enterTailContext()) {
                walkTokens(stmt);
            }
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
            newline();
            if (useLambdaAlign) {
                printAlignedBlockStatements(stmts, lambdaAlignCol);
                printAlignedTo(lambdaAlignCol);
            } else if (useChainAlign) {
                printAlignedBlockStatements(stmts, chainAlignCol);
                printAlignedTo(chainAlignCol);
            } else {
                indentLevel++;
                // Blank-line rules, applied uniformly in regular and lambda bodies (only the
                // base indent differs): R1 blank after a block-shaped stmt; R2 blank after a
                // local-var-decl run before a non-var stmt; R3 blank before a return/throw. A
                // single newline() is emitted, so overlapping rules coalesce to one blank.
                for (int i = 0; i < stmts.size(); i++) {
                    var stmt = stmts.get(i);

                    if (i >= 1 && !hasLeadingComment(stmt) && needsBlankBeforeStmt(stmts.get(i - 1), stmt)) {
                        newline();
                    }

                    if (!hasUnEmittedLeadingComment(stmt)) {
                        printIndent();
                    }

                    printNode(stmt);
                    // Emit trailing line comments from next stmt's leading trivia before newline.
                    if (i + 1 < stmts.size()) {
                        emitTrailingCommentsFrom(stmts.get(i + 1));
                    } else {
                        // Last stmt: emit trailing comments from the block's closing `}` token range.
                        emitBlockTrailingComments(block, stmts.get(i));
                    }

                    newline();
                }

                indentLevel--;
                printIndent();
            }
        } else {
            emitEmptyBlockComments(block);
        }

        emitBare("}");
    }

    /// Preserve line comments that are the sole content of an otherwise-empty block — emit each
    /// on its own indented line so a comment-only body (`{ // note }`) is not collapsed to `{}`
    /// (bug B2). No-op (leaves `{}`) when the block carries no un-emitted comments.
    private void emitEmptyBlockComments(Cursor.Branch block) {
        if (measuringMode) {
            return;
        }

        var tokens = block.cst().tokens();
        int open = block.firstTokenIdx();
        int close = block.lastTokenIdx();
        var comments = new ArrayList<Integer>();
        boolean needsOwnLines = false;  // a line comment or a multi-line block comment forces expansion
        for (int t = open + 1; t < close; t++) {
            if (!tokens.isTrivia(t)) {
                continue;
            }

            int kind = tokens.kindAt(t);
            boolean isLine = kind == 1 || kind == 3;  // LINE_COMMENT / DOC_LINE_COMMENT
            boolean isBlock = kind == 2 || kind == 4;  // BLOCK_COMMENT / DOC_BLOCK_COMMENT
            if ((!isLine && !isBlock) || emittedTriviaTokens.contains(t)) {
                continue;
            }

            comments.add(t);
            if (isLine || tokens.textAt(t).toString().indexOf('\n') >= 0) {
                needsOwnLines = true;
            }
        }

        if (comments.isEmpty()) {
            return;
        }

        if (!needsOwnLines) {
            // Only single-line block comments: keep them inline so `{ /* note */ }` stays one line.
            for (int t : comments) {
                emit(" " + tokens.textAt(t).toString().stripTrailing());
                emittedTriviaTokens.add(t);
            }

            emit(" ");

            return;
        }
        // A line comment (or multi-line block comment) can't share the `}` line — expand the body.
        indentLevel++;
        for (int t : comments) {
            newline();
            printIndent();
            emit(tokens.textAt(t).toString().stripTrailing());
            emittedTriviaTokens.add(t);
        }

        indentLevel--;
        newline();
        printIndent();
    }

    /// Emit trailing line comments that sit in the last stmt's trailing trivia region
    /// (between the last stmt's last non-trivia token and the block's closing `}`).
    private void emitBlockTrailingComments(Cursor.Branch block, Cursor lastStmt) {
        if (measuringMode) return;

        var tokens = block.cst().tokens();
        int closingBrace = block.lastTokenIdx();
        // Find the last non-trivia token of the last stmt to start scanning from.
        int lastNonTrivia = lastStmt.lastTokenIdx();

        while (lastNonTrivia > lastStmt.firstTokenIdx() && tokens.isTrivia(lastNonTrivia)) {
            lastNonTrivia--;
        }

        for (int t = lastNonTrivia + 1; t <= closingBrace; t++) {
            if (!tokens.isTrivia(t)) break;

            int kind = tokens.kindAt(t);

            if (kind != 1 && kind != 3) {
                // LINE_COMMENT or DOC_LINE_COMMENT only
                continue;
            }

            if (emittedTriviaTokens.contains(t)) {
                continue;
            }

            int commentStart = tokens.startAt(t);
            var text = tokens.textAt(t).toString().stripTrailing();

            if (precedingContentOnSameLine(commentStart) && currentColumn > 0) {
                // Trailing comment on the same source line as the preceding stmt.
                emit("  " + text);
            } else {
                // Comment on its OWN line before `}` — emit it on a fresh indented line instead
                // of dropping it. The `}` is emitted via emitBare, which never runs the
                // leading-comment path, so without this the comment is lost (bug B2).
                newline();
                printIndent();
                emit(text);
            }

            emittedTriviaTokens.add(t);
        }
    }

    /// Emit any trailing line comments from `nextNode`'s leading trivia that sit on the
    /// same source line as the preceding non-trivia content. Marks them emitted so
    /// `emitLeadingComments` skips them. Call this BEFORE the `newline()` that follows the
    /// current node, so the trailing comment lands on the right line.
    private void emitTrailingCommentsFrom(Cursor nextNode) {
        if (measuringMode) return;

        var tokens = nextNode.cst().tokens();

        for (int tokIdx : nextNode.leadingTriviaTokens().toArray()) {
            if (emittedTriviaTokens.contains(tokIdx)) continue;

            int kind = tokens.kindAt(tokIdx);

            if (kind != 1 && kind != 3) continue;  // LINE_COMMENT or DOC_LINE_COMMENT only
            int commentStart = tokens.startAt(tokIdx);

            if (!precedingContentOnSameLine(commentStart)) break;

            var text = tokens.textAt(tokIdx).toString().stripTrailing();

            emit("  " + text);
            emittedTriviaTokens.add(tokIdx);
        }
    }

    /// Returns true if there is non-whitespace content before `sourcePos` on the same source
    /// line — i.e., this position is a trailing context (not the start of a new line).
    private boolean precedingContentOnSameLine(int sourcePos) {
        for (int pos = sourcePos - 1; pos >= 0; pos--) {
            char ch = source.charAt(pos);

            if (ch == '\n') return false;

            if (ch != ' ' && ch != '\t' && ch != '\r') return true;
        }

        return false;
    }

    private boolean hasLeadingComment(Cursor node) {
        return node.leadingTrivia()
                   .anyMatch(t -> t.isLineComment() || t.isBlockComment());
    }

    /// Emit token `t` as a trailing comment when it is an unclaimed comment sitting at the END of a
    /// source line (non-whitespace precedes it on the same source line) — e.g. `// note` after an
    /// annotation, or after the last argument before `)`. Such comments are claimed by no node, so
    /// the trivia-skipping token walk would otherwise delete them (bug report mechanism B). Returns
    /// true when it emitted a comment. Own-line comments return false and are left for the
    /// leading-comment machinery. No-op in measuring mode, for non-comments, for already-emitted
    /// tokens, and for multi-line block comments (which cannot be inlined as a trailing comment).
    private boolean emitTrailingOrphanComment(TokenArray tokens, int t) {
        if (measuringMode || emittedTriviaTokens.contains(t)) {
            return false;
        }

        int kind = tokens.kindAt(t);
        boolean isLine = kind == 1 || kind == 3;
        boolean isBlock = kind == 2 || kind == 4;

        if (!isLine && !isBlock) {
            return false;
        }

        if (currentColumn == 0 || !precedingContentOnSameLine(tokens.startAt(t))) {
            return false;
        }

        var text = tokens.textAt(t).toString().stripTrailing();

        if (isBlock && text.indexOf('\n') >= 0) {
            return false;
        }

        emit("  " + text);
        emittedTriviaTokens.add(t);

        return true;
    }

    /// Emit token `t` as an inline block comment (`/* note */`) when it is an unclaimed,
    /// single-line block comment in mid-line position. Block comments are self-closing, so —
    /// unlike line comments — emitting one inline cannot swallow the tokens that follow it on the
    /// same line; this is the safe way to preserve `x /* note */ y` comments that the trivia-
    /// skipping walk would otherwise delete (bug report mechanism E1). No-op for line comments,
    /// multi-line block comments, start-of-line positions, measuring mode, and already-emitted
    /// tokens.
    private void emitInlineBlockComment(TokenArray tokens, int t) {
        if (measuringMode || emittedTriviaTokens.contains(t) || currentColumn == 0) {
            return;
        }

        int kind = tokens.kindAt(t);

        if (kind != 2 && kind != 4) {
            return;
        }
        // Only inline a block comment that was a same-line trailing comment in the source. An
        // own-line standalone block comment (`\n/* note */\n`) belongs to the leading-comment
        // machinery and must NOT be pulled up onto the previous token's line.
        if (!precedingContentOnSameLine(tokens.startAt(t))) {
            return;
        }

        var text = tokens.textAt(t).toString().stripTrailing();

        if (text.indexOf('\n') >= 0) {
            return;
        }

        emit(" " + text);
        emittedTriviaTokens.add(t);
    }

    /// Emit any unclaimed single-line block comments living INSIDE `node`'s token span, inline at
    /// the current position (after the node has rendered). Block comments are self-closing, so this
    /// cannot corrupt following tokens; it rescues `x /* note */ y` comments that the parser places
    /// inside a child expression span where the trivia-skipping walk never visits them (bug report
    /// mechanism E1). No-op for comment-free spans and for comments already emitted by their own
    /// node's leading-trivia handling (deduped via emittedTriviaTokens).
    private void flushInSpanBlockComments(Cursor node) {
        if (measuringMode) {
            return;
        }

        var tokens = node.cst().tokens();

        for (int t = node.firstTokenIdx(); t <= node.lastTokenIdx(); t++) {
            if (tokens.isTrivia(t)) {
                emitInlineBlockComment(tokens, t);
            }
        }
    }

    /// Emit same-line trailing comments that live INSIDE `node`'s own token span (e.g. the comment
    /// in `arg // note` trailing the last argument before `)`, which the parser places inside the
    /// argument expression rather than after it). Returns true if a LINE comment was emitted — the
    /// caller must then break the following delimiter (`,`/`)`) onto a new line so the line comment
    /// does not swallow it. No-op for comment-free spans.
    private boolean flushInSpanTrailingComment(Cursor node) {
        var tokens = node.cst().tokens();
        boolean lineEmitted = false;

        for (int t = node.firstTokenIdx(); t <= node.lastTokenIdx(); t++) {
            if (!tokens.isTrivia(t)) {
                continue;
            }

            int kind = tokens.kindAt(t);
            boolean wasLine = kind == 1 || kind == 3;

            if (emitTrailingOrphanComment(tokens, t) && wasLine) {
                lineEmitted = true;
            }
        }

        return lineEmitted;
    }

    /// Emit unclaimed own-line comments dangling between the last member and the body's closing
    /// `}` — i.e. comments that trail the final member of a class/interface/annotation body (bug
    /// report mechanism E3). Boundaries are computed from the actual tokens, NOT `lastTokenIdx()`,
    /// which includes trailing trivia attributed PAST the brace (e.g. the next sibling's leading
    /// comment) and would otherwise both over-reach (pulling a sibling's comment inside this body)
    /// and under-reach (skipping genuine tail comments absorbed into the member's trailing span).
    private void emitTrailingBodyComments(Cursor.Branch parent, Cursor lastMember) {
        if (measuringMode) {
            return;
        }

        var tokens = parent.cst().tokens();
        // First token after the last member's last NON-trivia token.
        int afterToken = lastMember.lastTokenIdx();

        while (afterToken >= lastMember.firstTokenIdx() && tokens.isTrivia(afterToken)) {
            afterToken--;
        }

        afterToken++;
        // The body's own closing `}` (last non-trivia `}` within the parent span).
        int close = parent.lastTokenIdx();

        while (close >= afterToken && (tokens.isTrivia(close) || !"}".contentEquals(tokens.textAt(close)))) {
            close--;
        }

        for (int t = afterToken; t < close; t++) {
            if (!tokens.isTrivia(t)) {
                continue;
            }

            int kind = tokens.kindAt(t);

            if (kind < 1 || kind > 4 || emittedTriviaTokens.contains(t)) {
                continue;
            }

            if (currentColumn > 0) {
                newline();
            }

            printIndent();
            emit(tokens.textAt(t).toString().stripTrailing());
            newline();
            emittedTriviaTokens.add(t);
        }
    }

    /// Emit unclaimed OWN-LINE comments dangling inside an argument list before its `)` (e.g.
    /// `f(a,\n // note\n)`), each on its own line aligned to `alignCol`. Returns true if any was
    /// emitted, so the caller can break the closing `)` onto a new line (bug report mechanism E2).
    /// Same-line comments are handled by `flushInSpanTrailingComment` / `emitInlineBlockComment`.
    private boolean emitDanglingOwnLineArgComments(Cursor args, int alignCol) {
        if (measuringMode) {
            return false;
        }

        var tokens = args.cst().tokens();
        boolean any = false;

        for (int t = args.firstTokenIdx(); t <= args.lastTokenIdx(); t++) {
            if (!tokens.isTrivia(t)) {
                continue;
            }

            int kind = tokens.kindAt(t);

            if (kind < 1 || kind > 4 || emittedTriviaTokens.contains(t) || precedingContentOnSameLine(tokens.startAt(t))) {
                continue;
            }

            newline();
            printAlignedTo(alignCol);
            emit(tokens.textAt(t).toString().stripTrailing());
            any = true;
            emittedTriviaTokens.add(t);
        }

        return any;
    }

    /// True if `node` has any line/block comment in its leading trivia that has NOT yet
    /// been emitted. Used instead of `hasLeadingComment` when trailing comments may have
    /// already been claimed by `emitTrailingCommentsFrom`, so we don't skip `printIndent`
    /// for a node whose only leading trivia was a trailing comment of the prior statement.
    private boolean hasUnEmittedLeadingComment(Cursor node) {
        if (measuringMode) return hasLeadingComment(node);

        var tokens = node.cst().tokens();

        for (int tokIdx : node.leadingTriviaTokens().toArray()) {
            if (emittedTriviaTokens.contains(tokIdx)) continue;

            int kind = tokens.kindAt(tokIdx);

            if (kind >= 1 && kind <= 4) return true;  // any comment kind (1=LINE, 2=BLOCK, 3=DOC_LINE, 4=DOC_BLOCK)
        }

        return false;
    }

    /// Whether a blank line is required between `prev` and `cur` per the uniform rules:
    /// R1 — blank after a block-shaped statement; R2 — blank after a local-variable-declaration
    /// run, before a non-var statement; R3 — blank before a return/throw. Overlapping rules
    /// coalesce because the caller emits a single newline.
    private static boolean needsBlankBeforeStmt(Cursor prev, Cursor cur) {
        return isBlockShapedStmt(prev) || (isLocalVarDecl(prev) && !isLocalVarDecl(cur)) || isReturnOrThrowStmt(cur);
    }

    /// True iff the statement is a local-variable declaration. In the v6 CST a var-decl
    /// statement uniquely presents as a BLOCK_STMT whose direct child is a LOCAL_VAR (other
    /// statements wrap an intermediate STMT or a leaf).
    private static boolean isLocalVarDecl(Cursor stmt) {
        return stmt instanceof Cursor.Branch br && br.children()
                                                     .anyMatch(c -> c.kindIs(RuleKind.LOCAL_VAR) || c.kindIs(RuleKind.LOCAL_VAR_NO_SEMI));
    }

    /// True if a stmt is one of the block-shaped control-flow constructs.
    private static boolean isBlockShapedStmt(Cursor stmt) {
        if (! (stmt instanceof Cursor.Branch br)) {
            return false;
        }

        var tokens = br.cst().tokens();

        for (int t = br.firstTokenIdx(); t <= br.lastTokenIdx(); t++) {
            if (tokens.isTrivia(t)) continue;

            var txt = tokens.textAt(t).toString();

            return switch (txt) {
                case "if", "try", "while", "for", "do", "switch", "synchronized", "{" -> true;
                default -> false;
            };
        }

        return false;
    }

    /// True if `stmt` is a return or throw statement (used to decide whether to insert a
    /// blank line before the final statement of a non-trivial block).
    private static boolean isReturnOrThrowStmt(Cursor stmt) {
        if (! (stmt instanceof Cursor.Branch br)) {
            return false;
        }

        var tokens = br.cst().tokens();

        for (int t = br.firstTokenIdx(); t <= br.lastTokenIdx(); t++) {
            if (tokens.isTrivia(t)) continue;

            var txt = tokens.textAt(t).toString();

            return "return".equals(txt) || "throw".equals(txt);
        }

        return false;
    }

    private void printAlignedBlockStatements(List<Cursor> stmts, int alignCol) {
        int bodyCol = alignCol + config.indentSize();
        // Use forcedIndentCol so emitLeadingComments uses printAlignedTo(bodyCol)
        // instead of printIndent() (which uses indentLevel and may round incorrectly).
        int savedForcedIndent = forcedIndentCol;

        forcedIndentCol = bodyCol;
        for (int i = 0; i < stmts.size(); i++) {
            var stmt = stmts.get(i);
            // Same uniform blank-line rules as the non-aligned body path (R1/R2/R3).
            if (i >= 1 && !hasLeadingComment(stmt) && needsBlankBeforeStmt(stmts.get(i - 1), stmt)) {
                newline();
            }
            // Skip printAlignedTo for stmts with leading comments: currentColumn is
            // already 0 from the preceding newline, so emitLeadingComments won't add
            // an extra blank line (it only newlines when currentColumn > 0).
            if (!hasUnEmittedLeadingComment(stmt)) {
                printAlignedTo(bodyCol);
            }

            printNode(stmt);
            newline();
        }

        forcedIndentCol = savedForcedIndent;
    }

    private void printSwitchBlock(Cursor.Branch switchBlock) {
        emit(" {");
        indentLevel++;
        var rules = childrenByRule(switchBlock, RuleKind.SWITCH_RULE);

        if (!rules.isEmpty()) {
            newline();
            for (var rule : rules) {
                // Switch case bodies render inline regardless of width — wrap in an
                // inline-expression scope so chain/additive/etc. break logic stays inline.
                try (var inlineScope = alignment.enterInlineExpression()) {
                    printOwnLineChildContent(rule);
                }
                // Rescue a same-line trailing comment after the arm (e.g. `default -> null; // x`)
                // before the newline — safe because a newline follows (bug report mechanism E4).
                flushInSpanTrailingComment(rule);
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
        printChainWithPrimary(primary,
                              singletonLinks(childrenByRule(postfix, RuleKind.POST_OP)),
                              countDotMethodChainLinks(postfix));
    }

    /// Statement-position chains are `StmtExpr -> Primary CallChain` since peglib 0.7.1
    /// (the JLS 14.8 rework), not `Postfix -> Primary PostOp*`. It is the same chain with a
    /// different spine, so normalise it to links and reuse the identical break/align logic.
    private void printStmtExpr(Cursor.Branch stmtExpr) {
        var primary = childByRule(stmtExpr, RuleKind.PRIMARY);

        if (primary.isEmpty()) {
            // `StmtExpr <- ('++' / '--') Unary` — no chain to align.
            walkTokens(stmtExpr);

            return;
        }

        printChainWithPrimary(primary.or((Cursor) null), callChainLinks(stmtExpr), 0);
    }

    /// A chain LINK is one logical `.name(args)` step, which may span more than one node.
    /// `Postfix` keeps it as a single `PostOp`; `CallChain` splits it into `ChainOp[.name]`
    /// followed by an invocation op. Merging the split form is what keeps link COUNTS — and
    /// therefore every break decision — identical between statement and expression position.
    private void printChainWithPrimary(Cursor primary, List<List<Cursor>> links, int nestedDotMethodCount) {
        if (measuringMode) {
            // Measurement only needs width — emit primary + each op inline.
            printNode(primary);
            forEachOp(links, this::printNode);

            return;
        }

        var flatOps = flattenLinks(links);
        var dotMethodLinks = links.stream().filter(FlowPrinter::isDotMethodLink).toList();
        boolean primaryHasMethodAccess = hasMethodAccessInPrimary(primary);
        boolean hasInvocationOfMethodInPrimary = primaryHasMethodAccess && links.stream()
                                                                                .anyMatch(link -> isBareInvocationPostOp(link.getFirst()));
        int chainLinkCount = Math.max(nestedDotMethodCount,
                                      dotMethodLinks.size() + (hasInvocationOfMethodInPrimary
                                                               ? 1
                                                               : 0));

        if (shouldBreakChain(primary, chainLinkCount, flatOps)) {
            printMethodChainAligned(primary, links, dotMethodLinks, hasInvocationOfMethodInPrimary);
        } else {
            boolean canInline = fitsOnLineUnary(primary, flatOps);

            printNode(primary);
            forEachOp(links,
                      op -> {
                          if (canInline) {
                              printNodeContent(op);
                          } else {
                              printNode(op);
                          }
                      });
        }
    }

    private static List<List<Cursor>> singletonLinks(List<Cursor> ops) {
        return ops.stream()
                  .map(List::of)
                  .toList();
    }

    private static List<Cursor> flattenLinks(List<List<Cursor>> links) {
        return links.stream()
                    .flatMap(List::stream)
                    .toList();
    }

    private void forEachOp(List<List<Cursor>> links, java.util.function.Consumer<Cursor> action) {
        for (var link : links) {
            for (var op : link) {
                action.accept(op);
            }
        }
    }

    /// Walk the right-recursive `CallChain` spine, collecting its operator nodes in source
    /// order, then merge each `.name` with the invocation that follows it.
    private static List<List<Cursor>> callChainLinks(Cursor.Branch stmtExpr) {
        var ops = new ArrayList<Cursor>();

        for (var chain = firstCallChain(stmtExpr); chain != null; chain = firstCallChain(chain)) {
            for (var child : children(chain)) {
                if (child.kindIsAny(RuleKind.CHAIN_OP, RuleKind.CALL_OP)) {
                    ops.add(child);
                }
            }
        }

        return mergeDotNameWithInvocation(ops);
    }

    private static Cursor.Branch firstCallChain(Cursor node) {
        for (var child : children(node)) {
            if (child.kindIs(RuleKind.CALL_CHAIN) && child instanceof Cursor.Branch branch) {
                return branch;
            }
        }

        return null;
    }

    /// `.c` + `(y)` become one link so `a.b().c(y)` counts 2 links as a statement, matching
    /// what it counts as an expression. `.new Foo(...)` already carries its own parens and is
    /// left alone.
    private static List<List<Cursor>> mergeDotNameWithInvocation(List<Cursor> ops) {
        var links = new ArrayList<List<Cursor>>();
        int i = 0;

        while (i < ops.size()) {
            var op = ops.get(i);
            boolean mergeable = i + 1 < ops.size()
                                && startsWith(op, ".")
                                && !containsToken(op, "(")
                                && startsWith(ops.get(i + 1), "(");

            if (mergeable) {
                links.add(List.of(op, ops.get(i + 1)));
                i += 2;
            } else {
                links.add(List.of(op));
                i++;
            }
        }

        return links;
    }

    /// A multi-node link is by construction `.name` + invocation, i.e. a dot-method call.
    private static boolean isDotMethodLink(List<Cursor> link) {
        return link.size() > 1 || isDotMethodOp(link.getFirst());
    }

    private static boolean startsWith(Cursor node, String text) {
        var tokens = node.cst().tokens();

        for (int t = node.firstTokenIdx(); t <= node.lastTokenIdx(); t++) {
            if (!tokens.isTrivia(t)) {
                return text.contentEquals(tokens.textAt(t));
            }
        }

        return false;
    }

    private static boolean containsToken(Cursor node, String text) {
        var tokens = node.cst().tokens();

        for (int t = node.firstTokenIdx(); t <= node.lastTokenIdx(); t++) {
            if (!tokens.isTrivia(t) && text.contentEquals(tokens.textAt(t))) {
                return true;
            }
        }

        return false;
    }

    private static boolean isDotMethodOp(Cursor op) {
        return startsWith(op, ".") && containsToken(op, "(");
    }

    /// Sequencer-as-steps: chain (2+ method calls) breaks vertically in TAIL contexts
    /// (return/throw expression, lambda body), and — width-aware fallback — in statement
    /// position when its flat rendering would exceed `maxLineLength`. Exception: when the
    /// receiver is a static factory call (e.g. `Class.method(...)`) and there are exactly 2
    /// chain links AND the first invocation's args don't themselves have complex multi-call
    /// content, the chain stays inline — matches the idiom `Result.all(args).map(Tuple3::new)`.
    /// Statement-position chains that FIT on the line stay inline.
    private boolean shouldBreakChain(Cursor primary, int chainLinkCount, List<Cursor> postOps) {
        if (chainLinkCount < 2 || alignment.isInInlineExpression()) {
            return false;
        }

        if (chainLinkCount == 2 && primary != null && isStaticFactoryReceiver(primary) && !firstPostOpHasComplexArgs(postOps)) {
            return false;
        }

        if (alignment.isInTailContext()) {
            return true;
        }
        // Statement-position chain: break only when the flat rendering overflows the line.
        return ! fitsOnLineUnary(primary, postOps);
    }

    /// True if the first PostOp in the list carries an Args subtree where at least one
    /// argument contains a `.method(...)` call (signals the args layout will break
    /// vertically, dragging the surrounding chain into vertical layout too).
    private boolean firstPostOpHasComplexArgs(List<Cursor> postOps) {
        if (postOps.isEmpty()) return false;

        var first = postOps.get(0);

        if (! (first instanceof Cursor.Branch br)) return false;

        return br.descendants()
                 .filter(c -> c.kindIs(RuleKind.ARGS))
                 .findFirst()
                 .map(args -> {
                          if (! (args instanceof Cursor.Branch ab)) return false;

                          var exprs = childrenByRule(ab, RuleKind.EXPR);

                          if (exprs.size() < 2) return false;

                          return exprs.stream()
                                      .anyMatch(e -> METHOD_CALL_PATTERN.matcher(text(e)).find());
                      })
                 .orElse(false);
    }

    /// True if `primary` is a class-like receiver — its first token is an identifier
    /// starting with an uppercase letter (heuristic for a class type such as `Result` in
    /// `Result.all(...)` or `Result.success(...)`). Used to identify the "static factory"
    /// chain receiver, which inlines a 2-link chain.
    private boolean isStaticFactoryReceiver(Cursor primary) {
        var t = text(primary).trim();

        return ! t.isEmpty() && Character.isUpperCase(t.charAt(0));
    }

    private boolean fitsOnLineUnary(Cursor primary, List<Cursor> postOps) {
        int width = primary != null
                    ? measureWidth(primary)
                    : 0;

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
        boolean hasInvocationOfMethodInPrimary = primaryHasMethodAccess && postOps.stream()
                                                                                  .anyMatch(this::isBareInvocationPostOp);
        int chainLinkCount = Math.max(allDotMethodCount,
                                      dotPlusParenPostOps.size() + (hasInvocationOfMethodInPrimary
                                                                    ? 1
                                                                    : 0));
        boolean shouldBreakChain = shouldBreakChain(primary, chainLinkCount, postOps);

        if (shouldBreakChain && !measuringMode) {
            printMethodChainAligned(primary,
                                    singletonLinks(postOps),
                                    singletonLinks(dotPlusParenPostOps),
                                    hasInvocationOfMethodInPrimary);
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
                                         List<List<Cursor>> links,
                                         List<List<Cursor>> methodCallLinks,
                                         boolean primaryHasInvocation) {
        int startColumn = currentColumn;
        int alignColumn = startColumn;
        // Identify a link by its head node; a merged link's head is its `.name` op.
        var methodCallSet = new HashSet<Cursor>(methodCallLinks.stream()
                                                               .map(List::getFirst)
                                                               .toList());

        if (primary != null) {
            // If primary contains an internal `.` (e.g. `value.trim`), the chain anchor
            // should be that `.`'s column — that's where the FIRST chain call begins.
            // Compute the suffix length AFTER the last `.` (e.g. `trim` = 4) so
            // alignColumn = currentColumn (post-primary) − suffixLen − 1 (the `.` itself).
            int suffixAfterLastDot = primaryHasInvocation
                                     ? suffixAfterLastDotInPrimary(primary)
                                     : -1;

            printNodeContent(primary);
            if (suffixAfterLastDot >= 0) {
                alignColumn = currentColumn - suffixAfterLastDot - 1;
            } else {
                alignColumn = currentColumn;
            }
        }

        try (var scope = alignment.enterChain(alignColumn)) {
            // Rule: primary + first chain call stay inline; remaining dot-method postOps
            // each go on their own line aligned to the chain column. This holds uniformly
            // after a wrapped-args head call too — the first follow-up always breaks onto
            // its own line at the chain column rather than gluing to the closing `)`.
            boolean firstMethodCallPending = !primaryHasInvocation;

            for (int pi = 0; pi < links.size(); pi++) {
                var link = links.get(pi);
                var postOp = link.getFirst();
                boolean isMethodCall = methodCallSet.contains(postOp);
                // A leading comment on this post-op forces it onto its own line at the chain
                // column. Handle positioning here (a single newline to column 0, then let
                // emitLeadingComments own the indent) and SKIP the normal pre-align below, which
                // would otherwise stack a spurious blank line ahead of the comment (bug report S5).
                boolean hasLead = !measuringMode && hasUnEmittedLeadingComment(postOp);

                if (hasLead) {
                    if (currentColumn > 0) {
                        newline();
                    }

                    if (isMethodCall && !firstMethodCallPending && scope.lastPostOpWasBrokenArgs()) {
                        scope.clearBrokenArgsAnchor();
                    }

                    int savedForcedIndentCol = forcedIndentCol;

                    forcedIndentCol = alignColumn;
                    emitLeadingComments(postOp);
                    forcedIndentCol = savedForcedIndentCol;
                } else if (isMethodCall && !firstMethodCallPending) {
                    if (scope.lastPostOpWasBrokenArgs()) {
                        // Break the first follow-up after a wrapped-args head call onto its
                        // own line, aligned to the chain (head call's dot) column.
                        newline();
                        printAlignedTo(alignColumn);
                        scope.clearBrokenArgsAnchor();
                    } else {
                        // Subsequent method calls align to the chain column, or to body
                        // indent when the previous post-op wrapped its own args.
                        newline();
                        int anchor = scope.nextDotMethodAnchor(alignColumn);

                        printAlignedTo(anchor);
                    }
                }

                int lineBefore = currentLine;
                boolean isBareInvoc = isBareInvocationPostOp(postOp);

                for (var op : link) {
                    printNodeContent(op);
                }

                // Emit trailing line comments from the next link's leading trivia
                // before the newline that separates chain calls.
                if (pi + 1 < links.size()) {
                    emitTrailingCommentsFrom(links.get(pi + 1).getFirst());
                }

                boolean spanned = currentLine != lineBefore;
                boolean linkHasLambda = link.stream().anyMatch(FlowPrinter::containsLambda);

                scope.notePostOpEmitted(spanned, linkHasLambda);
                if (isBareInvoc && spanned && !linkHasLambda) {
                    // Broken bare-args: the next dot-method breaks to the chain column.
                    scope.noteBrokenArgsPostOp();
                }

                if (isMethodCall) {
                    firstMethodCallPending = false;
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
        if (! (primary instanceof Cursor.Branch pb)) {
            return null;
        }

        return pb.descendants()
                 .filter(c -> c.kindIs(RuleKind.POSTFIX))
                 .findFirst()
                 .filter(c -> c instanceof Cursor.Branch)
                 .map(c -> (Cursor.Branch) c)
                 .orElse(null);
    }

    /// A dot-method PostOp's FIRST non-trivia token is `.` and the range contains `(`
    /// somewhere (the call's args paren — could be nested inside args expressions, but at
    /// least one exists for invocation forms). Distinguished from a bare `(args)` postOp
    /// whose first token is `(`.
    private boolean isDotMethodPostOp(Cursor postOp) {
        var tokens = postOp.cst().tokens();
        boolean firstIsDot = false;
        boolean seenFirst = false;
        boolean hasParen = false;

        for (int t = postOp.firstTokenIdx(); t <= postOp.lastTokenIdx(); t++) {
            if (tokens.isTrivia(t)) continue;

            var s = tokens.textAt(t).toString();

            if (!seenFirst) {
                firstIsDot = ".".equals(s);
                seenFirst = true;
            }

            if ("(".equals(s)) hasParen = true;
        }

        return firstIsDot && hasParen;
    }

    /// A bare invocation PostOp's FIRST non-trivia token is `(` (e.g. `(args)`, the
    /// invocation following an already-qualified primary like `Result.all`). Distinguished
    /// from a `.method(args)` postOp where the first token is `.`. Inner `.` tokens belong
    /// to nested expressions and are not relevant.
    private boolean isBareInvocationPostOp(Cursor postOp) {
        var tokens = postOp.cst().tokens();

        for (int t = postOp.firstTokenIdx(); t <= postOp.lastTokenIdx(); t++) {
            if (tokens.isTrivia(t)) continue;

            return "(".equals(tokens.textAt(t).toString());
        }

        return false;
    }

    private boolean hasMethodAccessInPrimary(Cursor primary) {
        // Primary contains a method-access path if its text contains a dot
        // outside of identifier suffix (we check the structural Primary's source text).
        var t = text(primary);

        return t.contains(".");
    }

    /// Return the total non-trivia text length AFTER the last `.` token within `primary`
    /// (e.g. `value.trim` -> 4 for `trim`), or -1 if no `.`. Used to compute the chain
    /// alignment column when the primary path itself contains a method access — we subtract
    /// this plus 1 (for the `.`) from post-primary currentColumn to get the dot's column.
    private int suffixAfterLastDotInPrimary(Cursor primary) {
        var tokens = primary.cst().tokens();
        int startTok = primary.firstTokenIdx();
        int lastTok = primary.lastTokenIdx();

        while (startTok <= lastTok && tokens.isTrivia(startTok)) startTok++;

        int lastDotTok = -1;

        for (int t = startTok; t <= lastTok; t++) {
            if (tokens.isTrivia(t)) continue;

            if (".".equals(tokens.textAt(t).toString())) {
                lastDotTok = t;
            }
        }

        if (lastDotTok < 0) return -1;

        int suffix = 0;

        for (int t = lastDotTok + 1; t <= lastTok; t++) {
            if (tokens.isTrivia(t)) continue;

            suffix += tokens.textAt(t).length();
        }

        return suffix;
    }

    private void printPostOp(Cursor.Branch postOp) {
        // PostOps look like `.method(args)`, `<TypeArgs>method(args)`, `(args)`, or `[expr]`.
        // We walk tokens and let child branches handle their own rendering.
        walkTokensWith(postOp,
                       new TokenWalker() {
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
        if (measuringMode) {
            walkTokens(args);

            return;
        }

        boolean hasComplexArgs = hasComplexArguments(args);

        if (hasComplexArgs) {
            printBrokenArgs(args);

            return;
        }

        int argsWidth = measureWidth(args);

        if (currentColumn + argsWidth <= config.maxLineLength()) {
            // Inline: walk tokens but use printNodeContent for child expressions. Suppress
            // chain breaking inside inline args — chains that fit horizontally as an
            // argument stay inline (e.g. `Result.all(user.map(a).map(b), ...)`).
            try (var scope = alignment.enterInlineExpression()) {
                walkTokensWith(args,
                               new TokenWalker() {
                    @Override
                    public void onChild(Cursor c) {
                                   printNodeContent(c);
                               }

                    @Override
                    public void onToken(int kind, String text) {
                                   emitToken(text);
                               }
                });
            }
        } else {
            printBrokenArgs(args);
        }
    }

    /// True if the node's token range contains a line (`//`) or doc-line (`///`) comment token.
    /// Token-based (not text-based), so `//` inside a string literal does not trigger it.
    private boolean hasLineCommentToken(Cursor node) {
        var tokens = node.cst().tokens();

        for (int t = node.firstTokenIdx(); t <= node.lastTokenIdx(); t++) {
            int kind = tokens.kindAt(t);

            if (kind == 1 || kind == 3) {
                return true;
            }
        }

        return false;
    }

    private boolean hasComplexArguments(Cursor args) {
        // A line comment anywhere in the argument list cannot be rendered inline (`f(a, b // c)`
        // would swallow `)` into the comment), so force broken layout — the broken walk then emits
        // the trailing comment on its argument's line (bug report mechanism B, last-arg trailing).
        if (hasLineCommentToken(args)) {
            return true;
        }
        // Block lambda args that contain line comments are underestimated by measureWidth()
        // (comments are emitted to output, not measureBuffer). Force broken layout when
        // a block lambda body has leading comments — the `//` check is a reliable proxy.
        var argsText = text(args);

        if (argsText.contains("-> {") && argsText.contains("//")) return true;

        var exprs = childrenByRule(args, RuleKind.EXPR);

        if (exprs.size() >= 2) {
            // 2+ lambda args: always break (each lambda on own line).
            int lambdaCount = 0;

            for (var expr : exprs) {
                if (containsLambdaArrow(expr)) lambdaCount++;
            }

            if (lambdaCount >= 2) {
                // Force complex layout only when any lambda has non-trivial content:
                // block body, method call in body, or string concatenation.
                // Short expression lambdas like `s -> s` or `c -> ""` can stay inline.
                boolean anyComplexLambda = exprs.stream()
                                                .anyMatch(e -> {
                                                              var t = text(e);

                                                              return t.contains("-> {") || containsMethodCall(t) || t.contains(" + ");
                                                          });

                if (anyComplexLambda) return true;
            }

            for (var expr : exprs) {
                var exprText = text(expr);

                if (containsMethodCall(exprText) || exprText.contains("-> {")) {
                    return true;
                }

                if (containsTopLevelTernary(expr)) {
                    return true;
                }

                if (alignment.isInBreakingChain() && exprText.contains("(")) {
                    return true;
                }
            }
        }

        return false;
    }

    /// True if `expr` contains a LAMBDA descendant (any `->` form).
    private boolean containsLambdaArrow(Cursor expr) {
        if (! (expr instanceof Cursor.Branch br)) return false;

        return br.descendants()
                 .anyMatch(c -> c.kindIs(RuleKind.LAMBDA));
    }

    /// True if `expr` contains a TERNARY node at its first-level descent (i.e. the
    /// expression IS a ternary, possibly wrapped in trivial precedence nodes). Used to
    /// detect when an argument will break vertically due to a `?:` operator, dragging
    /// the surrounding arg list into broken layout.
    private boolean containsTopLevelTernary(Cursor expr) {
        if (! (expr instanceof Cursor.Branch br)) return false;

        return br.descendants()
                 .anyMatch(c -> c.kindIs(RuleKind.TERNARY) && text(c).contains("?") && text(c).contains(":"));
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
        // A single argument at statement level (no enclosing broken method chain) has no commas
        // to align, and its block-lambda body should indent from the statement, not the deep arg
        // column (bug B3). Inside a broken chain the body aligns to the lambda parameter, so keep
        // lambda-align there. Multi-argument calls always align.
        if (childrenByRule(args, RuleKind.EXPR).size() < 2 && alignment.chainColumn() < 0) {
            printBrokenArgsBody(args, alignCol);

            return;
        }

        try (var scope = alignment.pushLambdaAlign(alignCol)) {
            printBrokenArgsBody(args, alignCol);
        }
    }

    private void printBrokenArgsBody(Cursor.Branch args, int alignCol) {
        boolean[] pendingLineBreak = {false};

        walkTokensWith(args,
                       new TokenWalker() {
            @Override
            public void onChild(Cursor child) {
                           if (child.kindIs(RuleKind.EXPR)) {
                           // If this single arg fits on its own line, render its inner
                           // chains/expressions inline. The args layout already broke at
                           // commas; an individual arg-expression should not force further
                           // vertical breaks unless its own width demands it.
                           int width = measureWidth(child);

                           if (currentColumn + width <= config.maxLineLength()) {
                           try (var inlineScope = alignment.enterInlineExpression()) {
                           printNodeContent(child);
                       }
                       } else {
                           printNodeContent(child);
                       }
                           // Rescue a comment trailing this argument (inside its span) before the next
                           // delimiter; a line comment forces that delimiter onto a new line.
                           if (flushInSpanTrailingComment(child)) {
                           pendingLineBreak[0] = true;
                       }
                       } else {
                           printNode(child);
                       }
                       }

            @Override
            public void onToken(int kind, String text) {
                           if (",".equals(text)) {
                           if (pendingLineBreak[0]) {
                           newline();
                           printAlignedTo(alignCol);
                           pendingLineBreak[0] = false;
                       }

                           emit(",");
                           newline();
                           printAlignedTo(alignCol);
                       } else {
                           emitToken(text);
                       }
                       }
        });
        // Last argument carried a trailing line comment, OR own-line comments dangle before `)`:
        // break so the enclosing `)` (emitted by the post-op after this returns) lands on its own
        // line instead of being swallowed (bug report mechanisms B last-arg / E2).
        boolean danglingOwnLine = emitDanglingOwnLineArgComments(args, alignCol);

        if (pendingLineBreak[0] || danglingOwnLine) {
            newline();
            printAlignedTo(Math.max(0, alignCol - 1));
        }
    }

    // ===== Lambda =====
    private void printLambda(Cursor.Branch lambda) {
        printLambdaWith(lambda, this::printNode);
    }

    /// A lambda BODY is a tail context — chains inside it break vertically — and both entry
    /// points must establish it. Since peglib 0.7.1 hoisted `Lambda` out of `Primary`
    /// (`Expr <- Lambda / Assignment`), an argument lambda is reached through
    /// `printNodeContent` rather than `printNode`, so a content-only variant that skipped the
    /// tail context silently stopped breaking those chains.
    private void printLambdaWith(Cursor.Branch lambda, java.util.function.Consumer<Cursor> printHead) {
        walkTokensWith(lambda,
                       new TokenWalker() {
            boolean afterArrow = false;

            @Override
            public void onChild(Cursor child) {
                           if (afterArrow) {
                           try (var scope = alignment.enterTailContext()) {
                           printNodeContent(child);
                       }

                           afterArrow = false;
                       } else {
                           printHead.accept(child);
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
            walkTokensWith(params,
                           new TokenWalker() {
                @Override
                public void onChild(Cursor c) {
                               printNodeContent(c);
                           }

                @Override
                public void onToken(int kind, String text) {
                               emitToken(text);
                           }
            });
        } else {
            printBrokenParams(params);
        }
    }

    private void printBrokenParams(Cursor.Branch params) {
        int alignCol = currentColumn;

        walkTokensWith(params,
                       new TokenWalker() {
            @Override
            public void onChild(Cursor child) {
                           if (child.kindIs(RuleKind.PLAIN_PARAM)
                               || child.kindIs(RuleKind.LAST_PARAM)
                               || child.kindIs(RuleKind.RECEIVER_PARAM)) {
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
        walkTokensWith(primary,
                       new TokenWalker() {
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

        walkTokensWith(recordDecl,
                       new TokenWalker() {
            @Override
            public void onChild(Cursor child) {
                           if (child.kindIs(RuleKind.RECORD_COMPONENTS)) {
                           printNodeContent(child);
                           afterComponents[0] = true;
                       } else if (child.kindIs(RuleKind.RECORD_BODY)) {
                           // RECORD_BODY may be a Leaf (empty `{}`) or a Branch (has members).
                           // Always include a space before `{` (matches Records.java golden where
                           // empty bodies render as `{}` with a separating space, never `(){}`.
                           if (child instanceof Cursor.Branch rbBranch) {
                           printRecordBody(rbBranch);
                       } else {
                           emit(" {}");
                       }

                           afterComponents[0] = false;
                       } else {
                           printNode(child);
                       }
                       }

            @Override
            public void onToken(int kind, String text) {
                           if ("{".equals(text) && afterComponents[0]) {
                           emit(" {");
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
        // A line comment between components cannot be inlined — force broken layout so each
        // component (and its leading comment) lands on its own line (bug report mechanism A).
        if (!hasLineCommentToken(components) && currentColumn + width + 3 <= config.maxLineLength()) {
            walkTokensWith(components,
                           new TokenWalker() {
                @Override
                public void onChild(Cursor c) {
                               printNodeContent(c);
                           }

                @Override
                public void onToken(int kind, String text) {
                               emitToken(text);
                           }
            });
        } else {
            printBrokenRecordComponents(components);
        }
    }

    private void printBrokenRecordComponents(Cursor.Branch components) {
        int alignCol = currentColumn;

        walkTokensWith(components,
                       new TokenWalker() {
            @Override
            public void onChild(Cursor child) {
                           if (child.kindIs(RuleKind.RECORD_COMP)) {
                           printOwnLineChildContentAligned(child, alignCol);
                       } else {
                           printNode(child);
                       }
                       }

            @Override
            public void onToken(int kind, String text) {
                           if (",".equals(text)) {
                           emit(",");
                           newline();
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

        walkTokensWith(resourceSpec,
                       new TokenWalker() {
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
        typeContextDepth++;
        walkTokens(typeArgs);
        typeContextDepth--;
    }

    private void printTypeParams(Cursor.Branch typeParams) {
        typeContextDepth++;
        walkTokens(typeParams);
        typeContextDepth--;
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
            // Align `?` and `:` under the first non-space char of the cond expression.
            // `currentColumn` at entry sits BEFORE any auto-space that the spacing
            // machinery will emit before the cond's first token. We probe with a
            // representative identifier-like first char ("x") to compute whether
            // that auto-space is pending; if so, the cond's first emit lands at
            // currentColumn + 1 rather than currentColumn. Each ternary uses its
            // OWN cond-start column — nested ternaries do NOT inherit; e.g. the
            // inner ternary in `cond ? a : (b ? c : d)` aligns at `b`'s column.
            int alignCol = currentColumn + (needsSpaceBefore("x")
                                            ? 1
                                            : 0);
            boolean[] skipNext = {false};

            walkTokensWith(ternary,
                           new TokenWalker() {
                @Override
                public void onChild(Cursor child) {
                               if (skipNext[0]) {
                               printNodeContent(child);
                               skipNext[0] = false;
                           } else {
                               // Ternary cond: suppress LOG_AND/LOG_OR breaking so `&&`/`||`
                               // in the cond stay on one line per the always-break-`?`/`:` rule.
                               try (var tc = alignment.enterTernaryCond()) {
                               printNode(child);
                           }
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
            walkTokensWith(additive,
                           new TokenWalker() {
                @Override
                public void onChild(Cursor c) {
                               printNodeContent(c);
                           }

                @Override
                public void onToken(int kind, String text) {
                               emitToken(text);
                           }
            });

            return;
        }

        boolean hasStringLit = containsStringLit(additive);

        if (!hasStringLit) {
            walkTokensWith(additive,
                           new TokenWalker() {
                @Override
                public void onChild(Cursor c) {
                               printNodeContent(c);
                           }

                @Override
                public void onToken(int kind, String text) {
                               emitToken(text);
                           }
            });

            return;
        }

        int width = measureWidth(additive);

        if (currentColumn + width <= config.maxLineLength()) {
            walkTokensWith(additive,
                           new TokenWalker() {
                @Override
                public void onChild(Cursor c) {
                               printNodeContent(c);
                           }

                @Override
                public void onToken(int kind, String text) {
                               emitToken(text);
                           }
            });

            return;
        }
        // Multi-line: break before string-literal operands. Suppress breaking inside
        // switch case expressions — goldens render those inline regardless of width.
        if (alignment.isInInlineExpression()) {
            walkTokensWith(additive,
                           new TokenWalker() {
                @Override
                public void onChild(Cursor c) {
                               printNodeContent(c);
                           }

                @Override
                public void onToken(int kind, String text) {
                               emitToken(text);
                           }
            });

            return;
        }

        var kids = additive.children().toList();
        // alignCol = currentColumn - 1 so that the `+` lands one column LEFT of the
        // first operand (the goldens align `+` under the first operand's quote, but the
        // operand itself sits one column to the right because of the trailing space
        // after `+`).
        int alignCol = Math.max(0, currentColumn - 1);
        // Key by firstTokenIdx — Cursor identity is unstable across children() calls.
        var operandInfo = new HashMap<Integer, OperandInfo>();

        for (var child : kids) {
            operandInfo.put(child.firstTokenIdx(), new OperandInfo(startsWithStringLit(child), measureWidth(child)));
        }

        boolean[] firstPrinted = {false};
        boolean[] pendingPlus = {false};

        walkTokensWith(additive,
                       new TokenWalker() {
            @Override
            public void onChild(Cursor child) {
                           if (pendingPlus[0]) {
                           var info = operandInfo.get(child.firstTokenIdx());
                           boolean startsWithStr = info != null && info.startsWithString();

                           if (startsWithStr && firstPrinted[0]) {
                           // Multi-line additive: every `+` followed by a string-literal
                           // operand breaks onto its own line (the goldens render each
                           // continuation aligned under the first operand).
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

    /// Print a `LOG_AND` (`&&`) chain. In TAIL contexts (return/throw/lambda body),
    /// always break — each `&&` lands on a new line aligned to the first operand. In
    /// non-tail contexts (assignment RHS, args, switch case), render inline.
    private void printLogAnd(Cursor.Branch logAnd) {
        // Count `&&` operators — only break when 2+ operators (3+ operands);
        // short `a && b` chains stay inline.
        int operatorCount = 0;
        var tokens = logAnd.cst().tokens();

        for (int t = logAnd.firstTokenIdx(); t <= logAnd.lastTokenIdx(); t++) {
            if (tokens.isTrivia(t)) continue;

            if ("&&".equals(tokens.textAt(t).toString())) operatorCount++;
        }

        if (measuringMode || alignment.isInInlineExpression() || alignment.isInTernaryCond() || !alignment.isInTailContext() || operatorCount < 2) {
            walkTokensWith(logAnd,
                           new TokenWalker() {
                @Override
                public void onChild(Cursor c) {
                               printNodeContent(c);
                           }

                @Override
                public void onToken(int kind, String text) {
                               emitToken(text);
                           }
            });

            return;
        }
        // Multi-line: break before each `&&` aligned to the first operand's column.
        // currentColumn at entry sits before any pending auto-space; add 1 if the next
        // emit would auto-space (e.g. after `return`).
        int alignCol = currentColumn + (needsSpaceBefore("x")
                                        ? 1
                                        : 0);
        boolean[] firstPrinted = {false};

        walkTokensWith(logAnd,
                       new TokenWalker() {
            @Override
            public void onChild(Cursor c) {
                           printNodeContent(c);
                           firstPrinted[0] = true;
                       }

            @Override
            public void onToken(int kind, String text) {
                           if ("&&".equals(text) && firstPrinted[0]) {
                           newline();
                           printAlignedTo(alignCol);
                           emit("&& ");
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

        return ! t.isEmpty() && t.charAt(0) == '"';
    }

    // ===== Content printing (no trivia, with spacing) =====
    private void printNodeContent(Cursor node) {
        switch (node) {
            case Cursor.Leaf leaf -> emitLeafTokens(leaf);
            case Cursor.ErrorNode err -> emitToken(err.skippedText().toString());
            case Cursor.Branch br -> {
                switch (br.kind()) {
                    case LAMBDA -> printLambdaContent(br);
                    case TYPED_LAMBDA_PARAM, VAR_LAMBDA_PARAM -> printLambdaParam(br);
                    case ARGS -> printArgs(br);
                    case BLOCK -> printBlock(br);
                    case POSTFIX -> printPostfix(br);
                    case STMT_EXPR -> printStmtExpr(br);
                    case POST_OP -> printPostOp(br);
                    case TERNARY -> printTernary(br);
                    case ADDITIVE -> printAdditive(br);
                    case LOG_AND -> printLogAnd(br);
                    case PARAMS, ORDINARY_PARAMS -> printParams(br);
                    case RECORD_COMPONENTS -> printRecordComponents(br);
                    case TYPE_ARGS -> printTypeArgs(br);
                    case TYPE_PARAMS -> printTypeParams(br);
                    case SWITCH_BLOCK -> printSwitchBlock(br);
                    case UNARY -> printUnary(br);
                    case FIELD_DECL, INTERFACE_FIELD_DECL -> printFieldDecl(br);
                    case PLAIN_PARAM, LAST_PARAM, RECEIVER_PARAM -> printParam(br);
                    case ENUM_BODY -> printEnumBody(br);
                    case RECORD_BODY -> printRecordBody(br);
                    case CLASS_BODY -> printClassBody(br);
                    case INTERFACE_BODY -> printInterfaceBody(br);
                    case ANNOTATION_BODY -> printAnnotationBody(br);
                    case PRIMARY -> printPrimary(br);
                    case RECORD_DECL -> printRecordDecl(br);
                    case RESOURCE_SPEC -> printResourceSpec(br);
                    default -> {
                        boolean breakAfterAnnotation = annotationsBreakOnNewlineInParent(br.kind());

                        walkTokensWith(br,
                                       new TokenWalker() {
                            @Override
                            public void onChild(Cursor c) {
                                           printNodeContent(c);
                                           if (breakAfterAnnotation && c.kindIs(RuleKind.ANNOTATION)) {
                                           newline();
                                           printIndent();
                                       }
                                       }

                            @Override
                            public void onToken(int kind, String text) {
                                           emitToken(text);
                                       }
                        });
                    }
                }
            }
        }
    }

    private void printLambdaContent(Cursor.Branch lambda) {
        printLambdaWith(lambda, this::printNodeContent);
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
            boolean isLine = kind == 1 || kind == 3;  // LINE_COMMENT or DOC_LINE_COMMENT
            boolean isBlock = kind == 2 || kind == 4;  // BLOCK_COMMENT or DOC_BLOCK_COMMENT
            if (isLine) {
                if (currentColumn > 0) {
                    newline();
                }

                if (forcedIndentCol >= 0) {
                    printAlignedTo(forcedIndentCol);
                } else {
                    printIndent();
                }

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
            if (forcedIndentCol >= 0) {
                printAlignedTo(forcedIndentCol);
            } else {
                printIndent();
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
            // Generic '<' (inside TYPE_ARGS / TYPE_PARAMS) glues to the type argument that follows
            // (`List<String`, `static <T`). A relational/shift operator '<' (any non-type context)
            // glues only a following '<' to form '<<'; before an operand it takes a space, added in
            // checkSpaceRules (e.g. `a < 0`, `v << n`).
            if (typeContextDepth > 0) {
                return true;
            }

            return firstChar == '<';
        }

        if (firstChar == '>' && lastChar == '?') {
            return true;
        }
        // Unary minus/plus: when the previous emitted token was `-` or `+` AND it sits in
        // a unary-operator position (preceded by a unary-context char), suppress the
        // space before the operand.
        if ((lastChar == '-' || lastChar == '+') && isUnaryPosition()) {
            return true;
        }

        return false;
    }

    /// True iff the previously emitted `-`/`+` is in a unary-operator position where the
    /// goldens render with NO trailing space. Applies after any binary-op char, `(`, `,`,
    /// or unary-context keyword: `return -x`, `throw -1`, `f(-x)`, `f(a, -b)`,
    /// `x = -1`, `cond ? -x : y`. After an operand (`a - b`), the operator is binary —
    /// return false.
    private boolean isUnaryPosition() {
        if (measuringMode || output.length() < 2) {
            return false;
        }
        // The operator sits at output[length-1]. Skip a single space to find the char
        // preceding it.
        int spaceIdx = output.length() - 2;
        boolean sawSpace = spaceIdx >= 0 && output.charAt(spaceIdx) == ' ';
        int beforeIdx = sawSpace
                        ? spaceIdx - 1
                        : spaceIdx;

        if (beforeIdx < 0) {
            return true;
        }

        char before = output.charAt(beforeIdx);

        if (before == '(' || before == ',' || BINARY_OP_CHARS.contains(before)) {
            return true;
        }
        // Keyword-prefixed: only when the operator immediately follows a space which
        // immediately follows the keyword (no intervening operand). The keyword ends
        // with a letter, so verify `before` is a letter AND `lastWord` is in the
        // unary-context keyword set.
        if (sawSpace && Character.isLetter(before) && SPACE_AFTER_KEYWORDS.contains(lastWord)) {
            // Confirm the letter run ending at `before` actually equals `lastWord` —
            // i.e. nothing was emitted between the keyword and this operator.
            int len = lastWord.length();

            if (beforeIdx + 1 - len >= 0 && output.substring(beforeIdx + 1 - len, beforeIdx + 1).equals(lastWord)) {
                return true;
            }
        }

        return false;
    }

    private boolean checkSpaceRules(String text, char firstChar) {
        // Comma rule
        if (lastChar == ',') {
            return true;
        }
        // Semicolon separator in a `for` header. A statement-terminating `;` is followed by a
        // newline (lastChar would be '\n'), so this only fires mid-line — between for-clauses.
        if (lastChar == ';') {
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
        // Annotation rules: `@` is a type-use or stacked annotation when following
        // `)`, `]`, an identifier char, or `>` (after generic close).
        if (firstChar == '@' && (lastChar == ')' || lastChar == ']' || lastChar == '>' || isIdentifierChar(lastChar))) {
            return true;
        }
        // Angle bracket rules
        if (text.equals("<") || text.equals(">")) {
            return checkAngleBracketRules(text, firstChar);
        }
        // Relational/shift operator '<' (outside any TYPE_ARGS / TYPE_PARAMS context) before its
        // operand. '<' is not in BINARY_OP_CHARS, so it needs this explicit rule; a generic-open
        // '<' (typeContextDepth > 0) was already suppressed in mustNotHaveSpaceBefore and never
        // reaches here. The symmetric '>' operand is handled by checkGenericClosing.
        if (lastChar == '<' && typeContextDepth == 0) {
            return true;
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

        return ! (lastChar == ':' && prevChar == ':');
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
