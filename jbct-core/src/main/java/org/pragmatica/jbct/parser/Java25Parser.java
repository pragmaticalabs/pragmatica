package org.pragmatica.jbct.parser;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generated PEG parser with CST (Concrete Syntax Tree) output.
 * This parser preserves all source information including trivia (whitespace/comments).
 * Depends only on pragmatica-lite:core for Result type.
 */
public final class Java25Parser {

    // === CST Types ===

    public record SourceLocation(int line, int column, int offset) {
        public static final SourceLocation START = new SourceLocation(1, 1, 0);
        public static SourceLocation at(int line, int column, int offset) {
            return new SourceLocation(line, column, offset);
        }
        @Override public String toString() { return line + ":" + column; }
    }

    public record SourceSpan(SourceLocation start, SourceLocation end) {
        public static SourceSpan of(SourceLocation start, SourceLocation end) {
            return new SourceSpan(start, end);
        }
        public int length() { return end.offset() - start.offset(); }
        public String extract(String source) { return source.substring(start.offset(), end.offset()); }
        @Override public String toString() { return start + "-" + end; }
    }

    public sealed interface Trivia {
        SourceSpan span();
        String text();
        record Whitespace(SourceSpan span, String text) implements Trivia {}
        record LineComment(SourceSpan span, String text) implements Trivia {}
        record BlockComment(SourceSpan span, String text) implements Trivia {}
    }

    public sealed interface CstNode {
        SourceSpan span();
        String rule();
        List<Trivia> leadingTrivia();
        List<Trivia> trailingTrivia();

        record Terminal(SourceSpan span, String rule, String text,
                        List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}

        record NonTerminal(SourceSpan span, String rule, List<CstNode> children,
                           List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}

        record Token(SourceSpan span, String rule, String text,
                     List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}
    }

    public record ParseError(SourceLocation location, String reason) implements Cause {
        @Override
        public String message() {
            return reason + " at " + location;
        }
    }

    // === Parse Context ===

    private String input;
    private int pos;
    private int line;
    private int column;
    private Map<Long, CstParseResult> cache;
    private Map<String, String> captures;
    private boolean inTokenBoundary;

    private void init(String input) {
        this.input = input;
        this.pos = 0;
        this.line = 1;
        this.column = 1;
        this.cache = new HashMap<>();
        this.captures = new HashMap<>();
        this.inTokenBoundary = false;
    }

    private SourceLocation location() {
        return SourceLocation.at(line, column, pos);
    }

    private boolean isAtEnd() {
        return pos >= input.length();
    }

    private char peek() {
        return input.charAt(pos);
    }

    private char peek(int offset) {
        return input.charAt(pos + offset);
    }

    private char advance() {
        char c = input.charAt(pos++);
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return c;
    }

    private int remaining() {
        return input.length() - pos;
    }

    private String substring(int start, int end) {
        return input.substring(start, end);
    }

    private long cacheKey(int ruleId, int position) {
        return ((long) ruleId << 32) | position;
    }

    private void restoreLocation(SourceLocation loc) {
        this.pos = loc.offset();
        this.line = loc.line();
        this.column = loc.column();
    }

    // === Public Parse Methods ===

    public Result<CstNode> parse(String input) {
        init(input);
        var leadingTrivia = skipWhitespace();
        var result = parse_CompilationUnit(leadingTrivia);
        if (result.isFailure()) {
            return Result.failure(new ParseError(location(), "expected " + result.expected));
        }
        skipWhitespace(); // Consume trailing whitespace
        if (!isAtEnd()) {
            return Result.failure(new ParseError(location(), "unexpected input"));
        }
        return Result.success(result.node);
    }

    // === Rule Parsing Methods ===

    private CstParseResult parse_CompilationUnit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(0, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var optStart1 = location();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem1 = parse_PackageDecl(trivia2);
            if (optElem1.isSuccess() && optElem1.node != null) {
                children.add(optElem1.node);
            }
            var elem0_0 = optElem1.isSuccess() ? optElem1 : CstParseResult.success(null, "", location());
            if (optElem1.isFailure()) {
                restoreLocation(optStart1);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem3 = parse_ImportDecl(trivia4);
                if (zomElem3.isSuccess() && zomElem3.node != null) {
                    children.add(zomElem3.node);
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_2 = CstParseResult.success(null, "", location());
            var zomStart5 = location();
            while (true) {
                var beforeLoc5 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem5 = parse_TypeDecl(trivia6);
                if (zomElem5.isSuccess() && zomElem5.node != null) {
                    children.add(zomElem5.node);
                }
                if (zomElem5.isFailure() || location().offset() == beforeLoc5.offset()) {
                    restoreLocation(beforeLoc5);
                    break;
                }
            }
            elem0_2 = CstParseResult.success(null, substring(zomStart5.offset(), pos), location());
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "CompilationUnit", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_PackageDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(1, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("package", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_QualifiedName(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst(";", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "PackageDecl", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ImportDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(2, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("import", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart2 = location();
            var optElem2 = matchLiteralCst("static", false);
            if (optElem2.isSuccess() && optElem2.node != null) {
                children.add(optElem2.node);
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_QualifiedName(trivia4);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart5 = location();
            CstParseResult optElem5 = CstParseResult.success(null, "", location());
            var seqStart7 = location();
            if (optElem5.isSuccess()) {
                var elem7_0 = matchLiteralCst(".", false);
                if (elem7_0.isSuccess() && elem7_0.node != null) {
                    children.add(elem7_0.node);
                }
                if (elem7_0.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_0;
                }
            }
            if (optElem5.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem7_1 = matchLiteralCst("*", false);
                if (elem7_1.isSuccess() && elem7_1.node != null) {
                    children.add(elem7_1.node);
                }
                if (elem7_1.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_1;
                }
            }
            if (optElem5.isSuccess()) {
                optElem5 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
            }
            var elem0_3 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_4 = matchLiteralCst(";", false);
            if (elem0_4.isSuccess() && elem0_4.node != null) {
                children.add(elem0_4.node);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "ImportDecl", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(3, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem3 = parse_Modifier(trivia4);
                if (zomElem3.isSuccess() && zomElem3.node != null) {
                    children.add(zomElem3.node);
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_TypeKind(trivia5);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "TypeDecl", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeKind(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(4, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_ClassDecl(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_InterfaceDecl(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_2 = parse_EnumDecl(trivia3);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_3 = parse_RecordDecl(trivia4);
        if (alt0_3.isSuccess() && alt0_3.node != null) {
            children.add(alt0_3.node);
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "TypeKind", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ClassDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(5, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("class", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart3 = location();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem3 = parse_TypeParams(trivia4);
            if (optElem3.isSuccess() && optElem3.node != null) {
                children.add(optElem3.node);
            }
            var elem0_2 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart5 = location();
            CstParseResult optElem5 = CstParseResult.success(null, "", location());
            var seqStart7 = location();
            if (optElem5.isSuccess()) {
                var elem7_0 = matchLiteralCst("extends", false);
                if (elem7_0.isSuccess() && elem7_0.node != null) {
                    children.add(elem7_0.node);
                }
                if (elem7_0.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_0;
                }
            }
            if (optElem5.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem7_1 = parse_Type(trivia9);
                if (elem7_1.isSuccess() && elem7_1.node != null) {
                    children.add(elem7_1.node);
                }
                if (elem7_1.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_1;
                }
            }
            if (optElem5.isSuccess()) {
                optElem5 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
            }
            var elem0_3 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart10 = location();
            var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem10 = parse_ImplementsClause(trivia11);
            if (optElem10.isSuccess() && optElem10.node != null) {
                children.add(optElem10.node);
            }
            var elem0_4 = optElem10.isSuccess() ? optElem10 : CstParseResult.success(null, "", location());
            if (optElem10.isFailure()) {
                restoreLocation(optStart10);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart12 = location();
            var trivia13 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem12 = parse_PermitsClause(trivia13);
            if (optElem12.isSuccess() && optElem12.node != null) {
                children.add(optElem12.node);
            }
            var elem0_5 = optElem12.isSuccess() ? optElem12 : CstParseResult.success(null, "", location());
            if (optElem12.isFailure()) {
                restoreLocation(optStart12);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia14 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_6 = parse_ClassBody(trivia14);
            if (elem0_6.isSuccess() && elem0_6.node != null) {
                children.add(elem0_6.node);
            }
            if (elem0_6.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_6;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "ClassDecl", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_InterfaceDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(6, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("interface", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart3 = location();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem3 = parse_TypeParams(trivia4);
            if (optElem3.isSuccess() && optElem3.node != null) {
                children.add(optElem3.node);
            }
            var elem0_2 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart5 = location();
            CstParseResult optElem5 = CstParseResult.success(null, "", location());
            var seqStart7 = location();
            if (optElem5.isSuccess()) {
                var elem7_0 = matchLiteralCst("extends", false);
                if (elem7_0.isSuccess() && elem7_0.node != null) {
                    children.add(elem7_0.node);
                }
                if (elem7_0.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_0;
                }
            }
            if (optElem5.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem7_1 = parse_TypeList(trivia9);
                if (elem7_1.isSuccess() && elem7_1.node != null) {
                    children.add(elem7_1.node);
                }
                if (elem7_1.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_1;
                }
            }
            if (optElem5.isSuccess()) {
                optElem5 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
            }
            var elem0_3 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_4 = parse_ClassBody(trivia10);
            if (elem0_4.isSuccess() && elem0_4.node != null) {
                children.add(elem0_4.node);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "InterfaceDecl", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_EnumDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(7, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("enum", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart3 = location();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem3 = parse_ImplementsClause(trivia4);
            if (optElem3.isSuccess() && optElem3.node != null) {
                children.add(optElem3.node);
            }
            var elem0_2 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_3 = parse_EnumBody(trivia5);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "EnumDecl", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RecordDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(8, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("record", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("(", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart4 = location();
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem4 = parse_RecordComponents(trivia5);
            if (optElem4.isSuccess() && optElem4.node != null) {
                children.add(optElem4.node);
            }
            var elem0_3 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_4 = matchLiteralCst(")", false);
            if (elem0_4.isSuccess() && elem0_4.node != null) {
                children.add(elem0_4.node);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart7 = location();
            var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem7 = parse_ImplementsClause(trivia8);
            if (optElem7.isSuccess() && optElem7.node != null) {
                children.add(optElem7.node);
            }
            var elem0_5 = optElem7.isSuccess() ? optElem7 : CstParseResult.success(null, "", location());
            if (optElem7.isFailure()) {
                restoreLocation(optStart7);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_6 = parse_RecordBody(trivia9);
            if (elem0_6.isSuccess() && elem0_6.node != null) {
                children.add(elem0_6.node);
            }
            if (elem0_6.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_6;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "RecordDecl", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ImplementsClause(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(9, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("implements", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_TypeList(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "ImplementsClause", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_PermitsClause(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(10, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("permits", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_TypeList(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "PermitsClause", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeList(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(11, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Type(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Type(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "TypeList", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeParams(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(12, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("<", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_TypeParam(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_2 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem3 = CstParseResult.success(null, "", location());
                var seqStart5 = location();
                if (zomElem3.isSuccess()) {
                    var elem5_0 = matchLiteralCst(",", false);
                    if (elem5_0.isSuccess() && elem5_0.node != null) {
                        children.add(elem5_0.node);
                    }
                    if (elem5_0.isFailure()) {
                        restoreLocation(seqStart5);
                        zomElem3 = elem5_0;
                    }
                }
                if (zomElem3.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem5_1 = parse_TypeParam(trivia7);
                    if (elem5_1.isSuccess() && elem5_1.node != null) {
                        children.add(elem5_1.node);
                    }
                    if (elem5_1.isFailure()) {
                        restoreLocation(seqStart5);
                        zomElem3 = elem5_1;
                    }
                }
                if (zomElem3.isSuccess()) {
                    zomElem3 = CstParseResult.success(null, substring(seqStart5.offset(), pos), location());
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_2 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst(">", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "TypeParams", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeParam(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(13, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Identifier(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart2 = location();
            CstParseResult optElem2 = CstParseResult.success(null, "", location());
            var seqStart4 = location();
            if (optElem2.isSuccess()) {
                var elem4_0 = matchLiteralCst("extends", false);
                if (elem4_0.isSuccess() && elem4_0.node != null) {
                    children.add(elem4_0.node);
                }
                if (elem4_0.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_0;
                }
            }
            if (optElem2.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem4_1 = parse_Type(trivia6);
                if (elem4_1.isSuccess() && elem4_1.node != null) {
                    children.add(elem4_1.node);
                }
                if (elem4_1.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_1;
                }
            }
            if (optElem2.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult elem4_2 = CstParseResult.success(null, "", location());
                var zomStart7 = location();
                while (true) {
                    var beforeLoc7 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    CstParseResult zomElem7 = CstParseResult.success(null, "", location());
                    var seqStart9 = location();
                    if (zomElem7.isSuccess()) {
                        var elem9_0 = matchLiteralCst("&", false);
                        if (elem9_0.isSuccess() && elem9_0.node != null) {
                            children.add(elem9_0.node);
                        }
                        if (elem9_0.isFailure()) {
                            restoreLocation(seqStart9);
                            zomElem7 = elem9_0;
                        }
                    }
                    if (zomElem7.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                        var elem9_1 = parse_Type(trivia11);
                        if (elem9_1.isSuccess() && elem9_1.node != null) {
                            children.add(elem9_1.node);
                        }
                        if (elem9_1.isFailure()) {
                            restoreLocation(seqStart9);
                            zomElem7 = elem9_1;
                        }
                    }
                    if (zomElem7.isSuccess()) {
                        zomElem7 = CstParseResult.success(null, substring(seqStart9.offset(), pos), location());
                    }
                    if (zomElem7.isFailure() || location().offset() == beforeLoc7.offset()) {
                        restoreLocation(beforeLoc7);
                        break;
                    }
                }
                elem4_2 = CstParseResult.success(null, substring(zomStart7.offset(), pos), location());
                if (elem4_2.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_2;
                }
            }
            if (optElem2.isSuccess()) {
                optElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "TypeParam", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ClassBody(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(14, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("{", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_ClassMember(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("}", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "ClassBody", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ClassMember(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(15, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            CstParseResult elem1_0 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_Annotation(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem1_0 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem1_1 = CstParseResult.success(null, "", location());
            var zomStart4 = location();
            while (true) {
                var beforeLoc4 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem4 = parse_Modifier(trivia5);
                if (zomElem4.isSuccess() && zomElem4.node != null) {
                    children.add(zomElem4.node);
                }
                if (zomElem4.isFailure() || location().offset() == beforeLoc4.offset()) {
                    restoreLocation(beforeLoc4);
                    break;
                }
            }
            elem1_1 = CstParseResult.success(null, substring(zomStart4.offset(), pos), location());
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_2 = parse_Member(trivia6);
            if (elem1_2.isSuccess() && elem1_2.node != null) {
                children.add(elem1_2.node);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_StaticBlock(trivia7);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_2 = matchLiteralCst(";", false);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "ClassMember", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Member(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(16, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_ConstructorDecl(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_MethodDecl(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_2 = parse_FieldDecl(trivia3);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_3 = parse_TypeKind(trivia4);
        if (alt0_3.isSuccess() && alt0_3.node != null) {
            children.add(alt0_3.node);
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Member", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_StaticBlock(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(17, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("static", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Block(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "StaticBlock", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_EnumBody(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(18, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("{", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart2 = location();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem2 = parse_EnumConsts(trivia3);
            if (optElem2.isSuccess() && optElem2.node != null) {
                children.add(optElem2.node);
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart4 = location();
            CstParseResult optElem4 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (optElem4.isSuccess()) {
                var elem6_0 = matchLiteralCst(";", false);
                if (elem6_0.isSuccess() && elem6_0.node != null) {
                    children.add(elem6_0.node);
                }
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_0;
                }
            }
            if (optElem4.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult elem6_1 = CstParseResult.success(null, "", location());
                var zomStart8 = location();
                while (true) {
                    var beforeLoc8 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var zomElem8 = parse_ClassMember(trivia9);
                    if (zomElem8.isSuccess() && zomElem8.node != null) {
                        children.add(zomElem8.node);
                    }
                    if (zomElem8.isFailure() || location().offset() == beforeLoc8.offset()) {
                        restoreLocation(beforeLoc8);
                        break;
                    }
                }
                elem6_1 = CstParseResult.success(null, substring(zomStart8.offset(), pos), location());
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_1;
                }
            }
            if (optElem4.isSuccess()) {
                optElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            var elem0_2 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst("}", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "EnumBody", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_EnumConsts(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(19, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_EnumConst(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_EnumConst(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart7 = location();
            var optElem7 = matchLiteralCst(",", false);
            if (optElem7.isSuccess() && optElem7.node != null) {
                children.add(optElem7.node);
            }
            var elem0_2 = optElem7.isSuccess() ? optElem7 : CstParseResult.success(null, "", location());
            if (optElem7.isFailure()) {
                restoreLocation(optStart7);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "EnumConsts", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_EnumConst(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(20, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Identifier(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart2 = location();
            CstParseResult optElem2 = CstParseResult.success(null, "", location());
            var seqStart4 = location();
            if (optElem2.isSuccess()) {
                var elem4_0 = matchLiteralCst("(", false);
                if (elem4_0.isSuccess() && elem4_0.node != null) {
                    children.add(elem4_0.node);
                }
                if (elem4_0.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_0;
                }
            }
            if (optElem2.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var optStart6 = location();
                var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem6 = parse_Args(trivia7);
                if (optElem6.isSuccess() && optElem6.node != null) {
                    children.add(optElem6.node);
                }
                var elem4_1 = optElem6.isSuccess() ? optElem6 : CstParseResult.success(null, "", location());
                if (optElem6.isFailure()) {
                    restoreLocation(optStart6);
                }
                if (elem4_1.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_1;
                }
            }
            if (optElem2.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem4_2 = matchLiteralCst(")", false);
                if (elem4_2.isSuccess() && elem4_2.node != null) {
                    children.add(elem4_2.node);
                }
                if (elem4_2.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_2;
                }
            }
            if (optElem2.isSuccess()) {
                optElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart9 = location();
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem9 = parse_ClassBody(trivia10);
            if (optElem9.isSuccess() && optElem9.node != null) {
                children.add(optElem9.node);
            }
            var elem0_2 = optElem9.isSuccess() ? optElem9 : CstParseResult.success(null, "", location());
            if (optElem9.isFailure()) {
                restoreLocation(optStart9);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "EnumConst", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RecordComponents(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(21, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_RecordComp(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_RecordComp(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "RecordComponents", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RecordComp(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(22, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Type(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_Identifier(trivia4);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "RecordComp", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RecordBody(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(23, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("{", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_ClassMember(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("}", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "RecordBody", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_FieldDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(24, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Type(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_VarDecls(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst(";", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "FieldDecl", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_VarDecls(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(25, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_VarDecl(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_VarDecl(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "VarDecls", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_VarDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(26, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Identifier(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart2 = location();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem2 = parse_Dims(trivia3);
            if (optElem2.isSuccess() && optElem2.node != null) {
                children.add(optElem2.node);
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart4 = location();
            CstParseResult optElem4 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (optElem4.isSuccess()) {
                var elem6_0 = matchLiteralCst("=", false);
                if (elem6_0.isSuccess() && elem6_0.node != null) {
                    children.add(elem6_0.node);
                }
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_0;
                }
            }
            if (optElem4.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem6_1 = parse_VarInit(trivia8);
                if (elem6_1.isSuccess() && elem6_1.node != null) {
                    children.add(elem6_1.node);
                }
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_1;
                }
            }
            if (optElem4.isSuccess()) {
                optElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            var elem0_2 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "VarDecl", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_VarInit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(27, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var elem1_0 = matchLiteralCst("{", false);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart3 = location();
            CstParseResult optElem3 = CstParseResult.success(null, "", location());
            var seqStart5 = location();
            if (optElem3.isSuccess()) {
                var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem5_0 = parse_VarInit(trivia6);
                if (elem5_0.isSuccess() && elem5_0.node != null) {
                    children.add(elem5_0.node);
                }
                if (elem5_0.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_0;
                }
            }
            if (optElem3.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult elem5_1 = CstParseResult.success(null, "", location());
                var zomStart7 = location();
                while (true) {
                    var beforeLoc7 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    CstParseResult zomElem7 = CstParseResult.success(null, "", location());
                    var seqStart9 = location();
                    if (zomElem7.isSuccess()) {
                        var elem9_0 = matchLiteralCst(",", false);
                        if (elem9_0.isSuccess() && elem9_0.node != null) {
                            children.add(elem9_0.node);
                        }
                        if (elem9_0.isFailure()) {
                            restoreLocation(seqStart9);
                            zomElem7 = elem9_0;
                        }
                    }
                    if (zomElem7.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                        var elem9_1 = parse_VarInit(trivia11);
                        if (elem9_1.isSuccess() && elem9_1.node != null) {
                            children.add(elem9_1.node);
                        }
                        if (elem9_1.isFailure()) {
                            restoreLocation(seqStart9);
                            zomElem7 = elem9_1;
                        }
                    }
                    if (zomElem7.isSuccess()) {
                        zomElem7 = CstParseResult.success(null, substring(seqStart9.offset(), pos), location());
                    }
                    if (zomElem7.isFailure() || location().offset() == beforeLoc7.offset()) {
                        restoreLocation(beforeLoc7);
                        break;
                    }
                }
                elem5_1 = CstParseResult.success(null, substring(zomStart7.offset(), pos), location());
                if (elem5_1.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_1;
                }
            }
            if (optElem3.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var optStart12 = location();
                var optElem12 = matchLiteralCst(",", false);
                if (optElem12.isSuccess() && optElem12.node != null) {
                    children.add(optElem12.node);
                }
                var elem5_2 = optElem12.isSuccess() ? optElem12 : CstParseResult.success(null, "", location());
                if (optElem12.isFailure()) {
                    restoreLocation(optStart12);
                }
                if (elem5_2.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_2;
                }
            }
            if (optElem3.isSuccess()) {
                optElem3 = CstParseResult.success(null, substring(seqStart5.offset(), pos), location());
            }
            var elem1_1 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_2 = matchLiteralCst("}", false);
            if (elem1_2.isSuccess() && elem1_2.node != null) {
                children.add(elem1_2.node);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia15 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_Expr(trivia15);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "VarInit", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_MethodDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(28, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var optStart1 = location();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem1 = parse_TypeParams(trivia2);
            if (optElem1.isSuccess() && optElem1.node != null) {
                children.add(optElem1.node);
            }
            var elem0_0 = optElem1.isSuccess() ? optElem1 : CstParseResult.success(null, "", location());
            if (optElem1.isFailure()) {
                restoreLocation(optStart1);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Type(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_Identifier(trivia4);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst("(", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart6 = location();
            var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem6 = parse_Params(trivia7);
            if (optElem6.isSuccess() && optElem6.node != null) {
                children.add(optElem6.node);
            }
            var elem0_4 = optElem6.isSuccess() ? optElem6 : CstParseResult.success(null, "", location());
            if (optElem6.isFailure()) {
                restoreLocation(optStart6);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_5 = matchLiteralCst(")", false);
            if (elem0_5.isSuccess() && elem0_5.node != null) {
                children.add(elem0_5.node);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart9 = location();
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem9 = parse_Dims(trivia10);
            if (optElem9.isSuccess() && optElem9.node != null) {
                children.add(optElem9.node);
            }
            var elem0_6 = optElem9.isSuccess() ? optElem9 : CstParseResult.success(null, "", location());
            if (optElem9.isFailure()) {
                restoreLocation(optStart9);
            }
            if (elem0_6.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_6;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart11 = location();
            var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem11 = parse_Throws(trivia12);
            if (optElem11.isSuccess() && optElem11.node != null) {
                children.add(optElem11.node);
            }
            var elem0_7 = optElem11.isSuccess() ? optElem11 : CstParseResult.success(null, "", location());
            if (optElem11.isFailure()) {
                restoreLocation(optStart11);
            }
            if (elem0_7.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_7;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_8 = null;
            var choiceStart14 = location();
            var savedChildren14 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren14);
            var trivia15 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt14_0 = parse_Block(trivia15);
            if (alt14_0.isSuccess() && alt14_0.node != null) {
                children.add(alt14_0.node);
            }
            if (alt14_0.isSuccess()) {
                elem0_8 = alt14_0;
            } else {
                restoreLocation(choiceStart14);
            children.clear();
            children.addAll(savedChildren14);
            var alt14_1 = matchLiteralCst(";", false);
            if (alt14_1.isSuccess() && alt14_1.node != null) {
                children.add(alt14_1.node);
            }
            if (alt14_1.isSuccess()) {
                elem0_8 = alt14_1;
            } else {
                restoreLocation(choiceStart14);
            }
            }
            if (elem0_8 == null) {
                children.clear();
                children.addAll(savedChildren14);
                elem0_8 = CstParseResult.failure("one of alternatives");
            }
            if (elem0_8.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_8;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "MethodDecl", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Params(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(29, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Param(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Param(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Params", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Param(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(30, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem3 = parse_Modifier(trivia4);
                if (zomElem3.isSuccess() && zomElem3.node != null) {
                    children.add(zomElem3.node);
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_Type(trivia5);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart6 = location();
            var optElem6 = matchLiteralCst("...", false);
            if (optElem6.isSuccess() && optElem6.node != null) {
                children.add(optElem6.node);
            }
            var elem0_3 = optElem6.isSuccess() ? optElem6 : CstParseResult.success(null, "", location());
            if (optElem6.isFailure()) {
                restoreLocation(optStart6);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_4 = parse_Identifier(trivia8);
            if (elem0_4.isSuccess() && elem0_4.node != null) {
                children.add(elem0_4.node);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart9 = location();
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem9 = parse_Dims(trivia10);
            if (optElem9.isSuccess() && optElem9.node != null) {
                children.add(optElem9.node);
            }
            var elem0_5 = optElem9.isSuccess() ? optElem9 : CstParseResult.success(null, "", location());
            if (optElem9.isFailure()) {
                restoreLocation(optStart9);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Param", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Throws(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(31, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("throws", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_TypeList(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Throws", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ConstructorDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(32, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var optStart1 = location();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem1 = parse_TypeParams(trivia2);
            if (optElem1.isSuccess() && optElem1.node != null) {
                children.add(optElem1.node);
            }
            var elem0_0 = optElem1.isSuccess() ? optElem1 : CstParseResult.success(null, "", location());
            if (optElem1.isFailure()) {
                restoreLocation(optStart1);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("(", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart5 = location();
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem5 = parse_Params(trivia6);
            if (optElem5.isSuccess() && optElem5.node != null) {
                children.add(optElem5.node);
            }
            var elem0_3 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_4 = matchLiteralCst(")", false);
            if (elem0_4.isSuccess() && elem0_4.node != null) {
                children.add(elem0_4.node);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart8 = location();
            var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem8 = parse_Throws(trivia9);
            if (optElem8.isSuccess() && optElem8.node != null) {
                children.add(optElem8.node);
            }
            var elem0_5 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
            if (optElem8.isFailure()) {
                restoreLocation(optStart8);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_6 = parse_Block(trivia10);
            if (elem0_6.isSuccess() && elem0_6.node != null) {
                children.add(elem0_6.node);
            }
            if (elem0_6.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_6;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "ConstructorDecl", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Block(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(33, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("{", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_BlockStmt(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("}", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Block", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_BlockStmt(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(34, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_LocalVar(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_TypeKind(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_2 = parse_Stmt(trivia3);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "BlockStmt", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LocalVar(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(35, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Modifier(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Type(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_VarDecls(trivia4);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst(";", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "LocalVar", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Stmt(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(36, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_Block(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (alt0_1.isSuccess()) {
            var elem2_0 = matchLiteralCst("if", false);
            if (elem2_0.isSuccess() && elem2_0.node != null) {
                children.add(elem2_0.node);
            }
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_0;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_1 = matchLiteralCst("(", false);
            if (elem2_1.isSuccess() && elem2_1.node != null) {
                children.add(elem2_1.node);
            }
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_1;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem2_2 = parse_Expr(trivia5);
            if (elem2_2.isSuccess() && elem2_2.node != null) {
                children.add(elem2_2.node);
            }
            if (elem2_2.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_2;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_3 = matchLiteralCst(")", false);
            if (elem2_3.isSuccess() && elem2_3.node != null) {
                children.add(elem2_3.node);
            }
            if (elem2_3.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_3;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem2_4 = parse_Stmt(trivia7);
            if (elem2_4.isSuccess() && elem2_4.node != null) {
                children.add(elem2_4.node);
            }
            if (elem2_4.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_4;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart8 = location();
            CstParseResult optElem8 = CstParseResult.success(null, "", location());
            var seqStart10 = location();
            if (optElem8.isSuccess()) {
                var elem10_0 = matchLiteralCst("else", false);
                if (elem10_0.isSuccess() && elem10_0.node != null) {
                    children.add(elem10_0.node);
                }
                if (elem10_0.isFailure()) {
                    restoreLocation(seqStart10);
                    optElem8 = elem10_0;
                }
            }
            if (optElem8.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem10_1 = parse_Stmt(trivia12);
                if (elem10_1.isSuccess() && elem10_1.node != null) {
                    children.add(elem10_1.node);
                }
                if (elem10_1.isFailure()) {
                    restoreLocation(seqStart10);
                    optElem8 = elem10_1;
                }
            }
            if (optElem8.isSuccess()) {
                optElem8 = CstParseResult.success(null, substring(seqStart10.offset(), pos), location());
            }
            var elem2_5 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
            if (optElem8.isFailure()) {
                restoreLocation(optStart8);
            }
            if (elem2_5.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_5;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_2 = CstParseResult.success(null, "", location());
        var seqStart13 = location();
        if (alt0_2.isSuccess()) {
            var elem13_0 = matchLiteralCst("while", false);
            if (elem13_0.isSuccess() && elem13_0.node != null) {
                children.add(elem13_0.node);
            }
            if (elem13_0.isFailure()) {
                restoreLocation(seqStart13);
                alt0_2 = elem13_0;
            }
        }
        if (alt0_2.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem13_1 = matchLiteralCst("(", false);
            if (elem13_1.isSuccess() && elem13_1.node != null) {
                children.add(elem13_1.node);
            }
            if (elem13_1.isFailure()) {
                restoreLocation(seqStart13);
                alt0_2 = elem13_1;
            }
        }
        if (alt0_2.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia16 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem13_2 = parse_Expr(trivia16);
            if (elem13_2.isSuccess() && elem13_2.node != null) {
                children.add(elem13_2.node);
            }
            if (elem13_2.isFailure()) {
                restoreLocation(seqStart13);
                alt0_2 = elem13_2;
            }
        }
        if (alt0_2.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem13_3 = matchLiteralCst(")", false);
            if (elem13_3.isSuccess() && elem13_3.node != null) {
                children.add(elem13_3.node);
            }
            if (elem13_3.isFailure()) {
                restoreLocation(seqStart13);
                alt0_2 = elem13_3;
            }
        }
        if (alt0_2.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia18 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem13_4 = parse_Stmt(trivia18);
            if (elem13_4.isSuccess() && elem13_4.node != null) {
                children.add(elem13_4.node);
            }
            if (elem13_4.isFailure()) {
                restoreLocation(seqStart13);
                alt0_2 = elem13_4;
            }
        }
        if (alt0_2.isSuccess()) {
            alt0_2 = CstParseResult.success(null, substring(seqStart13.offset(), pos), location());
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_3 = CstParseResult.success(null, "", location());
        var seqStart19 = location();
        if (alt0_3.isSuccess()) {
            var elem19_0 = matchLiteralCst("for", false);
            if (elem19_0.isSuccess() && elem19_0.node != null) {
                children.add(elem19_0.node);
            }
            if (elem19_0.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_0;
            }
        }
        if (alt0_3.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem19_1 = matchLiteralCst("(", false);
            if (elem19_1.isSuccess() && elem19_1.node != null) {
                children.add(elem19_1.node);
            }
            if (elem19_1.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_1;
            }
        }
        if (alt0_3.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia22 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem19_2 = parse_ForCtrl(trivia22);
            if (elem19_2.isSuccess() && elem19_2.node != null) {
                children.add(elem19_2.node);
            }
            if (elem19_2.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_2;
            }
        }
        if (alt0_3.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem19_3 = matchLiteralCst(")", false);
            if (elem19_3.isSuccess() && elem19_3.node != null) {
                children.add(elem19_3.node);
            }
            if (elem19_3.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_3;
            }
        }
        if (alt0_3.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia24 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem19_4 = parse_Stmt(trivia24);
            if (elem19_4.isSuccess() && elem19_4.node != null) {
                children.add(elem19_4.node);
            }
            if (elem19_4.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_4;
            }
        }
        if (alt0_3.isSuccess()) {
            alt0_3 = CstParseResult.success(null, substring(seqStart19.offset(), pos), location());
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_4 = CstParseResult.success(null, "", location());
        var seqStart25 = location();
        if (alt0_4.isSuccess()) {
            var elem25_0 = matchLiteralCst("do", false);
            if (elem25_0.isSuccess() && elem25_0.node != null) {
                children.add(elem25_0.node);
            }
            if (elem25_0.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_0;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia27 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem25_1 = parse_Stmt(trivia27);
            if (elem25_1.isSuccess() && elem25_1.node != null) {
                children.add(elem25_1.node);
            }
            if (elem25_1.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_1;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem25_2 = matchLiteralCst("while", false);
            if (elem25_2.isSuccess() && elem25_2.node != null) {
                children.add(elem25_2.node);
            }
            if (elem25_2.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_2;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem25_3 = matchLiteralCst("(", false);
            if (elem25_3.isSuccess() && elem25_3.node != null) {
                children.add(elem25_3.node);
            }
            if (elem25_3.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_3;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia30 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem25_4 = parse_Expr(trivia30);
            if (elem25_4.isSuccess() && elem25_4.node != null) {
                children.add(elem25_4.node);
            }
            if (elem25_4.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_4;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem25_5 = matchLiteralCst(")", false);
            if (elem25_5.isSuccess() && elem25_5.node != null) {
                children.add(elem25_5.node);
            }
            if (elem25_5.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_5;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem25_6 = matchLiteralCst(";", false);
            if (elem25_6.isSuccess() && elem25_6.node != null) {
                children.add(elem25_6.node);
            }
            if (elem25_6.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_6;
            }
        }
        if (alt0_4.isSuccess()) {
            alt0_4 = CstParseResult.success(null, substring(seqStart25.offset(), pos), location());
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_5 = CstParseResult.success(null, "", location());
        var seqStart33 = location();
        if (alt0_5.isSuccess()) {
            var elem33_0 = matchLiteralCst("try", false);
            if (elem33_0.isSuccess() && elem33_0.node != null) {
                children.add(elem33_0.node);
            }
            if (elem33_0.isFailure()) {
                restoreLocation(seqStart33);
                alt0_5 = elem33_0;
            }
        }
        if (alt0_5.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart35 = location();
            var trivia36 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem35 = parse_ResourceSpec(trivia36);
            if (optElem35.isSuccess() && optElem35.node != null) {
                children.add(optElem35.node);
            }
            var elem33_1 = optElem35.isSuccess() ? optElem35 : CstParseResult.success(null, "", location());
            if (optElem35.isFailure()) {
                restoreLocation(optStart35);
            }
            if (elem33_1.isFailure()) {
                restoreLocation(seqStart33);
                alt0_5 = elem33_1;
            }
        }
        if (alt0_5.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia37 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem33_2 = parse_Block(trivia37);
            if (elem33_2.isSuccess() && elem33_2.node != null) {
                children.add(elem33_2.node);
            }
            if (elem33_2.isFailure()) {
                restoreLocation(seqStart33);
                alt0_5 = elem33_2;
            }
        }
        if (alt0_5.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem33_3 = CstParseResult.success(null, "", location());
            var zomStart38 = location();
            while (true) {
                var beforeLoc38 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia39 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem38 = parse_Catch(trivia39);
                if (zomElem38.isSuccess() && zomElem38.node != null) {
                    children.add(zomElem38.node);
                }
                if (zomElem38.isFailure() || location().offset() == beforeLoc38.offset()) {
                    restoreLocation(beforeLoc38);
                    break;
                }
            }
            elem33_3 = CstParseResult.success(null, substring(zomStart38.offset(), pos), location());
            if (elem33_3.isFailure()) {
                restoreLocation(seqStart33);
                alt0_5 = elem33_3;
            }
        }
        if (alt0_5.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart40 = location();
            var trivia41 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem40 = parse_Finally(trivia41);
            if (optElem40.isSuccess() && optElem40.node != null) {
                children.add(optElem40.node);
            }
            var elem33_4 = optElem40.isSuccess() ? optElem40 : CstParseResult.success(null, "", location());
            if (optElem40.isFailure()) {
                restoreLocation(optStart40);
            }
            if (elem33_4.isFailure()) {
                restoreLocation(seqStart33);
                alt0_5 = elem33_4;
            }
        }
        if (alt0_5.isSuccess()) {
            alt0_5 = CstParseResult.success(null, substring(seqStart33.offset(), pos), location());
        }
        if (alt0_5.isSuccess()) {
            result = alt0_5;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_6 = CstParseResult.success(null, "", location());
        var seqStart42 = location();
        if (alt0_6.isSuccess()) {
            var elem42_0 = matchLiteralCst("switch", false);
            if (elem42_0.isSuccess() && elem42_0.node != null) {
                children.add(elem42_0.node);
            }
            if (elem42_0.isFailure()) {
                restoreLocation(seqStart42);
                alt0_6 = elem42_0;
            }
        }
        if (alt0_6.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem42_1 = matchLiteralCst("(", false);
            if (elem42_1.isSuccess() && elem42_1.node != null) {
                children.add(elem42_1.node);
            }
            if (elem42_1.isFailure()) {
                restoreLocation(seqStart42);
                alt0_6 = elem42_1;
            }
        }
        if (alt0_6.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia45 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem42_2 = parse_Expr(trivia45);
            if (elem42_2.isSuccess() && elem42_2.node != null) {
                children.add(elem42_2.node);
            }
            if (elem42_2.isFailure()) {
                restoreLocation(seqStart42);
                alt0_6 = elem42_2;
            }
        }
        if (alt0_6.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem42_3 = matchLiteralCst(")", false);
            if (elem42_3.isSuccess() && elem42_3.node != null) {
                children.add(elem42_3.node);
            }
            if (elem42_3.isFailure()) {
                restoreLocation(seqStart42);
                alt0_6 = elem42_3;
            }
        }
        if (alt0_6.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia47 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem42_4 = parse_SwitchBlock(trivia47);
            if (elem42_4.isSuccess() && elem42_4.node != null) {
                children.add(elem42_4.node);
            }
            if (elem42_4.isFailure()) {
                restoreLocation(seqStart42);
                alt0_6 = elem42_4;
            }
        }
        if (alt0_6.isSuccess()) {
            alt0_6 = CstParseResult.success(null, substring(seqStart42.offset(), pos), location());
        }
        if (alt0_6.isSuccess()) {
            result = alt0_6;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_7 = CstParseResult.success(null, "", location());
        var seqStart48 = location();
        if (alt0_7.isSuccess()) {
            var elem48_0 = matchLiteralCst("return", false);
            if (elem48_0.isSuccess() && elem48_0.node != null) {
                children.add(elem48_0.node);
            }
            if (elem48_0.isFailure()) {
                restoreLocation(seqStart48);
                alt0_7 = elem48_0;
            }
        }
        if (alt0_7.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart50 = location();
            var trivia51 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem50 = parse_Expr(trivia51);
            if (optElem50.isSuccess() && optElem50.node != null) {
                children.add(optElem50.node);
            }
            var elem48_1 = optElem50.isSuccess() ? optElem50 : CstParseResult.success(null, "", location());
            if (optElem50.isFailure()) {
                restoreLocation(optStart50);
            }
            if (elem48_1.isFailure()) {
                restoreLocation(seqStart48);
                alt0_7 = elem48_1;
            }
        }
        if (alt0_7.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem48_2 = matchLiteralCst(";", false);
            if (elem48_2.isSuccess() && elem48_2.node != null) {
                children.add(elem48_2.node);
            }
            if (elem48_2.isFailure()) {
                restoreLocation(seqStart48);
                alt0_7 = elem48_2;
            }
        }
        if (alt0_7.isSuccess()) {
            alt0_7 = CstParseResult.success(null, substring(seqStart48.offset(), pos), location());
        }
        if (alt0_7.isSuccess()) {
            result = alt0_7;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_8 = CstParseResult.success(null, "", location());
        var seqStart53 = location();
        if (alt0_8.isSuccess()) {
            var elem53_0 = matchLiteralCst("throw", false);
            if (elem53_0.isSuccess() && elem53_0.node != null) {
                children.add(elem53_0.node);
            }
            if (elem53_0.isFailure()) {
                restoreLocation(seqStart53);
                alt0_8 = elem53_0;
            }
        }
        if (alt0_8.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia55 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem53_1 = parse_Expr(trivia55);
            if (elem53_1.isSuccess() && elem53_1.node != null) {
                children.add(elem53_1.node);
            }
            if (elem53_1.isFailure()) {
                restoreLocation(seqStart53);
                alt0_8 = elem53_1;
            }
        }
        if (alt0_8.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem53_2 = matchLiteralCst(";", false);
            if (elem53_2.isSuccess() && elem53_2.node != null) {
                children.add(elem53_2.node);
            }
            if (elem53_2.isFailure()) {
                restoreLocation(seqStart53);
                alt0_8 = elem53_2;
            }
        }
        if (alt0_8.isSuccess()) {
            alt0_8 = CstParseResult.success(null, substring(seqStart53.offset(), pos), location());
        }
        if (alt0_8.isSuccess()) {
            result = alt0_8;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_9 = CstParseResult.success(null, "", location());
        var seqStart57 = location();
        if (alt0_9.isSuccess()) {
            var elem57_0 = matchLiteralCst("break", false);
            if (elem57_0.isSuccess() && elem57_0.node != null) {
                children.add(elem57_0.node);
            }
            if (elem57_0.isFailure()) {
                restoreLocation(seqStart57);
                alt0_9 = elem57_0;
            }
        }
        if (alt0_9.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart59 = location();
            var trivia60 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem59 = parse_Identifier(trivia60);
            if (optElem59.isSuccess() && optElem59.node != null) {
                children.add(optElem59.node);
            }
            var elem57_1 = optElem59.isSuccess() ? optElem59 : CstParseResult.success(null, "", location());
            if (optElem59.isFailure()) {
                restoreLocation(optStart59);
            }
            if (elem57_1.isFailure()) {
                restoreLocation(seqStart57);
                alt0_9 = elem57_1;
            }
        }
        if (alt0_9.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem57_2 = matchLiteralCst(";", false);
            if (elem57_2.isSuccess() && elem57_2.node != null) {
                children.add(elem57_2.node);
            }
            if (elem57_2.isFailure()) {
                restoreLocation(seqStart57);
                alt0_9 = elem57_2;
            }
        }
        if (alt0_9.isSuccess()) {
            alt0_9 = CstParseResult.success(null, substring(seqStart57.offset(), pos), location());
        }
        if (alt0_9.isSuccess()) {
            result = alt0_9;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_10 = CstParseResult.success(null, "", location());
        var seqStart62 = location();
        if (alt0_10.isSuccess()) {
            var elem62_0 = matchLiteralCst("continue", false);
            if (elem62_0.isSuccess() && elem62_0.node != null) {
                children.add(elem62_0.node);
            }
            if (elem62_0.isFailure()) {
                restoreLocation(seqStart62);
                alt0_10 = elem62_0;
            }
        }
        if (alt0_10.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart64 = location();
            var trivia65 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem64 = parse_Identifier(trivia65);
            if (optElem64.isSuccess() && optElem64.node != null) {
                children.add(optElem64.node);
            }
            var elem62_1 = optElem64.isSuccess() ? optElem64 : CstParseResult.success(null, "", location());
            if (optElem64.isFailure()) {
                restoreLocation(optStart64);
            }
            if (elem62_1.isFailure()) {
                restoreLocation(seqStart62);
                alt0_10 = elem62_1;
            }
        }
        if (alt0_10.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem62_2 = matchLiteralCst(";", false);
            if (elem62_2.isSuccess() && elem62_2.node != null) {
                children.add(elem62_2.node);
            }
            if (elem62_2.isFailure()) {
                restoreLocation(seqStart62);
                alt0_10 = elem62_2;
            }
        }
        if (alt0_10.isSuccess()) {
            alt0_10 = CstParseResult.success(null, substring(seqStart62.offset(), pos), location());
        }
        if (alt0_10.isSuccess()) {
            result = alt0_10;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_11 = CstParseResult.success(null, "", location());
        var seqStart67 = location();
        if (alt0_11.isSuccess()) {
            var elem67_0 = matchLiteralCst("assert", false);
            if (elem67_0.isSuccess() && elem67_0.node != null) {
                children.add(elem67_0.node);
            }
            if (elem67_0.isFailure()) {
                restoreLocation(seqStart67);
                alt0_11 = elem67_0;
            }
        }
        if (alt0_11.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia69 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem67_1 = parse_Expr(trivia69);
            if (elem67_1.isSuccess() && elem67_1.node != null) {
                children.add(elem67_1.node);
            }
            if (elem67_1.isFailure()) {
                restoreLocation(seqStart67);
                alt0_11 = elem67_1;
            }
        }
        if (alt0_11.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart70 = location();
            CstParseResult optElem70 = CstParseResult.success(null, "", location());
            var seqStart72 = location();
            if (optElem70.isSuccess()) {
                var elem72_0 = matchLiteralCst(":", false);
                if (elem72_0.isSuccess() && elem72_0.node != null) {
                    children.add(elem72_0.node);
                }
                if (elem72_0.isFailure()) {
                    restoreLocation(seqStart72);
                    optElem70 = elem72_0;
                }
            }
            if (optElem70.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var trivia74 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem72_1 = parse_Expr(trivia74);
                if (elem72_1.isSuccess() && elem72_1.node != null) {
                    children.add(elem72_1.node);
                }
                if (elem72_1.isFailure()) {
                    restoreLocation(seqStart72);
                    optElem70 = elem72_1;
                }
            }
            if (optElem70.isSuccess()) {
                optElem70 = CstParseResult.success(null, substring(seqStart72.offset(), pos), location());
            }
            var elem67_2 = optElem70.isSuccess() ? optElem70 : CstParseResult.success(null, "", location());
            if (optElem70.isFailure()) {
                restoreLocation(optStart70);
            }
            if (elem67_2.isFailure()) {
                restoreLocation(seqStart67);
                alt0_11 = elem67_2;
            }
        }
        if (alt0_11.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem67_3 = matchLiteralCst(";", false);
            if (elem67_3.isSuccess() && elem67_3.node != null) {
                children.add(elem67_3.node);
            }
            if (elem67_3.isFailure()) {
                restoreLocation(seqStart67);
                alt0_11 = elem67_3;
            }
        }
        if (alt0_11.isSuccess()) {
            alt0_11 = CstParseResult.success(null, substring(seqStart67.offset(), pos), location());
        }
        if (alt0_11.isSuccess()) {
            result = alt0_11;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_12 = CstParseResult.success(null, "", location());
        var seqStart76 = location();
        if (alt0_12.isSuccess()) {
            var elem76_0 = matchLiteralCst("synchronized", false);
            if (elem76_0.isSuccess() && elem76_0.node != null) {
                children.add(elem76_0.node);
            }
            if (elem76_0.isFailure()) {
                restoreLocation(seqStart76);
                alt0_12 = elem76_0;
            }
        }
        if (alt0_12.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem76_1 = matchLiteralCst("(", false);
            if (elem76_1.isSuccess() && elem76_1.node != null) {
                children.add(elem76_1.node);
            }
            if (elem76_1.isFailure()) {
                restoreLocation(seqStart76);
                alt0_12 = elem76_1;
            }
        }
        if (alt0_12.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia79 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem76_2 = parse_Expr(trivia79);
            if (elem76_2.isSuccess() && elem76_2.node != null) {
                children.add(elem76_2.node);
            }
            if (elem76_2.isFailure()) {
                restoreLocation(seqStart76);
                alt0_12 = elem76_2;
            }
        }
        if (alt0_12.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem76_3 = matchLiteralCst(")", false);
            if (elem76_3.isSuccess() && elem76_3.node != null) {
                children.add(elem76_3.node);
            }
            if (elem76_3.isFailure()) {
                restoreLocation(seqStart76);
                alt0_12 = elem76_3;
            }
        }
        if (alt0_12.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia81 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem76_4 = parse_Block(trivia81);
            if (elem76_4.isSuccess() && elem76_4.node != null) {
                children.add(elem76_4.node);
            }
            if (elem76_4.isFailure()) {
                restoreLocation(seqStart76);
                alt0_12 = elem76_4;
            }
        }
        if (alt0_12.isSuccess()) {
            alt0_12 = CstParseResult.success(null, substring(seqStart76.offset(), pos), location());
        }
        if (alt0_12.isSuccess()) {
            result = alt0_12;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_13 = CstParseResult.success(null, "", location());
        var seqStart82 = location();
        if (alt0_13.isSuccess()) {
            var elem82_0 = matchLiteralCst("yield", false);
            if (elem82_0.isSuccess() && elem82_0.node != null) {
                children.add(elem82_0.node);
            }
            if (elem82_0.isFailure()) {
                restoreLocation(seqStart82);
                alt0_13 = elem82_0;
            }
        }
        if (alt0_13.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia84 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem82_1 = parse_Expr(trivia84);
            if (elem82_1.isSuccess() && elem82_1.node != null) {
                children.add(elem82_1.node);
            }
            if (elem82_1.isFailure()) {
                restoreLocation(seqStart82);
                alt0_13 = elem82_1;
            }
        }
        if (alt0_13.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem82_2 = matchLiteralCst(";", false);
            if (elem82_2.isSuccess() && elem82_2.node != null) {
                children.add(elem82_2.node);
            }
            if (elem82_2.isFailure()) {
                restoreLocation(seqStart82);
                alt0_13 = elem82_2;
            }
        }
        if (alt0_13.isSuccess()) {
            alt0_13 = CstParseResult.success(null, substring(seqStart82.offset(), pos), location());
        }
        if (alt0_13.isSuccess()) {
            result = alt0_13;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_14 = CstParseResult.success(null, "", location());
        var seqStart86 = location();
        if (alt0_14.isSuccess()) {
            var trivia87 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem86_0 = parse_Expr(trivia87);
            if (elem86_0.isSuccess() && elem86_0.node != null) {
                children.add(elem86_0.node);
            }
            if (elem86_0.isFailure()) {
                restoreLocation(seqStart86);
                alt0_14 = elem86_0;
            }
        }
        if (alt0_14.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem86_1 = matchLiteralCst(";", false);
            if (elem86_1.isSuccess() && elem86_1.node != null) {
                children.add(elem86_1.node);
            }
            if (elem86_1.isFailure()) {
                restoreLocation(seqStart86);
                alt0_14 = elem86_1;
            }
        }
        if (alt0_14.isSuccess()) {
            alt0_14 = CstParseResult.success(null, substring(seqStart86.offset(), pos), location());
        }
        if (alt0_14.isSuccess()) {
            result = alt0_14;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_15 = matchLiteralCst(";", false);
        if (alt0_15.isSuccess() && alt0_15.node != null) {
            children.add(alt0_15.node);
        }
        if (alt0_15.isSuccess()) {
            result = alt0_15;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Stmt", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ForCtrl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(37, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var optStart2 = location();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem2 = parse_ForInit(trivia3);
            if (optElem2.isSuccess() && optElem2.node != null) {
                children.add(optElem2.node);
            }
            var elem1_0 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_1 = matchLiteralCst(";", false);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart5 = location();
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem5 = parse_Expr(trivia6);
            if (optElem5.isSuccess() && optElem5.node != null) {
                children.add(optElem5.node);
            }
            var elem1_2 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_3 = matchLiteralCst(";", false);
            if (elem1_3.isSuccess() && elem1_3.node != null) {
                children.add(elem1_3.node);
            }
            if (elem1_3.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_3;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart8 = location();
            var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem8 = parse_ExprList(trivia9);
            if (optElem8.isSuccess() && optElem8.node != null) {
                children.add(optElem8.node);
            }
            var elem1_4 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
            if (optElem8.isFailure()) {
                restoreLocation(optStart8);
            }
            if (elem1_4.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_4;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart10 = location();
        if (alt0_1.isSuccess()) {
            var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem10_0 = parse_Type(trivia11);
            if (elem10_0.isSuccess() && elem10_0.node != null) {
                children.add(elem10_0.node);
            }
            if (elem10_0.isFailure()) {
                restoreLocation(seqStart10);
                alt0_1 = elem10_0;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem10_1 = parse_Identifier(trivia12);
            if (elem10_1.isSuccess() && elem10_1.node != null) {
                children.add(elem10_1.node);
            }
            if (elem10_1.isFailure()) {
                restoreLocation(seqStart10);
                alt0_1 = elem10_1;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem10_2 = matchLiteralCst(":", false);
            if (elem10_2.isSuccess() && elem10_2.node != null) {
                children.add(elem10_2.node);
            }
            if (elem10_2.isFailure()) {
                restoreLocation(seqStart10);
                alt0_1 = elem10_2;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia14 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem10_3 = parse_Expr(trivia14);
            if (elem10_3.isSuccess() && elem10_3.node != null) {
                children.add(elem10_3.node);
            }
            if (elem10_3.isFailure()) {
                restoreLocation(seqStart10);
                alt0_1 = elem10_3;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart10.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "ForCtrl", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ForInit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(38, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_LocalVarNoSemi(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_ExprList(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "ForInit", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LocalVarNoSemi(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(39, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Modifier(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Type(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_VarDecls(trivia4);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "LocalVarNoSemi", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ResourceSpec(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(40, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("(", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Resource(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_2 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem3 = CstParseResult.success(null, "", location());
                var seqStart5 = location();
                if (zomElem3.isSuccess()) {
                    var elem5_0 = matchLiteralCst(";", false);
                    if (elem5_0.isSuccess() && elem5_0.node != null) {
                        children.add(elem5_0.node);
                    }
                    if (elem5_0.isFailure()) {
                        restoreLocation(seqStart5);
                        zomElem3 = elem5_0;
                    }
                }
                if (zomElem3.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem5_1 = parse_Resource(trivia7);
                    if (elem5_1.isSuccess() && elem5_1.node != null) {
                        children.add(elem5_1.node);
                    }
                    if (elem5_1.isFailure()) {
                        restoreLocation(seqStart5);
                        zomElem3 = elem5_1;
                    }
                }
                if (zomElem3.isSuccess()) {
                    zomElem3 = CstParseResult.success(null, substring(seqStart5.offset(), pos), location());
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_2 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart8 = location();
            var optElem8 = matchLiteralCst(";", false);
            if (optElem8.isSuccess() && optElem8.node != null) {
                children.add(optElem8.node);
            }
            var elem0_3 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
            if (optElem8.isFailure()) {
                restoreLocation(optStart8);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_4 = matchLiteralCst(")", false);
            if (elem0_4.isSuccess() && elem0_4.node != null) {
                children.add(elem0_4.node);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "ResourceSpec", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Resource(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(41, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            CstParseResult elem1_0 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_Modifier(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem1_0 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_1 = parse_Type(trivia4);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_2 = parse_Identifier(trivia5);
            if (elem1_2.isSuccess() && elem1_2.node != null) {
                children.add(elem1_2.node);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_3 = matchLiteralCst("=", false);
            if (elem1_3.isSuccess() && elem1_3.node != null) {
                children.add(elem1_3.node);
            }
            if (elem1_3.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_3;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_4 = parse_Expr(trivia7);
            if (elem1_4.isSuccess() && elem1_4.node != null) {
                children.add(elem1_4.node);
            }
            if (elem1_4.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_4;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_QualifiedName(trivia8);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Resource", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Catch(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(42, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("catch", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_1 = matchLiteralCst("(", false);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_2 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem3 = parse_Modifier(trivia4);
                if (zomElem3.isSuccess() && zomElem3.node != null) {
                    children.add(zomElem3.node);
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_2 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_3 = parse_Type(trivia5);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_4 = CstParseResult.success(null, "", location());
            var zomStart6 = location();
            while (true) {
                var beforeLoc6 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem6 = CstParseResult.success(null, "", location());
                var seqStart8 = location();
                if (zomElem6.isSuccess()) {
                    var elem8_0 = matchLiteralCst("|", false);
                    if (elem8_0.isSuccess() && elem8_0.node != null) {
                        children.add(elem8_0.node);
                    }
                    if (elem8_0.isFailure()) {
                        restoreLocation(seqStart8);
                        zomElem6 = elem8_0;
                    }
                }
                if (zomElem6.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem8_1 = parse_Type(trivia10);
                    if (elem8_1.isSuccess() && elem8_1.node != null) {
                        children.add(elem8_1.node);
                    }
                    if (elem8_1.isFailure()) {
                        restoreLocation(seqStart8);
                        zomElem6 = elem8_1;
                    }
                }
                if (zomElem6.isSuccess()) {
                    zomElem6 = CstParseResult.success(null, substring(seqStart8.offset(), pos), location());
                }
                if (zomElem6.isFailure() || location().offset() == beforeLoc6.offset()) {
                    restoreLocation(beforeLoc6);
                    break;
                }
            }
            elem0_4 = CstParseResult.success(null, substring(zomStart6.offset(), pos), location());
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_5 = parse_Identifier(trivia11);
            if (elem0_5.isSuccess() && elem0_5.node != null) {
                children.add(elem0_5.node);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_6 = matchLiteralCst(")", false);
            if (elem0_6.isSuccess() && elem0_6.node != null) {
                children.add(elem0_6.node);
            }
            if (elem0_6.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_6;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia13 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_7 = parse_Block(trivia13);
            if (elem0_7.isSuccess() && elem0_7.node != null) {
                children.add(elem0_7.node);
            }
            if (elem0_7.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_7;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Catch", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Finally(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(43, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("finally", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Block(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Finally", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_SwitchBlock(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(44, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("{", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_SwitchRule(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("}", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "SwitchBlock", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_SwitchRule(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(45, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_0 = parse_SwitchLabel(trivia2);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_1 = matchLiteralCst("->", false);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem1_2 = null;
            var choiceStart5 = location();
            var savedChildren5 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren5);
            CstParseResult alt5_0 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (alt5_0.isSuccess()) {
                var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem6_0 = parse_Expr(trivia7);
                if (elem6_0.isSuccess() && elem6_0.node != null) {
                    children.add(elem6_0.node);
                }
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    alt5_0 = elem6_0;
                }
            }
            if (alt5_0.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem6_1 = matchLiteralCst(";", false);
                if (elem6_1.isSuccess() && elem6_1.node != null) {
                    children.add(elem6_1.node);
                }
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    alt5_0 = elem6_1;
                }
            }
            if (alt5_0.isSuccess()) {
                alt5_0 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            if (alt5_0.isSuccess()) {
                elem1_2 = alt5_0;
            } else {
                restoreLocation(choiceStart5);
            children.clear();
            children.addAll(savedChildren5);
            var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt5_1 = parse_Block(trivia9);
            if (alt5_1.isSuccess() && alt5_1.node != null) {
                children.add(alt5_1.node);
            }
            if (alt5_1.isSuccess()) {
                elem1_2 = alt5_1;
            } else {
                restoreLocation(choiceStart5);
            children.clear();
            children.addAll(savedChildren5);
            CstParseResult alt5_2 = CstParseResult.success(null, "", location());
            var seqStart10 = location();
            if (alt5_2.isSuccess()) {
                var elem10_0 = matchLiteralCst("throw", false);
                if (elem10_0.isSuccess() && elem10_0.node != null) {
                    children.add(elem10_0.node);
                }
                if (elem10_0.isFailure()) {
                    restoreLocation(seqStart10);
                    alt5_2 = elem10_0;
                }
            }
            if (alt5_2.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem10_1 = parse_Expr(trivia12);
                if (elem10_1.isSuccess() && elem10_1.node != null) {
                    children.add(elem10_1.node);
                }
                if (elem10_1.isFailure()) {
                    restoreLocation(seqStart10);
                    alt5_2 = elem10_1;
                }
            }
            if (alt5_2.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem10_2 = matchLiteralCst(";", false);
                if (elem10_2.isSuccess() && elem10_2.node != null) {
                    children.add(elem10_2.node);
                }
                if (elem10_2.isFailure()) {
                    restoreLocation(seqStart10);
                    alt5_2 = elem10_2;
                }
            }
            if (alt5_2.isSuccess()) {
                alt5_2 = CstParseResult.success(null, substring(seqStart10.offset(), pos), location());
            }
            if (alt5_2.isSuccess()) {
                elem1_2 = alt5_2;
            } else {
                restoreLocation(choiceStart5);
            }
            }
            }
            if (elem1_2 == null) {
                children.clear();
                children.addAll(savedChildren5);
                elem1_2 = CstParseResult.failure("one of alternatives");
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart14 = location();
        if (alt0_1.isSuccess()) {
            var trivia15 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem14_0 = parse_SwitchLabel(trivia15);
            if (elem14_0.isSuccess() && elem14_0.node != null) {
                children.add(elem14_0.node);
            }
            if (elem14_0.isFailure()) {
                restoreLocation(seqStart14);
                alt0_1 = elem14_0;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem14_1 = matchLiteralCst(":", false);
            if (elem14_1.isSuccess() && elem14_1.node != null) {
                children.add(elem14_1.node);
            }
            if (elem14_1.isFailure()) {
                restoreLocation(seqStart14);
                alt0_1 = elem14_1;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem14_2 = CstParseResult.success(null, "", location());
            var zomStart17 = location();
            while (true) {
                var beforeLoc17 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia18 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem17 = parse_BlockStmt(trivia18);
                if (zomElem17.isSuccess() && zomElem17.node != null) {
                    children.add(zomElem17.node);
                }
                if (zomElem17.isFailure() || location().offset() == beforeLoc17.offset()) {
                    restoreLocation(beforeLoc17);
                    break;
                }
            }
            elem14_2 = CstParseResult.success(null, substring(zomStart17.offset(), pos), location());
            if (elem14_2.isFailure()) {
                restoreLocation(seqStart14);
                alt0_1 = elem14_2;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart14.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "SwitchRule", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_SwitchLabel(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(46, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var elem1_0 = matchLiteralCst("case", false);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_1 = parse_CaseItem(trivia3);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem1_2 = CstParseResult.success(null, "", location());
            var zomStart4 = location();
            while (true) {
                var beforeLoc4 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem4 = CstParseResult.success(null, "", location());
                var seqStart6 = location();
                if (zomElem4.isSuccess()) {
                    var elem6_0 = matchLiteralCst(",", false);
                    if (elem6_0.isSuccess() && elem6_0.node != null) {
                        children.add(elem6_0.node);
                    }
                    if (elem6_0.isFailure()) {
                        restoreLocation(seqStart6);
                        zomElem4 = elem6_0;
                    }
                }
                if (zomElem4.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem6_1 = parse_CaseItem(trivia8);
                    if (elem6_1.isSuccess() && elem6_1.node != null) {
                        children.add(elem6_1.node);
                    }
                    if (elem6_1.isFailure()) {
                        restoreLocation(seqStart6);
                        zomElem4 = elem6_1;
                    }
                }
                if (zomElem4.isSuccess()) {
                    zomElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
                }
                if (zomElem4.isFailure() || location().offset() == beforeLoc4.offset()) {
                    restoreLocation(beforeLoc4);
                    break;
                }
            }
            elem1_2 = CstParseResult.success(null, substring(zomStart4.offset(), pos), location());
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_1 = matchLiteralCst("default", false);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "SwitchLabel", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_CaseItem(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(47, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_0 = parse_Type(trivia2);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_1 = parse_Identifier(trivia3);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_Expr(trivia4);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "CaseItem", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Expr(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(48, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        var trivia0 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var result = parse_Ternary(trivia0);
        if (result.isSuccess() && result.node != null) {
            children.add(result.node);
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Expr", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Ternary(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(49, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_LogOr(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart2 = location();
            CstParseResult optElem2 = CstParseResult.success(null, "", location());
            var seqStart4 = location();
            if (optElem2.isSuccess()) {
                var elem4_0 = matchLiteralCst("?", false);
                if (elem4_0.isSuccess() && elem4_0.node != null) {
                    children.add(elem4_0.node);
                }
                if (elem4_0.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_0;
                }
            }
            if (optElem2.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem4_1 = parse_Expr(trivia6);
                if (elem4_1.isSuccess() && elem4_1.node != null) {
                    children.add(elem4_1.node);
                }
                if (elem4_1.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_1;
                }
            }
            if (optElem2.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem4_2 = matchLiteralCst(":", false);
                if (elem4_2.isSuccess() && elem4_2.node != null) {
                    children.add(elem4_2.node);
                }
                if (elem4_2.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_2;
                }
            }
            if (optElem2.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem4_3 = parse_Ternary(trivia8);
                if (elem4_3.isSuccess() && elem4_3.node != null) {
                    children.add(elem4_3.node);
                }
                if (elem4_3.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_3;
                }
            }
            if (optElem2.isSuccess()) {
                optElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Ternary", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LogOr(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(50, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_LogAnd(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst("||", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_LogAnd(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "LogOr", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LogAnd(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(51, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_BitOr(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst("&&", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_BitOr(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "LogAnd", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_BitOr(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(52, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_BitXor(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst("|", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_BitXor(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "BitOr", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_BitXor(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(53, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_BitAnd(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst("^", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_BitAnd(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "BitXor", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_BitAnd(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(54, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Equality(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst("&", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Equality(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "BitAnd", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Equality(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(55, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Relational(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    CstParseResult elem4_0 = null;
                    var choiceStart6 = location();
                    var savedChildren6 = new ArrayList<>(children);
                    children.clear();
                    children.addAll(savedChildren6);
                    var alt6_0 = matchLiteralCst("==", false);
                    if (alt6_0.isSuccess() && alt6_0.node != null) {
                        children.add(alt6_0.node);
                    }
                    if (alt6_0.isSuccess()) {
                        elem4_0 = alt6_0;
                    } else {
                        restoreLocation(choiceStart6);
                    children.clear();
                    children.addAll(savedChildren6);
                    var alt6_1 = matchLiteralCst("!=", false);
                    if (alt6_1.isSuccess() && alt6_1.node != null) {
                        children.add(alt6_1.node);
                    }
                    if (alt6_1.isSuccess()) {
                        elem4_0 = alt6_1;
                    } else {
                        restoreLocation(choiceStart6);
                    }
                    }
                    if (elem4_0 == null) {
                        children.clear();
                        children.addAll(savedChildren6);
                        elem4_0 = CstParseResult.failure("one of alternatives");
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Relational(trivia9);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Equality", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Relational(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(56, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Shift(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart2 = location();
            CstParseResult optElem2 = null;
            var choiceStart4 = location();
            var savedChildren4 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren4);
            CstParseResult alt4_0 = CstParseResult.success(null, "", location());
            var seqStart5 = location();
            if (alt4_0.isSuccess()) {
                CstParseResult elem5_0 = null;
                var choiceStart7 = location();
                var savedChildren7 = new ArrayList<>(children);
                children.clear();
                children.addAll(savedChildren7);
                var alt7_0 = matchLiteralCst("<=", false);
                if (alt7_0.isSuccess() && alt7_0.node != null) {
                    children.add(alt7_0.node);
                }
                if (alt7_0.isSuccess()) {
                    elem5_0 = alt7_0;
                } else {
                    restoreLocation(choiceStart7);
                children.clear();
                children.addAll(savedChildren7);
                var alt7_1 = matchLiteralCst(">=", false);
                if (alt7_1.isSuccess() && alt7_1.node != null) {
                    children.add(alt7_1.node);
                }
                if (alt7_1.isSuccess()) {
                    elem5_0 = alt7_1;
                } else {
                    restoreLocation(choiceStart7);
                children.clear();
                children.addAll(savedChildren7);
                var alt7_2 = matchLiteralCst("<", false);
                if (alt7_2.isSuccess() && alt7_2.node != null) {
                    children.add(alt7_2.node);
                }
                if (alt7_2.isSuccess()) {
                    elem5_0 = alt7_2;
                } else {
                    restoreLocation(choiceStart7);
                children.clear();
                children.addAll(savedChildren7);
                var alt7_3 = matchLiteralCst(">", false);
                if (alt7_3.isSuccess() && alt7_3.node != null) {
                    children.add(alt7_3.node);
                }
                if (alt7_3.isSuccess()) {
                    elem5_0 = alt7_3;
                } else {
                    restoreLocation(choiceStart7);
                }
                }
                }
                }
                if (elem5_0 == null) {
                    children.clear();
                    children.addAll(savedChildren7);
                    elem5_0 = CstParseResult.failure("one of alternatives");
                }
                if (elem5_0.isFailure()) {
                    restoreLocation(seqStart5);
                    alt4_0 = elem5_0;
                }
            }
            if (alt4_0.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem5_1 = parse_Shift(trivia12);
                if (elem5_1.isSuccess() && elem5_1.node != null) {
                    children.add(elem5_1.node);
                }
                if (elem5_1.isFailure()) {
                    restoreLocation(seqStart5);
                    alt4_0 = elem5_1;
                }
            }
            if (alt4_0.isSuccess()) {
                alt4_0 = CstParseResult.success(null, substring(seqStart5.offset(), pos), location());
            }
            if (alt4_0.isSuccess()) {
                optElem2 = alt4_0;
            } else {
                restoreLocation(choiceStart4);
            children.clear();
            children.addAll(savedChildren4);
            CstParseResult alt4_1 = CstParseResult.success(null, "", location());
            var seqStart13 = location();
            if (alt4_1.isSuccess()) {
                var elem13_0 = matchLiteralCst("instanceof", false);
                if (elem13_0.isSuccess() && elem13_0.node != null) {
                    children.add(elem13_0.node);
                }
                if (elem13_0.isFailure()) {
                    restoreLocation(seqStart13);
                    alt4_1 = elem13_0;
                }
            }
            if (alt4_1.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var trivia15 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem13_1 = parse_Type(trivia15);
                if (elem13_1.isSuccess() && elem13_1.node != null) {
                    children.add(elem13_1.node);
                }
                if (elem13_1.isFailure()) {
                    restoreLocation(seqStart13);
                    alt4_1 = elem13_1;
                }
            }
            if (alt4_1.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var optStart16 = location();
                var trivia17 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem16 = parse_Identifier(trivia17);
                if (optElem16.isSuccess() && optElem16.node != null) {
                    children.add(optElem16.node);
                }
                var elem13_2 = optElem16.isSuccess() ? optElem16 : CstParseResult.success(null, "", location());
                if (optElem16.isFailure()) {
                    restoreLocation(optStart16);
                }
                if (elem13_2.isFailure()) {
                    restoreLocation(seqStart13);
                    alt4_1 = elem13_2;
                }
            }
            if (alt4_1.isSuccess()) {
                alt4_1 = CstParseResult.success(null, substring(seqStart13.offset(), pos), location());
            }
            if (alt4_1.isSuccess()) {
                optElem2 = alt4_1;
            } else {
                restoreLocation(choiceStart4);
            }
            }
            if (optElem2 == null) {
                children.clear();
                children.addAll(savedChildren4);
                optElem2 = CstParseResult.failure("one of alternatives");
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Relational", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Shift(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(57, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Additive(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    CstParseResult elem4_0 = null;
                    var choiceStart6 = location();
                    var savedChildren6 = new ArrayList<>(children);
                    children.clear();
                    children.addAll(savedChildren6);
                    var alt6_0 = matchLiteralCst("<<", false);
                    if (alt6_0.isSuccess() && alt6_0.node != null) {
                        children.add(alt6_0.node);
                    }
                    if (alt6_0.isSuccess()) {
                        elem4_0 = alt6_0;
                    } else {
                        restoreLocation(choiceStart6);
                    children.clear();
                    children.addAll(savedChildren6);
                    var alt6_1 = matchLiteralCst(">>>", false);
                    if (alt6_1.isSuccess() && alt6_1.node != null) {
                        children.add(alt6_1.node);
                    }
                    if (alt6_1.isSuccess()) {
                        elem4_0 = alt6_1;
                    } else {
                        restoreLocation(choiceStart6);
                    children.clear();
                    children.addAll(savedChildren6);
                    var alt6_2 = matchLiteralCst(">>", false);
                    if (alt6_2.isSuccess() && alt6_2.node != null) {
                        children.add(alt6_2.node);
                    }
                    if (alt6_2.isSuccess()) {
                        elem4_0 = alt6_2;
                    } else {
                        restoreLocation(choiceStart6);
                    }
                    }
                    }
                    if (elem4_0 == null) {
                        children.clear();
                        children.addAll(savedChildren6);
                        elem4_0 = CstParseResult.failure("one of alternatives");
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Additive(trivia10);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Shift", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Additive(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(58, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Multiplicative(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    CstParseResult elem4_0 = null;
                    var choiceStart6 = location();
                    var savedChildren6 = new ArrayList<>(children);
                    children.clear();
                    children.addAll(savedChildren6);
                    var alt6_0 = matchLiteralCst("+", false);
                    if (alt6_0.isSuccess() && alt6_0.node != null) {
                        children.add(alt6_0.node);
                    }
                    if (alt6_0.isSuccess()) {
                        elem4_0 = alt6_0;
                    } else {
                        restoreLocation(choiceStart6);
                    children.clear();
                    children.addAll(savedChildren6);
                    var alt6_1 = matchLiteralCst("-", false);
                    if (alt6_1.isSuccess() && alt6_1.node != null) {
                        children.add(alt6_1.node);
                    }
                    if (alt6_1.isSuccess()) {
                        elem4_0 = alt6_1;
                    } else {
                        restoreLocation(choiceStart6);
                    }
                    }
                    if (elem4_0 == null) {
                        children.clear();
                        children.addAll(savedChildren6);
                        elem4_0 = CstParseResult.failure("one of alternatives");
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Multiplicative(trivia9);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Additive", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Multiplicative(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(59, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Unary(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    CstParseResult elem4_0 = null;
                    var choiceStart6 = location();
                    var savedChildren6 = new ArrayList<>(children);
                    children.clear();
                    children.addAll(savedChildren6);
                    var alt6_0 = matchLiteralCst("*", false);
                    if (alt6_0.isSuccess() && alt6_0.node != null) {
                        children.add(alt6_0.node);
                    }
                    if (alt6_0.isSuccess()) {
                        elem4_0 = alt6_0;
                    } else {
                        restoreLocation(choiceStart6);
                    children.clear();
                    children.addAll(savedChildren6);
                    var alt6_1 = matchLiteralCst("/", false);
                    if (alt6_1.isSuccess() && alt6_1.node != null) {
                        children.add(alt6_1.node);
                    }
                    if (alt6_1.isSuccess()) {
                        elem4_0 = alt6_1;
                    } else {
                        restoreLocation(choiceStart6);
                    children.clear();
                    children.addAll(savedChildren6);
                    var alt6_2 = matchLiteralCst("%", false);
                    if (alt6_2.isSuccess() && alt6_2.node != null) {
                        children.add(alt6_2.node);
                    }
                    if (alt6_2.isSuccess()) {
                        elem4_0 = alt6_2;
                    } else {
                        restoreLocation(choiceStart6);
                    }
                    }
                    }
                    if (elem4_0 == null) {
                        children.clear();
                        children.addAll(savedChildren6);
                        elem4_0 = CstParseResult.failure("one of alternatives");
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Unary(trivia10);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Multiplicative", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Unary(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(60, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            CstParseResult elem1_0 = null;
            var choiceStart3 = location();
            var savedChildren3 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren3);
            var alt3_0 = matchLiteralCst("++", false);
            if (alt3_0.isSuccess() && alt3_0.node != null) {
                children.add(alt3_0.node);
            }
            if (alt3_0.isSuccess()) {
                elem1_0 = alt3_0;
            } else {
                restoreLocation(choiceStart3);
            children.clear();
            children.addAll(savedChildren3);
            var alt3_1 = matchLiteralCst("--", false);
            if (alt3_1.isSuccess() && alt3_1.node != null) {
                children.add(alt3_1.node);
            }
            if (alt3_1.isSuccess()) {
                elem1_0 = alt3_1;
            } else {
                restoreLocation(choiceStart3);
            children.clear();
            children.addAll(savedChildren3);
            var alt3_2 = matchLiteralCst("+", false);
            if (alt3_2.isSuccess() && alt3_2.node != null) {
                children.add(alt3_2.node);
            }
            if (alt3_2.isSuccess()) {
                elem1_0 = alt3_2;
            } else {
                restoreLocation(choiceStart3);
            children.clear();
            children.addAll(savedChildren3);
            var alt3_3 = matchLiteralCst("-", false);
            if (alt3_3.isSuccess() && alt3_3.node != null) {
                children.add(alt3_3.node);
            }
            if (alt3_3.isSuccess()) {
                elem1_0 = alt3_3;
            } else {
                restoreLocation(choiceStart3);
            children.clear();
            children.addAll(savedChildren3);
            var alt3_4 = matchLiteralCst("!", false);
            if (alt3_4.isSuccess() && alt3_4.node != null) {
                children.add(alt3_4.node);
            }
            if (alt3_4.isSuccess()) {
                elem1_0 = alt3_4;
            } else {
                restoreLocation(choiceStart3);
            children.clear();
            children.addAll(savedChildren3);
            var alt3_5 = matchLiteralCst("~", false);
            if (alt3_5.isSuccess() && alt3_5.node != null) {
                children.add(alt3_5.node);
            }
            if (alt3_5.isSuccess()) {
                elem1_0 = alt3_5;
            } else {
                restoreLocation(choiceStart3);
            }
            }
            }
            }
            }
            }
            if (elem1_0 == null) {
                children.clear();
                children.addAll(savedChildren3);
                elem1_0 = CstParseResult.failure("one of alternatives");
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_1 = parse_Unary(trivia10);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart11 = location();
        if (alt0_1.isSuccess()) {
            var elem11_0 = matchLiteralCst("(", false);
            if (elem11_0.isSuccess() && elem11_0.node != null) {
                children.add(elem11_0.node);
            }
            if (elem11_0.isFailure()) {
                restoreLocation(seqStart11);
                alt0_1 = elem11_0;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia13 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem11_1 = parse_Type(trivia13);
            if (elem11_1.isSuccess() && elem11_1.node != null) {
                children.add(elem11_1.node);
            }
            if (elem11_1.isFailure()) {
                restoreLocation(seqStart11);
                alt0_1 = elem11_1;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem11_2 = matchLiteralCst(")", false);
            if (elem11_2.isSuccess() && elem11_2.node != null) {
                children.add(elem11_2.node);
            }
            if (elem11_2.isFailure()) {
                restoreLocation(seqStart11);
                alt0_1 = elem11_2;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia15 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem11_3 = parse_Unary(trivia15);
            if (elem11_3.isSuccess() && elem11_3.node != null) {
                children.add(elem11_3.node);
            }
            if (elem11_3.isFailure()) {
                restoreLocation(seqStart11);
                alt0_1 = elem11_3;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart11.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia16 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_2 = parse_Postfix(trivia16);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Unary", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Postfix(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(61, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Primary(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_PostOp(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Postfix", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_PostOp(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(62, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var elem1_0 = matchLiteralCst(".", false);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_1 = parse_Identifier(trivia3);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart4 = location();
            CstParseResult optElem4 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (optElem4.isSuccess()) {
                var elem6_0 = matchLiteralCst("(", false);
                if (elem6_0.isSuccess() && elem6_0.node != null) {
                    children.add(elem6_0.node);
                }
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_0;
                }
            }
            if (optElem4.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var optStart8 = location();
                var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem8 = parse_Args(trivia9);
                if (optElem8.isSuccess() && optElem8.node != null) {
                    children.add(optElem8.node);
                }
                var elem6_1 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
                if (optElem8.isFailure()) {
                    restoreLocation(optStart8);
                }
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_1;
                }
            }
            if (optElem4.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem6_2 = matchLiteralCst(")", false);
                if (elem6_2.isSuccess() && elem6_2.node != null) {
                    children.add(elem6_2.node);
                }
                if (elem6_2.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_2;
                }
            }
            if (optElem4.isSuccess()) {
                optElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            var elem1_2 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart11 = location();
        if (alt0_1.isSuccess()) {
            var elem11_0 = matchLiteralCst(".", false);
            if (elem11_0.isSuccess() && elem11_0.node != null) {
                children.add(elem11_0.node);
            }
            if (elem11_0.isFailure()) {
                restoreLocation(seqStart11);
                alt0_1 = elem11_0;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem11_1 = matchLiteralCst("class", false);
            if (elem11_1.isSuccess() && elem11_1.node != null) {
                children.add(elem11_1.node);
            }
            if (elem11_1.isFailure()) {
                restoreLocation(seqStart11);
                alt0_1 = elem11_1;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart11.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_2 = CstParseResult.success(null, "", location());
        var seqStart14 = location();
        if (alt0_2.isSuccess()) {
            var elem14_0 = matchLiteralCst(".", false);
            if (elem14_0.isSuccess() && elem14_0.node != null) {
                children.add(elem14_0.node);
            }
            if (elem14_0.isFailure()) {
                restoreLocation(seqStart14);
                alt0_2 = elem14_0;
            }
        }
        if (alt0_2.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem14_1 = matchLiteralCst("this", false);
            if (elem14_1.isSuccess() && elem14_1.node != null) {
                children.add(elem14_1.node);
            }
            if (elem14_1.isFailure()) {
                restoreLocation(seqStart14);
                alt0_2 = elem14_1;
            }
        }
        if (alt0_2.isSuccess()) {
            alt0_2 = CstParseResult.success(null, substring(seqStart14.offset(), pos), location());
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_3 = CstParseResult.success(null, "", location());
        var seqStart17 = location();
        if (alt0_3.isSuccess()) {
            var elem17_0 = matchLiteralCst("[", false);
            if (elem17_0.isSuccess() && elem17_0.node != null) {
                children.add(elem17_0.node);
            }
            if (elem17_0.isFailure()) {
                restoreLocation(seqStart17);
                alt0_3 = elem17_0;
            }
        }
        if (alt0_3.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia19 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem17_1 = parse_Expr(trivia19);
            if (elem17_1.isSuccess() && elem17_1.node != null) {
                children.add(elem17_1.node);
            }
            if (elem17_1.isFailure()) {
                restoreLocation(seqStart17);
                alt0_3 = elem17_1;
            }
        }
        if (alt0_3.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem17_2 = matchLiteralCst("]", false);
            if (elem17_2.isSuccess() && elem17_2.node != null) {
                children.add(elem17_2.node);
            }
            if (elem17_2.isFailure()) {
                restoreLocation(seqStart17);
                alt0_3 = elem17_2;
            }
        }
        if (alt0_3.isSuccess()) {
            alt0_3 = CstParseResult.success(null, substring(seqStart17.offset(), pos), location());
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_4 = CstParseResult.success(null, "", location());
        var seqStart21 = location();
        if (alt0_4.isSuccess()) {
            var elem21_0 = matchLiteralCst("(", false);
            if (elem21_0.isSuccess() && elem21_0.node != null) {
                children.add(elem21_0.node);
            }
            if (elem21_0.isFailure()) {
                restoreLocation(seqStart21);
                alt0_4 = elem21_0;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart23 = location();
            var trivia24 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem23 = parse_Args(trivia24);
            if (optElem23.isSuccess() && optElem23.node != null) {
                children.add(optElem23.node);
            }
            var elem21_1 = optElem23.isSuccess() ? optElem23 : CstParseResult.success(null, "", location());
            if (optElem23.isFailure()) {
                restoreLocation(optStart23);
            }
            if (elem21_1.isFailure()) {
                restoreLocation(seqStart21);
                alt0_4 = elem21_1;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem21_2 = matchLiteralCst(")", false);
            if (elem21_2.isSuccess() && elem21_2.node != null) {
                children.add(elem21_2.node);
            }
            if (elem21_2.isFailure()) {
                restoreLocation(seqStart21);
                alt0_4 = elem21_2;
            }
        }
        if (alt0_4.isSuccess()) {
            alt0_4 = CstParseResult.success(null, substring(seqStart21.offset(), pos), location());
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_5 = matchLiteralCst("++", false);
        if (alt0_5.isSuccess() && alt0_5.node != null) {
            children.add(alt0_5.node);
        }
        if (alt0_5.isSuccess()) {
            result = alt0_5;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_6 = matchLiteralCst("--", false);
        if (alt0_6.isSuccess() && alt0_6.node != null) {
            children.add(alt0_6.node);
        }
        if (alt0_6.isSuccess()) {
            result = alt0_6;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_7 = CstParseResult.success(null, "", location());
        var seqStart28 = location();
        if (alt0_7.isSuccess()) {
            var elem28_0 = matchLiteralCst("::", false);
            if (elem28_0.isSuccess() && elem28_0.node != null) {
                children.add(elem28_0.node);
            }
            if (elem28_0.isFailure()) {
                restoreLocation(seqStart28);
                alt0_7 = elem28_0;
            }
        }
        if (alt0_7.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart30 = location();
            var trivia31 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem30 = parse_TypeArgs(trivia31);
            if (optElem30.isSuccess() && optElem30.node != null) {
                children.add(optElem30.node);
            }
            var elem28_1 = optElem30.isSuccess() ? optElem30 : CstParseResult.success(null, "", location());
            if (optElem30.isFailure()) {
                restoreLocation(optStart30);
            }
            if (elem28_1.isFailure()) {
                restoreLocation(seqStart28);
                alt0_7 = elem28_1;
            }
        }
        if (alt0_7.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem28_2 = null;
            var choiceStart33 = location();
            var savedChildren33 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren33);
            var trivia34 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt33_0 = parse_Identifier(trivia34);
            if (alt33_0.isSuccess() && alt33_0.node != null) {
                children.add(alt33_0.node);
            }
            if (alt33_0.isSuccess()) {
                elem28_2 = alt33_0;
            } else {
                restoreLocation(choiceStart33);
            children.clear();
            children.addAll(savedChildren33);
            var alt33_1 = matchLiteralCst("new", false);
            if (alt33_1.isSuccess() && alt33_1.node != null) {
                children.add(alt33_1.node);
            }
            if (alt33_1.isSuccess()) {
                elem28_2 = alt33_1;
            } else {
                restoreLocation(choiceStart33);
            }
            }
            if (elem28_2 == null) {
                children.clear();
                children.addAll(savedChildren33);
                elem28_2 = CstParseResult.failure("one of alternatives");
            }
            if (elem28_2.isFailure()) {
                restoreLocation(seqStart28);
                alt0_7 = elem28_2;
            }
        }
        if (alt0_7.isSuccess()) {
            alt0_7 = CstParseResult.success(null, substring(seqStart28.offset(), pos), location());
        }
        if (alt0_7.isSuccess()) {
            result = alt0_7;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "PostOp", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Primary(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(63, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_Literal(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_1 = matchLiteralCst("this", false);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_2 = matchLiteralCst("super", false);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_3 = CstParseResult.success(null, "", location());
        var seqStart4 = location();
        if (alt0_3.isSuccess()) {
            var elem4_0 = matchLiteralCst("new", false);
            if (elem4_0.isSuccess() && elem4_0.node != null) {
                children.add(elem4_0.node);
            }
            if (elem4_0.isFailure()) {
                restoreLocation(seqStart4);
                alt0_3 = elem4_0;
            }
        }
        if (alt0_3.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart6 = location();
            var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem6 = parse_TypeArgs(trivia7);
            if (optElem6.isSuccess() && optElem6.node != null) {
                children.add(optElem6.node);
            }
            var elem4_1 = optElem6.isSuccess() ? optElem6 : CstParseResult.success(null, "", location());
            if (optElem6.isFailure()) {
                restoreLocation(optStart6);
            }
            if (elem4_1.isFailure()) {
                restoreLocation(seqStart4);
                alt0_3 = elem4_1;
            }
        }
        if (alt0_3.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem4_2 = parse_Type(trivia8);
            if (elem4_2.isSuccess() && elem4_2.node != null) {
                children.add(elem4_2.node);
            }
            if (elem4_2.isFailure()) {
                restoreLocation(seqStart4);
                alt0_3 = elem4_2;
            }
        }
        if (alt0_3.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem4_3 = null;
            var choiceStart10 = location();
            var savedChildren10 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren10);
            CstParseResult alt10_0 = CstParseResult.success(null, "", location());
            var seqStart11 = location();
            if (alt10_0.isSuccess()) {
                var elem11_0 = matchLiteralCst("(", false);
                if (elem11_0.isSuccess() && elem11_0.node != null) {
                    children.add(elem11_0.node);
                }
                if (elem11_0.isFailure()) {
                    restoreLocation(seqStart11);
                    alt10_0 = elem11_0;
                }
            }
            if (alt10_0.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var optStart13 = location();
                var trivia14 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem13 = parse_Args(trivia14);
                if (optElem13.isSuccess() && optElem13.node != null) {
                    children.add(optElem13.node);
                }
                var elem11_1 = optElem13.isSuccess() ? optElem13 : CstParseResult.success(null, "", location());
                if (optElem13.isFailure()) {
                    restoreLocation(optStart13);
                }
                if (elem11_1.isFailure()) {
                    restoreLocation(seqStart11);
                    alt10_0 = elem11_1;
                }
            }
            if (alt10_0.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem11_2 = matchLiteralCst(")", false);
                if (elem11_2.isSuccess() && elem11_2.node != null) {
                    children.add(elem11_2.node);
                }
                if (elem11_2.isFailure()) {
                    restoreLocation(seqStart11);
                    alt10_0 = elem11_2;
                }
            }
            if (alt10_0.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var optStart16 = location();
                var trivia17 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem16 = parse_ClassBody(trivia17);
                if (optElem16.isSuccess() && optElem16.node != null) {
                    children.add(optElem16.node);
                }
                var elem11_3 = optElem16.isSuccess() ? optElem16 : CstParseResult.success(null, "", location());
                if (optElem16.isFailure()) {
                    restoreLocation(optStart16);
                }
                if (elem11_3.isFailure()) {
                    restoreLocation(seqStart11);
                    alt10_0 = elem11_3;
                }
            }
            if (alt10_0.isSuccess()) {
                alt10_0 = CstParseResult.success(null, substring(seqStart11.offset(), pos), location());
            }
            if (alt10_0.isSuccess()) {
                elem4_3 = alt10_0;
            } else {
                restoreLocation(choiceStart10);
            children.clear();
            children.addAll(savedChildren10);
            CstParseResult alt10_1 = CstParseResult.success(null, "", location());
            var seqStart18 = location();
            if (alt10_1.isSuccess()) {
                var optStart19 = location();
                var trivia20 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem19 = parse_Dims(trivia20);
                if (optElem19.isSuccess() && optElem19.node != null) {
                    children.add(optElem19.node);
                }
                var elem18_0 = optElem19.isSuccess() ? optElem19 : CstParseResult.success(null, "", location());
                if (optElem19.isFailure()) {
                    restoreLocation(optStart19);
                }
                if (elem18_0.isFailure()) {
                    restoreLocation(seqStart18);
                    alt10_1 = elem18_0;
                }
            }
            if (alt10_1.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var optStart21 = location();
                var trivia22 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem21 = parse_VarInit(trivia22);
                if (optElem21.isSuccess() && optElem21.node != null) {
                    children.add(optElem21.node);
                }
                var elem18_1 = optElem21.isSuccess() ? optElem21 : CstParseResult.success(null, "", location());
                if (optElem21.isFailure()) {
                    restoreLocation(optStart21);
                }
                if (elem18_1.isFailure()) {
                    restoreLocation(seqStart18);
                    alt10_1 = elem18_1;
                }
            }
            if (alt10_1.isSuccess()) {
                alt10_1 = CstParseResult.success(null, substring(seqStart18.offset(), pos), location());
            }
            if (alt10_1.isSuccess()) {
                elem4_3 = alt10_1;
            } else {
                restoreLocation(choiceStart10);
            }
            }
            if (elem4_3 == null) {
                children.clear();
                children.addAll(savedChildren10);
                elem4_3 = CstParseResult.failure("one of alternatives");
            }
            if (elem4_3.isFailure()) {
                restoreLocation(seqStart4);
                alt0_3 = elem4_3;
            }
        }
        if (alt0_3.isSuccess()) {
            alt0_3 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_4 = CstParseResult.success(null, "", location());
        var seqStart23 = location();
        if (alt0_4.isSuccess()) {
            var elem23_0 = matchLiteralCst("(", false);
            if (elem23_0.isSuccess() && elem23_0.node != null) {
                children.add(elem23_0.node);
            }
            if (elem23_0.isFailure()) {
                restoreLocation(seqStart23);
                alt0_4 = elem23_0;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia25 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem23_1 = parse_Expr(trivia25);
            if (elem23_1.isSuccess() && elem23_1.node != null) {
                children.add(elem23_1.node);
            }
            if (elem23_1.isFailure()) {
                restoreLocation(seqStart23);
                alt0_4 = elem23_1;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem23_2 = matchLiteralCst(")", false);
            if (elem23_2.isSuccess() && elem23_2.node != null) {
                children.add(elem23_2.node);
            }
            if (elem23_2.isFailure()) {
                restoreLocation(seqStart23);
                alt0_4 = elem23_2;
            }
        }
        if (alt0_4.isSuccess()) {
            alt0_4 = CstParseResult.success(null, substring(seqStart23.offset(), pos), location());
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia27 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_5 = parse_Lambda(trivia27);
        if (alt0_5.isSuccess() && alt0_5.node != null) {
            children.add(alt0_5.node);
        }
        if (alt0_5.isSuccess()) {
            result = alt0_5;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_6 = CstParseResult.success(null, "", location());
        var seqStart28 = location();
        if (alt0_6.isSuccess()) {
            var elem28_0 = matchLiteralCst("switch", false);
            if (elem28_0.isSuccess() && elem28_0.node != null) {
                children.add(elem28_0.node);
            }
            if (elem28_0.isFailure()) {
                restoreLocation(seqStart28);
                alt0_6 = elem28_0;
            }
        }
        if (alt0_6.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem28_1 = matchLiteralCst("(", false);
            if (elem28_1.isSuccess() && elem28_1.node != null) {
                children.add(elem28_1.node);
            }
            if (elem28_1.isFailure()) {
                restoreLocation(seqStart28);
                alt0_6 = elem28_1;
            }
        }
        if (alt0_6.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia31 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem28_2 = parse_Expr(trivia31);
            if (elem28_2.isSuccess() && elem28_2.node != null) {
                children.add(elem28_2.node);
            }
            if (elem28_2.isFailure()) {
                restoreLocation(seqStart28);
                alt0_6 = elem28_2;
            }
        }
        if (alt0_6.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem28_3 = matchLiteralCst(")", false);
            if (elem28_3.isSuccess() && elem28_3.node != null) {
                children.add(elem28_3.node);
            }
            if (elem28_3.isFailure()) {
                restoreLocation(seqStart28);
                alt0_6 = elem28_3;
            }
        }
        if (alt0_6.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia33 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem28_4 = parse_SwitchBlock(trivia33);
            if (elem28_4.isSuccess() && elem28_4.node != null) {
                children.add(elem28_4.node);
            }
            if (elem28_4.isFailure()) {
                restoreLocation(seqStart28);
                alt0_6 = elem28_4;
            }
        }
        if (alt0_6.isSuccess()) {
            alt0_6 = CstParseResult.success(null, substring(seqStart28.offset(), pos), location());
        }
        if (alt0_6.isSuccess()) {
            result = alt0_6;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia34 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_7 = parse_QualifiedName(trivia34);
        if (alt0_7.isSuccess() && alt0_7.node != null) {
            children.add(alt0_7.node);
        }
        if (alt0_7.isSuccess()) {
            result = alt0_7;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Primary", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Lambda(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(64, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_LambdaParams(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_1 = matchLiteralCst("->", false);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_2 = null;
            var choiceStart4 = location();
            var savedChildren4 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren4);
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt4_0 = parse_Expr(trivia5);
            if (alt4_0.isSuccess() && alt4_0.node != null) {
                children.add(alt4_0.node);
            }
            if (alt4_0.isSuccess()) {
                elem0_2 = alt4_0;
            } else {
                restoreLocation(choiceStart4);
            children.clear();
            children.addAll(savedChildren4);
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt4_1 = parse_Block(trivia6);
            if (alt4_1.isSuccess() && alt4_1.node != null) {
                children.add(alt4_1.node);
            }
            if (alt4_1.isSuccess()) {
                elem0_2 = alt4_1;
            } else {
                restoreLocation(choiceStart4);
            }
            }
            if (elem0_2 == null) {
                children.clear();
                children.addAll(savedChildren4);
                elem0_2 = CstParseResult.failure("one of alternatives");
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Lambda", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LambdaParams(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(65, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_Identifier(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (alt0_1.isSuccess()) {
            var elem2_0 = matchLiteralCst("(", false);
            if (elem2_0.isSuccess() && elem2_0.node != null) {
                children.add(elem2_0.node);
            }
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_0;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart4 = location();
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem4 = parse_LambdaParam(trivia5);
            if (optElem4.isSuccess() && optElem4.node != null) {
                children.add(optElem4.node);
            }
            var elem2_1 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_1;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem2_2 = CstParseResult.success(null, "", location());
            var zomStart6 = location();
            while (true) {
                var beforeLoc6 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem6 = CstParseResult.success(null, "", location());
                var seqStart8 = location();
                if (zomElem6.isSuccess()) {
                    var elem8_0 = matchLiteralCst(",", false);
                    if (elem8_0.isSuccess() && elem8_0.node != null) {
                        children.add(elem8_0.node);
                    }
                    if (elem8_0.isFailure()) {
                        restoreLocation(seqStart8);
                        zomElem6 = elem8_0;
                    }
                }
                if (zomElem6.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem8_1 = parse_LambdaParam(trivia10);
                    if (elem8_1.isSuccess() && elem8_1.node != null) {
                        children.add(elem8_1.node);
                    }
                    if (elem8_1.isFailure()) {
                        restoreLocation(seqStart8);
                        zomElem6 = elem8_1;
                    }
                }
                if (zomElem6.isSuccess()) {
                    zomElem6 = CstParseResult.success(null, substring(seqStart8.offset(), pos), location());
                }
                if (zomElem6.isFailure() || location().offset() == beforeLoc6.offset()) {
                    restoreLocation(beforeLoc6);
                    break;
                }
            }
            elem2_2 = CstParseResult.success(null, substring(zomStart6.offset(), pos), location());
            if (elem2_2.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_2;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_3 = matchLiteralCst(")", false);
            if (elem2_3.isSuccess() && elem2_3.node != null) {
                children.add(elem2_3.node);
            }
            if (elem2_3.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_3;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "LambdaParams", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LambdaParam(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(66, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                if (!inTokenBoundary) skipWhitespace();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Modifier(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart3 = location();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem3 = parse_Type(trivia4);
            if (optElem3.isSuccess() && optElem3.node != null) {
                children.add(optElem3.node);
            }
            var elem0_1 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_Identifier(trivia5);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "LambdaParam", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Args(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(67, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Expr(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Expr(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Args", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ExprList(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(68, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Expr(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Expr(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "ExprList", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Type(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(69, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = null;
            var choiceStart2 = location();
            var savedChildren2 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren2);
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt2_0 = parse_PrimType(trivia3);
            if (alt2_0.isSuccess() && alt2_0.node != null) {
                children.add(alt2_0.node);
            }
            if (alt2_0.isSuccess()) {
                elem0_0 = alt2_0;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt2_1 = parse_RefType(trivia4);
            if (alt2_1.isSuccess() && alt2_1.node != null) {
                children.add(alt2_1.node);
            }
            if (alt2_1.isSuccess()) {
                elem0_0 = alt2_1;
            } else {
                restoreLocation(choiceStart2);
            }
            }
            if (elem0_0 == null) {
                children.clear();
                children.addAll(savedChildren2);
                elem0_0 = CstParseResult.failure("one of alternatives");
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart5 = location();
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem5 = parse_Dims(trivia6);
            if (optElem5.isSuccess() && optElem5.node != null) {
                children.add(optElem5.node);
            }
            var elem0_1 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Type", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_PrimType(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(70, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_0 = matchLiteralCst("boolean", false);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_1 = matchLiteralCst("byte", false);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_2 = matchLiteralCst("short", false);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_3 = matchLiteralCst("int", false);
        if (alt0_3.isSuccess() && alt0_3.node != null) {
            children.add(alt0_3.node);
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_4 = matchLiteralCst("long", false);
        if (alt0_4.isSuccess() && alt0_4.node != null) {
            children.add(alt0_4.node);
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_5 = matchLiteralCst("float", false);
        if (alt0_5.isSuccess() && alt0_5.node != null) {
            children.add(alt0_5.node);
        }
        if (alt0_5.isSuccess()) {
            result = alt0_5;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_6 = matchLiteralCst("double", false);
        if (alt0_6.isSuccess() && alt0_6.node != null) {
            children.add(alt0_6.node);
        }
        if (alt0_6.isSuccess()) {
            result = alt0_6;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_7 = matchLiteralCst("char", false);
        if (alt0_7.isSuccess() && alt0_7.node != null) {
            children.add(alt0_7.node);
        }
        if (alt0_7.isSuccess()) {
            result = alt0_7;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_8 = matchLiteralCst("void", false);
        if (alt0_8.isSuccess() && alt0_8.node != null) {
            children.add(alt0_8.node);
        }
        if (alt0_8.isSuccess()) {
            result = alt0_8;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "PrimType", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RefType(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(71, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_QualifiedName(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart2 = location();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem2 = parse_TypeArgs(trivia3);
            if (optElem2.isSuccess() && optElem2.node != null) {
                children.add(optElem2.node);
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "RefType", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Dims(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(72, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult oomFirst0 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (oomFirst0.isSuccess()) {
            var elem2_0 = matchLiteralCst("[", false);
            if (elem2_0.isSuccess() && elem2_0.node != null) {
                children.add(elem2_0.node);
            }
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                oomFirst0 = elem2_0;
            }
        }
        if (oomFirst0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_1 = matchLiteralCst("]", false);
            if (elem2_1.isSuccess() && elem2_1.node != null) {
                children.add(elem2_1.node);
            }
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                oomFirst0 = elem2_1;
            }
        }
        if (oomFirst0.isSuccess()) {
            oomFirst0 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        var result = oomFirst0;
        if (oomFirst0.isSuccess()) {
            var oomStart0 = location();
            while (true) {
                var beforeLoc0 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult oomElem0 = CstParseResult.success(null, "", location());
                var seqStart6 = location();
                if (oomElem0.isSuccess()) {
                    var elem6_0 = matchLiteralCst("[", false);
                    if (elem6_0.isSuccess() && elem6_0.node != null) {
                        children.add(elem6_0.node);
                    }
                    if (elem6_0.isFailure()) {
                        restoreLocation(seqStart6);
                        oomElem0 = elem6_0;
                    }
                }
                if (oomElem0.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem6_1 = matchLiteralCst("]", false);
                    if (elem6_1.isSuccess() && elem6_1.node != null) {
                        children.add(elem6_1.node);
                    }
                    if (elem6_1.isFailure()) {
                        restoreLocation(seqStart6);
                        oomElem0 = elem6_1;
                    }
                }
                if (oomElem0.isSuccess()) {
                    oomElem0 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
                }
                if (oomElem0.isFailure() || location().offset() == beforeLoc0.offset()) {
                    restoreLocation(beforeLoc0);
                    break;
                }
            }
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Dims", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeArgs(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(73, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("<", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_TypeArg(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_2 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem3 = CstParseResult.success(null, "", location());
                var seqStart5 = location();
                if (zomElem3.isSuccess()) {
                    var elem5_0 = matchLiteralCst(",", false);
                    if (elem5_0.isSuccess() && elem5_0.node != null) {
                        children.add(elem5_0.node);
                    }
                    if (elem5_0.isFailure()) {
                        restoreLocation(seqStart5);
                        zomElem3 = elem5_0;
                    }
                }
                if (zomElem3.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem5_1 = parse_TypeArg(trivia7);
                    if (elem5_1.isSuccess() && elem5_1.node != null) {
                        children.add(elem5_1.node);
                    }
                    if (elem5_1.isFailure()) {
                        restoreLocation(seqStart5);
                        zomElem3 = elem5_1;
                    }
                }
                if (zomElem3.isSuccess()) {
                    zomElem3 = CstParseResult.success(null, substring(seqStart5.offset(), pos), location());
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_2 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst(">", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "TypeArgs", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeArg(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(74, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_Type(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (alt0_1.isSuccess()) {
            var elem2_0 = matchLiteralCst("?", false);
            if (elem2_0.isSuccess() && elem2_0.node != null) {
                children.add(elem2_0.node);
            }
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_0;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart4 = location();
            CstParseResult optElem4 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (optElem4.isSuccess()) {
                CstParseResult elem6_0 = null;
                var choiceStart8 = location();
                var savedChildren8 = new ArrayList<>(children);
                children.clear();
                children.addAll(savedChildren8);
                var alt8_0 = matchLiteralCst("extends", false);
                if (alt8_0.isSuccess() && alt8_0.node != null) {
                    children.add(alt8_0.node);
                }
                if (alt8_0.isSuccess()) {
                    elem6_0 = alt8_0;
                } else {
                    restoreLocation(choiceStart8);
                children.clear();
                children.addAll(savedChildren8);
                var alt8_1 = matchLiteralCst("super", false);
                if (alt8_1.isSuccess() && alt8_1.node != null) {
                    children.add(alt8_1.node);
                }
                if (alt8_1.isSuccess()) {
                    elem6_0 = alt8_1;
                } else {
                    restoreLocation(choiceStart8);
                }
                }
                if (elem6_0 == null) {
                    children.clear();
                    children.addAll(savedChildren8);
                    elem6_0 = CstParseResult.failure("one of alternatives");
                }
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_0;
                }
            }
            if (optElem4.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem6_1 = parse_Type(trivia11);
                if (elem6_1.isSuccess() && elem6_1.node != null) {
                    children.add(elem6_1.node);
                }
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_1;
                }
            }
            if (optElem4.isSuccess()) {
                optElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            var elem2_1 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_1;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "TypeArg", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_QualifiedName(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(75, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Identifier(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(".", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Identifier(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "QualifiedName", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Identifier(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(76, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var notStart1 = location();
            var savedChildrenNot1 = new ArrayList<>(children);
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var notElem1 = parse_Keyword(trivia2);
            restoreLocation(notStart1);
            children.clear();
            children.addAll(savedChildrenNot1);
            var elem0_0 = notElem1.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var tbStart3 = location();
            inTokenBoundary = true;
            var savedChildrenTb3 = new ArrayList<>(children);
            CstParseResult tbElem3 = CstParseResult.success(null, "", location());
            var seqStart4 = location();
            if (tbElem3.isSuccess()) {
                var elem4_0 = matchCharClassCst("a-zA-Z_$", false, false);
                if (elem4_0.isFailure()) {
                    restoreLocation(seqStart4);
                    tbElem3 = elem4_0;
                }
            }
            if (tbElem3.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult elem4_1 = CstParseResult.success(null, "", location());
                var zomStart6 = location();
                while (true) {
                    var beforeLoc6 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    var zomElem6 = matchCharClassCst("a-zA-Z0-9_$", false, false);
                    if (zomElem6.isFailure() || location().offset() == beforeLoc6.offset()) {
                        restoreLocation(beforeLoc6);
                        break;
                    }
                }
                elem4_1 = CstParseResult.success(null, substring(zomStart6.offset(), pos), location());
                if (elem4_1.isFailure()) {
                    restoreLocation(seqStart4);
                    tbElem3 = elem4_1;
                }
            }
            if (tbElem3.isSuccess()) {
                tbElem3 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
            }
            inTokenBoundary = false;
            children.clear();
            children.addAll(savedChildrenTb3);
            CstParseResult elem0_1;
            if (tbElem3.isSuccess()) {
                var tbText3 = substring(tbStart3.offset(), pos);
                var tbSpan3 = SourceSpan.of(tbStart3, location());
                var tbNode3 = new CstNode.Token(tbSpan3, "token", tbText3, List.of(), List.of());
                children.add(tbNode3);
                elem0_1 = CstParseResult.success(tbNode3, tbText3, location());
            } else {
                elem0_1 = tbElem3;
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Identifier", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Modifier(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(77, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_0 = matchLiteralCst("public", false);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_1 = matchLiteralCst("protected", false);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_2 = matchLiteralCst("private", false);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_3 = matchLiteralCst("static", false);
        if (alt0_3.isSuccess() && alt0_3.node != null) {
            children.add(alt0_3.node);
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_4 = matchLiteralCst("final", false);
        if (alt0_4.isSuccess() && alt0_4.node != null) {
            children.add(alt0_4.node);
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_5 = matchLiteralCst("abstract", false);
        if (alt0_5.isSuccess() && alt0_5.node != null) {
            children.add(alt0_5.node);
        }
        if (alt0_5.isSuccess()) {
            result = alt0_5;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_6 = matchLiteralCst("native", false);
        if (alt0_6.isSuccess() && alt0_6.node != null) {
            children.add(alt0_6.node);
        }
        if (alt0_6.isSuccess()) {
            result = alt0_6;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_7 = matchLiteralCst("synchronized", false);
        if (alt0_7.isSuccess() && alt0_7.node != null) {
            children.add(alt0_7.node);
        }
        if (alt0_7.isSuccess()) {
            result = alt0_7;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_8 = matchLiteralCst("transient", false);
        if (alt0_8.isSuccess() && alt0_8.node != null) {
            children.add(alt0_8.node);
        }
        if (alt0_8.isSuccess()) {
            result = alt0_8;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_9 = matchLiteralCst("volatile", false);
        if (alt0_9.isSuccess() && alt0_9.node != null) {
            children.add(alt0_9.node);
        }
        if (alt0_9.isSuccess()) {
            result = alt0_9;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_10 = matchLiteralCst("strictfp", false);
        if (alt0_10.isSuccess() && alt0_10.node != null) {
            children.add(alt0_10.node);
        }
        if (alt0_10.isSuccess()) {
            result = alt0_10;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_11 = matchLiteralCst("default", false);
        if (alt0_11.isSuccess() && alt0_11.node != null) {
            children.add(alt0_11.node);
        }
        if (alt0_11.isSuccess()) {
            result = alt0_11;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_12 = matchLiteralCst("sealed", false);
        if (alt0_12.isSuccess() && alt0_12.node != null) {
            children.add(alt0_12.node);
        }
        if (alt0_12.isSuccess()) {
            result = alt0_12;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_13 = matchLiteralCst("non-sealed", false);
        if (alt0_13.isSuccess() && alt0_13.node != null) {
            children.add(alt0_13.node);
        }
        if (alt0_13.isSuccess()) {
            result = alt0_13;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Modifier", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Annotation(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(78, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("@", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_QualifiedName(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart3 = location();
            CstParseResult optElem3 = CstParseResult.success(null, "", location());
            var seqStart5 = location();
            if (optElem3.isSuccess()) {
                var elem5_0 = matchLiteralCst("(", false);
                if (elem5_0.isSuccess() && elem5_0.node != null) {
                    children.add(elem5_0.node);
                }
                if (elem5_0.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_0;
                }
            }
            if (optElem3.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var optStart7 = location();
                var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem7 = parse_AnnotationValue(trivia8);
                if (optElem7.isSuccess() && optElem7.node != null) {
                    children.add(optElem7.node);
                }
                var elem5_1 = optElem7.isSuccess() ? optElem7 : CstParseResult.success(null, "", location());
                if (optElem7.isFailure()) {
                    restoreLocation(optStart7);
                }
                if (elem5_1.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_1;
                }
            }
            if (optElem3.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem5_2 = matchLiteralCst(")", false);
                if (elem5_2.isSuccess() && elem5_2.node != null) {
                    children.add(elem5_2.node);
                }
                if (elem5_2.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_2;
                }
            }
            if (optElem3.isSuccess()) {
                optElem3 = CstParseResult.success(null, substring(seqStart5.offset(), pos), location());
            }
            var elem0_2 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Annotation", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_AnnotationValue(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(79, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_0 = parse_Identifier(trivia2);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_1 = matchLiteralCst("=", false);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_2 = parse_AnnotationElem(trivia4);
            if (elem1_2.isSuccess() && elem1_2.node != null) {
                children.add(elem1_2.node);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem1_3 = CstParseResult.success(null, "", location());
            var zomStart5 = location();
            while (true) {
                var beforeLoc5 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem5 = CstParseResult.success(null, "", location());
                var seqStart7 = location();
                if (zomElem5.isSuccess()) {
                    var elem7_0 = matchLiteralCst(",", false);
                    if (elem7_0.isSuccess() && elem7_0.node != null) {
                        children.add(elem7_0.node);
                    }
                    if (elem7_0.isFailure()) {
                        restoreLocation(seqStart7);
                        zomElem5 = elem7_0;
                    }
                }
                if (zomElem5.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem7_1 = parse_Identifier(trivia9);
                    if (elem7_1.isSuccess() && elem7_1.node != null) {
                        children.add(elem7_1.node);
                    }
                    if (elem7_1.isFailure()) {
                        restoreLocation(seqStart7);
                        zomElem5 = elem7_1;
                    }
                }
                if (zomElem5.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem7_2 = matchLiteralCst("=", false);
                    if (elem7_2.isSuccess() && elem7_2.node != null) {
                        children.add(elem7_2.node);
                    }
                    if (elem7_2.isFailure()) {
                        restoreLocation(seqStart7);
                        zomElem5 = elem7_2;
                    }
                }
                if (zomElem5.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem7_3 = parse_AnnotationElem(trivia11);
                    if (elem7_3.isSuccess() && elem7_3.node != null) {
                        children.add(elem7_3.node);
                    }
                    if (elem7_3.isFailure()) {
                        restoreLocation(seqStart7);
                        zomElem5 = elem7_3;
                    }
                }
                if (zomElem5.isSuccess()) {
                    zomElem5 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
                }
                if (zomElem5.isFailure() || location().offset() == beforeLoc5.offset()) {
                    restoreLocation(beforeLoc5);
                    break;
                }
            }
            elem1_3 = CstParseResult.success(null, substring(zomStart5.offset(), pos), location());
            if (elem1_3.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_3;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_AnnotationElem(trivia12);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "AnnotationValue", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_AnnotationElem(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(80, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_Annotation(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (alt0_1.isSuccess()) {
            var elem2_0 = matchLiteralCst("{", false);
            if (elem2_0.isSuccess() && elem2_0.node != null) {
                children.add(elem2_0.node);
            }
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_0;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart4 = location();
            CstParseResult optElem4 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (optElem4.isSuccess()) {
                var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem6_0 = parse_AnnotationElem(trivia7);
                if (elem6_0.isSuccess() && elem6_0.node != null) {
                    children.add(elem6_0.node);
                }
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_0;
                }
            }
            if (optElem4.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult elem6_1 = CstParseResult.success(null, "", location());
                var zomStart8 = location();
                while (true) {
                    var beforeLoc8 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    CstParseResult zomElem8 = CstParseResult.success(null, "", location());
                    var seqStart10 = location();
                    if (zomElem8.isSuccess()) {
                        var elem10_0 = matchLiteralCst(",", false);
                        if (elem10_0.isSuccess() && elem10_0.node != null) {
                            children.add(elem10_0.node);
                        }
                        if (elem10_0.isFailure()) {
                            restoreLocation(seqStart10);
                            zomElem8 = elem10_0;
                        }
                    }
                    if (zomElem8.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                        var elem10_1 = parse_AnnotationElem(trivia12);
                        if (elem10_1.isSuccess() && elem10_1.node != null) {
                            children.add(elem10_1.node);
                        }
                        if (elem10_1.isFailure()) {
                            restoreLocation(seqStart10);
                            zomElem8 = elem10_1;
                        }
                    }
                    if (zomElem8.isSuccess()) {
                        zomElem8 = CstParseResult.success(null, substring(seqStart10.offset(), pos), location());
                    }
                    if (zomElem8.isFailure() || location().offset() == beforeLoc8.offset()) {
                        restoreLocation(beforeLoc8);
                        break;
                    }
                }
                elem6_1 = CstParseResult.success(null, substring(zomStart8.offset(), pos), location());
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_1;
                }
            }
            if (optElem4.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var optStart13 = location();
                var optElem13 = matchLiteralCst(",", false);
                if (optElem13.isSuccess() && optElem13.node != null) {
                    children.add(optElem13.node);
                }
                var elem6_2 = optElem13.isSuccess() ? optElem13 : CstParseResult.success(null, "", location());
                if (optElem13.isFailure()) {
                    restoreLocation(optStart13);
                }
                if (elem6_2.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_2;
                }
            }
            if (optElem4.isSuccess()) {
                optElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            var elem2_1 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_1;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_2 = matchLiteralCst("}", false);
            if (elem2_2.isSuccess() && elem2_2.node != null) {
                children.add(elem2_2.node);
            }
            if (elem2_2.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_2;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia16 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_2 = parse_Ternary(trivia16);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "AnnotationElem", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Literal(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(81, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_0 = matchLiteralCst("null", false);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_1 = matchLiteralCst("true", false);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_2 = matchLiteralCst("false", false);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_3 = parse_CharLit(trivia4);
        if (alt0_3.isSuccess() && alt0_3.node != null) {
            children.add(alt0_3.node);
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_4 = parse_StringLit(trivia5);
        if (alt0_4.isSuccess() && alt0_4.node != null) {
            children.add(alt0_4.node);
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_5 = parse_NumLit(trivia6);
        if (alt0_5.isSuccess() && alt0_5.node != null) {
            children.add(alt0_5.node);
        }
        if (alt0_5.isSuccess()) {
            result = alt0_5;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Literal", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_CharLit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(82, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        var tbStart0 = location();
        inTokenBoundary = true;
        var savedChildrenTb0 = new ArrayList<>(children);
        CstParseResult tbElem0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (tbElem0.isSuccess()) {
            var elem1_0 = matchLiteralCst("'", false);
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_0;
            }
        }
        if (tbElem0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem1_1 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem3 = null;
                var choiceStart5 = location();
                var alt5_0 = matchCharClassCst("'\\\\", true, false);
                if (alt5_0.isSuccess()) {
                    zomElem3 = alt5_0;
                } else {
                    restoreLocation(choiceStart5);
                CstParseResult alt5_1 = CstParseResult.success(null, "", location());
                var seqStart7 = location();
                if (alt5_1.isSuccess()) {
                    var elem7_0 = matchLiteralCst("\\", false);
                    if (elem7_0.isFailure()) {
                        restoreLocation(seqStart7);
                        alt5_1 = elem7_0;
                    }
                }
                if (alt5_1.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem7_1 = matchAnyCst();
                    if (elem7_1.isFailure()) {
                        restoreLocation(seqStart7);
                        alt5_1 = elem7_1;
                    }
                }
                if (alt5_1.isSuccess()) {
                    alt5_1 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
                }
                if (alt5_1.isSuccess()) {
                    zomElem3 = alt5_1;
                } else {
                    restoreLocation(choiceStart5);
                }
                }
                if (zomElem3 == null) {
                    zomElem3 = CstParseResult.failure("one of alternatives");
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem1_1 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_1;
            }
        }
        if (tbElem0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_2 = matchLiteralCst("'", false);
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_2;
            }
        }
        if (tbElem0.isSuccess()) {
            tbElem0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb0);
        CstParseResult result;
        if (tbElem0.isSuccess()) {
            var tbText0 = substring(tbStart0.offset(), pos);
            var tbSpan0 = SourceSpan.of(tbStart0, location());
            var tbNode0 = new CstNode.Token(tbSpan0, "token", tbText0, List.of(), List.of());
            children.add(tbNode0);
            result = CstParseResult.success(tbNode0, tbText0, location());
        } else {
            result = tbElem0;
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.Token(span, "CharLit", result.text, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_StringLit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(83, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var tbStart1 = location();
        inTokenBoundary = true;
        var savedChildrenTb1 = new ArrayList<>(children);
        CstParseResult tbElem1 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (tbElem1.isSuccess()) {
            var elem2_0 = matchLiteralCst("\"", false);
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_0;
            }
        }
        if (tbElem1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem2_1 = CstParseResult.success(null, "", location());
            var zomStart4 = location();
            while (true) {
                var beforeLoc4 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem4 = null;
                var choiceStart6 = location();
                var alt6_0 = matchCharClassCst("\"\\\\", true, false);
                if (alt6_0.isSuccess()) {
                    zomElem4 = alt6_0;
                } else {
                    restoreLocation(choiceStart6);
                CstParseResult alt6_1 = CstParseResult.success(null, "", location());
                var seqStart8 = location();
                if (alt6_1.isSuccess()) {
                    var elem8_0 = matchLiteralCst("\\", false);
                    if (elem8_0.isFailure()) {
                        restoreLocation(seqStart8);
                        alt6_1 = elem8_0;
                    }
                }
                if (alt6_1.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem8_1 = matchAnyCst();
                    if (elem8_1.isFailure()) {
                        restoreLocation(seqStart8);
                        alt6_1 = elem8_1;
                    }
                }
                if (alt6_1.isSuccess()) {
                    alt6_1 = CstParseResult.success(null, substring(seqStart8.offset(), pos), location());
                }
                if (alt6_1.isSuccess()) {
                    zomElem4 = alt6_1;
                } else {
                    restoreLocation(choiceStart6);
                }
                }
                if (zomElem4 == null) {
                    zomElem4 = CstParseResult.failure("one of alternatives");
                }
                if (zomElem4.isFailure() || location().offset() == beforeLoc4.offset()) {
                    restoreLocation(beforeLoc4);
                    break;
                }
            }
            elem2_1 = CstParseResult.success(null, substring(zomStart4.offset(), pos), location());
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_1;
            }
        }
        if (tbElem1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_2 = matchLiteralCst("\"", false);
            if (elem2_2.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_2;
            }
        }
        if (tbElem1.isSuccess()) {
            tbElem1 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb1);
        CstParseResult alt0_0;
        if (tbElem1.isSuccess()) {
            var tbText1 = substring(tbStart1.offset(), pos);
            var tbSpan1 = SourceSpan.of(tbStart1, location());
            var tbNode1 = new CstNode.Token(tbSpan1, "token", tbText1, List.of(), List.of());
            children.add(tbNode1);
            alt0_0 = CstParseResult.success(tbNode1, tbText1, location());
        } else {
            alt0_0 = tbElem1;
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var tbStart12 = location();
        inTokenBoundary = true;
        var savedChildrenTb12 = new ArrayList<>(children);
        CstParseResult tbElem12 = CstParseResult.success(null, "", location());
        var seqStart13 = location();
        if (tbElem12.isSuccess()) {
            var elem13_0 = matchLiteralCst("\"\"\"", false);
            if (elem13_0.isFailure()) {
                restoreLocation(seqStart13);
                tbElem12 = elem13_0;
            }
        }
        if (tbElem12.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult elem13_1 = CstParseResult.success(null, "", location());
            var zomStart15 = location();
            while (true) {
                var beforeLoc15 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem15 = CstParseResult.success(null, "", location());
                var seqStart17 = location();
                if (zomElem15.isSuccess()) {
                    var notStart18 = location();
                    var notElem18 = matchLiteralCst("\"\"\"", false);
                    restoreLocation(notStart18);
                    var elem17_0 = notElem18.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                    if (elem17_0.isFailure()) {
                        restoreLocation(seqStart17);
                        zomElem15 = elem17_0;
                    }
                }
                if (zomElem15.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem17_1 = matchAnyCst();
                    if (elem17_1.isFailure()) {
                        restoreLocation(seqStart17);
                        zomElem15 = elem17_1;
                    }
                }
                if (zomElem15.isSuccess()) {
                    zomElem15 = CstParseResult.success(null, substring(seqStart17.offset(), pos), location());
                }
                if (zomElem15.isFailure() || location().offset() == beforeLoc15.offset()) {
                    restoreLocation(beforeLoc15);
                    break;
                }
            }
            elem13_1 = CstParseResult.success(null, substring(zomStart15.offset(), pos), location());
            if (elem13_1.isFailure()) {
                restoreLocation(seqStart13);
                tbElem12 = elem13_1;
            }
        }
        if (tbElem12.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem13_2 = matchLiteralCst("\"\"\"", false);
            if (elem13_2.isFailure()) {
                restoreLocation(seqStart13);
                tbElem12 = elem13_2;
            }
        }
        if (tbElem12.isSuccess()) {
            tbElem12 = CstParseResult.success(null, substring(seqStart13.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb12);
        CstParseResult alt0_1;
        if (tbElem12.isSuccess()) {
            var tbText12 = substring(tbStart12.offset(), pos);
            var tbSpan12 = SourceSpan.of(tbStart12, location());
            var tbNode12 = new CstNode.Token(tbSpan12, "token", tbText12, List.of(), List.of());
            children.add(tbNode12);
            alt0_1 = CstParseResult.success(tbNode12, tbText12, location());
        } else {
            alt0_1 = tbElem12;
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "StringLit", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_NumLit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(84, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var tbStart1 = location();
        inTokenBoundary = true;
        var savedChildrenTb1 = new ArrayList<>(children);
        CstParseResult tbElem1 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (tbElem1.isSuccess()) {
            var elem2_0 = matchLiteralCst("0", false);
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_0;
            }
        }
        if (tbElem1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_1 = matchCharClassCst("xX", false, false);
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_1;
            }
        }
        if (tbElem1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var oomFirst5 = matchCharClassCst("0-9a-fA-F_", false, false);
            var elem2_2 = oomFirst5;
            if (oomFirst5.isSuccess()) {
                var oomStart5 = location();
                while (true) {
                    var beforeLoc5 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    var oomElem5 = matchCharClassCst("0-9a-fA-F_", false, false);
                    if (oomElem5.isFailure() || location().offset() == beforeLoc5.offset()) {
                        restoreLocation(beforeLoc5);
                        break;
                    }
                }
            }
            if (elem2_2.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_2;
            }
        }
        if (tbElem1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart8 = location();
            var optElem8 = matchCharClassCst("lL", false, false);
            var elem2_3 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
            if (optElem8.isFailure()) {
                restoreLocation(optStart8);
            }
            if (elem2_3.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_3;
            }
        }
        if (tbElem1.isSuccess()) {
            tbElem1 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb1);
        CstParseResult alt0_0;
        if (tbElem1.isSuccess()) {
            var tbText1 = substring(tbStart1.offset(), pos);
            var tbSpan1 = SourceSpan.of(tbStart1, location());
            var tbNode1 = new CstNode.Token(tbSpan1, "token", tbText1, List.of(), List.of());
            children.add(tbNode1);
            alt0_0 = CstParseResult.success(tbNode1, tbText1, location());
        } else {
            alt0_0 = tbElem1;
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var tbStart10 = location();
        inTokenBoundary = true;
        var savedChildrenTb10 = new ArrayList<>(children);
        CstParseResult tbElem10 = CstParseResult.success(null, "", location());
        var seqStart11 = location();
        if (tbElem10.isSuccess()) {
            var elem11_0 = matchLiteralCst("0", false);
            if (elem11_0.isFailure()) {
                restoreLocation(seqStart11);
                tbElem10 = elem11_0;
            }
        }
        if (tbElem10.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem11_1 = matchCharClassCst("bB", false, false);
            if (elem11_1.isFailure()) {
                restoreLocation(seqStart11);
                tbElem10 = elem11_1;
            }
        }
        if (tbElem10.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var oomFirst14 = matchCharClassCst("01_", false, false);
            var elem11_2 = oomFirst14;
            if (oomFirst14.isSuccess()) {
                var oomStart14 = location();
                while (true) {
                    var beforeLoc14 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    var oomElem14 = matchCharClassCst("01_", false, false);
                    if (oomElem14.isFailure() || location().offset() == beforeLoc14.offset()) {
                        restoreLocation(beforeLoc14);
                        break;
                    }
                }
            }
            if (elem11_2.isFailure()) {
                restoreLocation(seqStart11);
                tbElem10 = elem11_2;
            }
        }
        if (tbElem10.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart17 = location();
            var optElem17 = matchCharClassCst("lL", false, false);
            var elem11_3 = optElem17.isSuccess() ? optElem17 : CstParseResult.success(null, "", location());
            if (optElem17.isFailure()) {
                restoreLocation(optStart17);
            }
            if (elem11_3.isFailure()) {
                restoreLocation(seqStart11);
                tbElem10 = elem11_3;
            }
        }
        if (tbElem10.isSuccess()) {
            tbElem10 = CstParseResult.success(null, substring(seqStart11.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb10);
        CstParseResult alt0_1;
        if (tbElem10.isSuccess()) {
            var tbText10 = substring(tbStart10.offset(), pos);
            var tbSpan10 = SourceSpan.of(tbStart10, location());
            var tbNode10 = new CstNode.Token(tbSpan10, "token", tbText10, List.of(), List.of());
            children.add(tbNode10);
            alt0_1 = CstParseResult.success(tbNode10, tbText10, location());
        } else {
            alt0_1 = tbElem10;
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var tbStart19 = location();
        inTokenBoundary = true;
        var savedChildrenTb19 = new ArrayList<>(children);
        CstParseResult tbElem19 = CstParseResult.success(null, "", location());
        var seqStart20 = location();
        if (tbElem19.isSuccess()) {
            var oomFirst21 = matchCharClassCst("0-9", false, false);
            var elem20_0 = oomFirst21;
            if (oomFirst21.isSuccess()) {
                var oomStart21 = location();
                while (true) {
                    var beforeLoc21 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    var oomElem21 = matchCharClassCst("0-9", false, false);
                    if (oomElem21.isFailure() || location().offset() == beforeLoc21.offset()) {
                        restoreLocation(beforeLoc21);
                        break;
                    }
                }
            }
            if (elem20_0.isFailure()) {
                restoreLocation(seqStart20);
                tbElem19 = elem20_0;
            }
        }
        if (tbElem19.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart24 = location();
            CstParseResult optElem24 = CstParseResult.success(null, "", location());
            var seqStart26 = location();
            if (optElem24.isSuccess()) {
                var elem26_0 = matchLiteralCst(".", false);
                if (elem26_0.isFailure()) {
                    restoreLocation(seqStart26);
                    optElem24 = elem26_0;
                }
            }
            if (optElem24.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult elem26_1 = CstParseResult.success(null, "", location());
                var zomStart28 = location();
                while (true) {
                    var beforeLoc28 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    var zomElem28 = matchCharClassCst("0-9", false, false);
                    if (zomElem28.isFailure() || location().offset() == beforeLoc28.offset()) {
                        restoreLocation(beforeLoc28);
                        break;
                    }
                }
                elem26_1 = CstParseResult.success(null, substring(zomStart28.offset(), pos), location());
                if (elem26_1.isFailure()) {
                    restoreLocation(seqStart26);
                    optElem24 = elem26_1;
                }
            }
            if (optElem24.isSuccess()) {
                optElem24 = CstParseResult.success(null, substring(seqStart26.offset(), pos), location());
            }
            var elem20_1 = optElem24.isSuccess() ? optElem24 : CstParseResult.success(null, "", location());
            if (optElem24.isFailure()) {
                restoreLocation(optStart24);
            }
            if (elem20_1.isFailure()) {
                restoreLocation(seqStart20);
                tbElem19 = elem20_1;
            }
        }
        if (tbElem19.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart30 = location();
            CstParseResult optElem30 = CstParseResult.success(null, "", location());
            var seqStart32 = location();
            if (optElem30.isSuccess()) {
                var elem32_0 = matchCharClassCst("eE", false, false);
                if (elem32_0.isFailure()) {
                    restoreLocation(seqStart32);
                    optElem30 = elem32_0;
                }
            }
            if (optElem30.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var optStart34 = location();
                var optElem34 = matchCharClassCst("+\\-", false, false);
                var elem32_1 = optElem34.isSuccess() ? optElem34 : CstParseResult.success(null, "", location());
                if (optElem34.isFailure()) {
                    restoreLocation(optStart34);
                }
                if (elem32_1.isFailure()) {
                    restoreLocation(seqStart32);
                    optElem30 = elem32_1;
                }
            }
            if (optElem30.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var oomFirst36 = matchCharClassCst("0-9", false, false);
                var elem32_2 = oomFirst36;
                if (oomFirst36.isSuccess()) {
                    var oomStart36 = location();
                    while (true) {
                        var beforeLoc36 = location();
                        if (!inTokenBoundary) skipWhitespace();
                        var oomElem36 = matchCharClassCst("0-9", false, false);
                        if (oomElem36.isFailure() || location().offset() == beforeLoc36.offset()) {
                            restoreLocation(beforeLoc36);
                            break;
                        }
                    }
                }
                if (elem32_2.isFailure()) {
                    restoreLocation(seqStart32);
                    optElem30 = elem32_2;
                }
            }
            if (optElem30.isSuccess()) {
                optElem30 = CstParseResult.success(null, substring(seqStart32.offset(), pos), location());
            }
            var elem20_2 = optElem30.isSuccess() ? optElem30 : CstParseResult.success(null, "", location());
            if (optElem30.isFailure()) {
                restoreLocation(optStart30);
            }
            if (elem20_2.isFailure()) {
                restoreLocation(seqStart20);
                tbElem19 = elem20_2;
            }
        }
        if (tbElem19.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart39 = location();
            var optElem39 = matchCharClassCst("fFdDlL", false, false);
            var elem20_3 = optElem39.isSuccess() ? optElem39 : CstParseResult.success(null, "", location());
            if (optElem39.isFailure()) {
                restoreLocation(optStart39);
            }
            if (elem20_3.isFailure()) {
                restoreLocation(seqStart20);
                tbElem19 = elem20_3;
            }
        }
        if (tbElem19.isSuccess()) {
            tbElem19 = CstParseResult.success(null, substring(seqStart20.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb19);
        CstParseResult alt0_2;
        if (tbElem19.isSuccess()) {
            var tbText19 = substring(tbStart19.offset(), pos);
            var tbSpan19 = SourceSpan.of(tbStart19, location());
            var tbNode19 = new CstNode.Token(tbSpan19, "token", tbText19, List.of(), List.of());
            children.add(tbNode19);
            alt0_2 = CstParseResult.success(tbNode19, tbText19, location());
        } else {
            alt0_2 = tbElem19;
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var tbStart41 = location();
        inTokenBoundary = true;
        var savedChildrenTb41 = new ArrayList<>(children);
        CstParseResult tbElem41 = CstParseResult.success(null, "", location());
        var seqStart42 = location();
        if (tbElem41.isSuccess()) {
            var elem42_0 = matchLiteralCst(".", false);
            if (elem42_0.isFailure()) {
                restoreLocation(seqStart42);
                tbElem41 = elem42_0;
            }
        }
        if (tbElem41.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var oomFirst44 = matchCharClassCst("0-9", false, false);
            var elem42_1 = oomFirst44;
            if (oomFirst44.isSuccess()) {
                var oomStart44 = location();
                while (true) {
                    var beforeLoc44 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    var oomElem44 = matchCharClassCst("0-9", false, false);
                    if (oomElem44.isFailure() || location().offset() == beforeLoc44.offset()) {
                        restoreLocation(beforeLoc44);
                        break;
                    }
                }
            }
            if (elem42_1.isFailure()) {
                restoreLocation(seqStart42);
                tbElem41 = elem42_1;
            }
        }
        if (tbElem41.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart47 = location();
            CstParseResult optElem47 = CstParseResult.success(null, "", location());
            var seqStart49 = location();
            if (optElem47.isSuccess()) {
                var elem49_0 = matchCharClassCst("eE", false, false);
                if (elem49_0.isFailure()) {
                    restoreLocation(seqStart49);
                    optElem47 = elem49_0;
                }
            }
            if (optElem47.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var optStart51 = location();
                var optElem51 = matchCharClassCst("+\\-", false, false);
                var elem49_1 = optElem51.isSuccess() ? optElem51 : CstParseResult.success(null, "", location());
                if (optElem51.isFailure()) {
                    restoreLocation(optStart51);
                }
                if (elem49_1.isFailure()) {
                    restoreLocation(seqStart49);
                    optElem47 = elem49_1;
                }
            }
            if (optElem47.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var oomFirst53 = matchCharClassCst("0-9", false, false);
                var elem49_2 = oomFirst53;
                if (oomFirst53.isSuccess()) {
                    var oomStart53 = location();
                    while (true) {
                        var beforeLoc53 = location();
                        if (!inTokenBoundary) skipWhitespace();
                        var oomElem53 = matchCharClassCst("0-9", false, false);
                        if (oomElem53.isFailure() || location().offset() == beforeLoc53.offset()) {
                            restoreLocation(beforeLoc53);
                            break;
                        }
                    }
                }
                if (elem49_2.isFailure()) {
                    restoreLocation(seqStart49);
                    optElem47 = elem49_2;
                }
            }
            if (optElem47.isSuccess()) {
                optElem47 = CstParseResult.success(null, substring(seqStart49.offset(), pos), location());
            }
            var elem42_2 = optElem47.isSuccess() ? optElem47 : CstParseResult.success(null, "", location());
            if (optElem47.isFailure()) {
                restoreLocation(optStart47);
            }
            if (elem42_2.isFailure()) {
                restoreLocation(seqStart42);
                tbElem41 = elem42_2;
            }
        }
        if (tbElem41.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var optStart56 = location();
            var optElem56 = matchCharClassCst("fFdD", false, false);
            var elem42_3 = optElem56.isSuccess() ? optElem56 : CstParseResult.success(null, "", location());
            if (optElem56.isFailure()) {
                restoreLocation(optStart56);
            }
            if (elem42_3.isFailure()) {
                restoreLocation(seqStart42);
                tbElem41 = elem42_3;
            }
        }
        if (tbElem41.isSuccess()) {
            tbElem41 = CstParseResult.success(null, substring(seqStart42.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb41);
        CstParseResult alt0_3;
        if (tbElem41.isSuccess()) {
            var tbText41 = substring(tbStart41.offset(), pos);
            var tbSpan41 = SourceSpan.of(tbStart41, location());
            var tbNode41 = new CstNode.Token(tbSpan41, "token", tbText41, List.of(), List.of());
            children.add(tbNode41);
            alt0_3 = CstParseResult.success(tbNode41, tbText41, location());
        } else {
            alt0_3 = tbElem41;
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "NumLit", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Keyword(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(85, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = null;
            var choiceStart2 = location();
            var savedChildren2 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_0 = matchLiteralCst("abstract", false);
            if (alt2_0.isSuccess() && alt2_0.node != null) {
                children.add(alt2_0.node);
            }
            if (alt2_0.isSuccess()) {
                elem0_0 = alt2_0;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_1 = matchLiteralCst("assert", false);
            if (alt2_1.isSuccess() && alt2_1.node != null) {
                children.add(alt2_1.node);
            }
            if (alt2_1.isSuccess()) {
                elem0_0 = alt2_1;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_2 = matchLiteralCst("boolean", false);
            if (alt2_2.isSuccess() && alt2_2.node != null) {
                children.add(alt2_2.node);
            }
            if (alt2_2.isSuccess()) {
                elem0_0 = alt2_2;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_3 = matchLiteralCst("break", false);
            if (alt2_3.isSuccess() && alt2_3.node != null) {
                children.add(alt2_3.node);
            }
            if (alt2_3.isSuccess()) {
                elem0_0 = alt2_3;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_4 = matchLiteralCst("byte", false);
            if (alt2_4.isSuccess() && alt2_4.node != null) {
                children.add(alt2_4.node);
            }
            if (alt2_4.isSuccess()) {
                elem0_0 = alt2_4;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_5 = matchLiteralCst("case", false);
            if (alt2_5.isSuccess() && alt2_5.node != null) {
                children.add(alt2_5.node);
            }
            if (alt2_5.isSuccess()) {
                elem0_0 = alt2_5;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_6 = matchLiteralCst("catch", false);
            if (alt2_6.isSuccess() && alt2_6.node != null) {
                children.add(alt2_6.node);
            }
            if (alt2_6.isSuccess()) {
                elem0_0 = alt2_6;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_7 = matchLiteralCst("char", false);
            if (alt2_7.isSuccess() && alt2_7.node != null) {
                children.add(alt2_7.node);
            }
            if (alt2_7.isSuccess()) {
                elem0_0 = alt2_7;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_8 = matchLiteralCst("class", false);
            if (alt2_8.isSuccess() && alt2_8.node != null) {
                children.add(alt2_8.node);
            }
            if (alt2_8.isSuccess()) {
                elem0_0 = alt2_8;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_9 = matchLiteralCst("const", false);
            if (alt2_9.isSuccess() && alt2_9.node != null) {
                children.add(alt2_9.node);
            }
            if (alt2_9.isSuccess()) {
                elem0_0 = alt2_9;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_10 = matchLiteralCst("continue", false);
            if (alt2_10.isSuccess() && alt2_10.node != null) {
                children.add(alt2_10.node);
            }
            if (alt2_10.isSuccess()) {
                elem0_0 = alt2_10;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_11 = matchLiteralCst("default", false);
            if (alt2_11.isSuccess() && alt2_11.node != null) {
                children.add(alt2_11.node);
            }
            if (alt2_11.isSuccess()) {
                elem0_0 = alt2_11;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_12 = matchLiteralCst("do", false);
            if (alt2_12.isSuccess() && alt2_12.node != null) {
                children.add(alt2_12.node);
            }
            if (alt2_12.isSuccess()) {
                elem0_0 = alt2_12;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_13 = matchLiteralCst("double", false);
            if (alt2_13.isSuccess() && alt2_13.node != null) {
                children.add(alt2_13.node);
            }
            if (alt2_13.isSuccess()) {
                elem0_0 = alt2_13;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_14 = matchLiteralCst("else", false);
            if (alt2_14.isSuccess() && alt2_14.node != null) {
                children.add(alt2_14.node);
            }
            if (alt2_14.isSuccess()) {
                elem0_0 = alt2_14;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_15 = matchLiteralCst("enum", false);
            if (alt2_15.isSuccess() && alt2_15.node != null) {
                children.add(alt2_15.node);
            }
            if (alt2_15.isSuccess()) {
                elem0_0 = alt2_15;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_16 = matchLiteralCst("extends", false);
            if (alt2_16.isSuccess() && alt2_16.node != null) {
                children.add(alt2_16.node);
            }
            if (alt2_16.isSuccess()) {
                elem0_0 = alt2_16;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_17 = matchLiteralCst("false", false);
            if (alt2_17.isSuccess() && alt2_17.node != null) {
                children.add(alt2_17.node);
            }
            if (alt2_17.isSuccess()) {
                elem0_0 = alt2_17;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_18 = matchLiteralCst("final", false);
            if (alt2_18.isSuccess() && alt2_18.node != null) {
                children.add(alt2_18.node);
            }
            if (alt2_18.isSuccess()) {
                elem0_0 = alt2_18;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_19 = matchLiteralCst("finally", false);
            if (alt2_19.isSuccess() && alt2_19.node != null) {
                children.add(alt2_19.node);
            }
            if (alt2_19.isSuccess()) {
                elem0_0 = alt2_19;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_20 = matchLiteralCst("float", false);
            if (alt2_20.isSuccess() && alt2_20.node != null) {
                children.add(alt2_20.node);
            }
            if (alt2_20.isSuccess()) {
                elem0_0 = alt2_20;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_21 = matchLiteralCst("for", false);
            if (alt2_21.isSuccess() && alt2_21.node != null) {
                children.add(alt2_21.node);
            }
            if (alt2_21.isSuccess()) {
                elem0_0 = alt2_21;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_22 = matchLiteralCst("goto", false);
            if (alt2_22.isSuccess() && alt2_22.node != null) {
                children.add(alt2_22.node);
            }
            if (alt2_22.isSuccess()) {
                elem0_0 = alt2_22;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_23 = matchLiteralCst("if", false);
            if (alt2_23.isSuccess() && alt2_23.node != null) {
                children.add(alt2_23.node);
            }
            if (alt2_23.isSuccess()) {
                elem0_0 = alt2_23;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_24 = matchLiteralCst("implements", false);
            if (alt2_24.isSuccess() && alt2_24.node != null) {
                children.add(alt2_24.node);
            }
            if (alt2_24.isSuccess()) {
                elem0_0 = alt2_24;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_25 = matchLiteralCst("import", false);
            if (alt2_25.isSuccess() && alt2_25.node != null) {
                children.add(alt2_25.node);
            }
            if (alt2_25.isSuccess()) {
                elem0_0 = alt2_25;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_26 = matchLiteralCst("instanceof", false);
            if (alt2_26.isSuccess() && alt2_26.node != null) {
                children.add(alt2_26.node);
            }
            if (alt2_26.isSuccess()) {
                elem0_0 = alt2_26;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_27 = matchLiteralCst("int", false);
            if (alt2_27.isSuccess() && alt2_27.node != null) {
                children.add(alt2_27.node);
            }
            if (alt2_27.isSuccess()) {
                elem0_0 = alt2_27;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_28 = matchLiteralCst("interface", false);
            if (alt2_28.isSuccess() && alt2_28.node != null) {
                children.add(alt2_28.node);
            }
            if (alt2_28.isSuccess()) {
                elem0_0 = alt2_28;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_29 = matchLiteralCst("long", false);
            if (alt2_29.isSuccess() && alt2_29.node != null) {
                children.add(alt2_29.node);
            }
            if (alt2_29.isSuccess()) {
                elem0_0 = alt2_29;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_30 = matchLiteralCst("native", false);
            if (alt2_30.isSuccess() && alt2_30.node != null) {
                children.add(alt2_30.node);
            }
            if (alt2_30.isSuccess()) {
                elem0_0 = alt2_30;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_31 = matchLiteralCst("new", false);
            if (alt2_31.isSuccess() && alt2_31.node != null) {
                children.add(alt2_31.node);
            }
            if (alt2_31.isSuccess()) {
                elem0_0 = alt2_31;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_32 = matchLiteralCst("non-sealed", false);
            if (alt2_32.isSuccess() && alt2_32.node != null) {
                children.add(alt2_32.node);
            }
            if (alt2_32.isSuccess()) {
                elem0_0 = alt2_32;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_33 = matchLiteralCst("null", false);
            if (alt2_33.isSuccess() && alt2_33.node != null) {
                children.add(alt2_33.node);
            }
            if (alt2_33.isSuccess()) {
                elem0_0 = alt2_33;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_34 = matchLiteralCst("package", false);
            if (alt2_34.isSuccess() && alt2_34.node != null) {
                children.add(alt2_34.node);
            }
            if (alt2_34.isSuccess()) {
                elem0_0 = alt2_34;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_35 = matchLiteralCst("permits", false);
            if (alt2_35.isSuccess() && alt2_35.node != null) {
                children.add(alt2_35.node);
            }
            if (alt2_35.isSuccess()) {
                elem0_0 = alt2_35;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_36 = matchLiteralCst("private", false);
            if (alt2_36.isSuccess() && alt2_36.node != null) {
                children.add(alt2_36.node);
            }
            if (alt2_36.isSuccess()) {
                elem0_0 = alt2_36;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_37 = matchLiteralCst("protected", false);
            if (alt2_37.isSuccess() && alt2_37.node != null) {
                children.add(alt2_37.node);
            }
            if (alt2_37.isSuccess()) {
                elem0_0 = alt2_37;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_38 = matchLiteralCst("public", false);
            if (alt2_38.isSuccess() && alt2_38.node != null) {
                children.add(alt2_38.node);
            }
            if (alt2_38.isSuccess()) {
                elem0_0 = alt2_38;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_39 = matchLiteralCst("record", false);
            if (alt2_39.isSuccess() && alt2_39.node != null) {
                children.add(alt2_39.node);
            }
            if (alt2_39.isSuccess()) {
                elem0_0 = alt2_39;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_40 = matchLiteralCst("return", false);
            if (alt2_40.isSuccess() && alt2_40.node != null) {
                children.add(alt2_40.node);
            }
            if (alt2_40.isSuccess()) {
                elem0_0 = alt2_40;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_41 = matchLiteralCst("sealed", false);
            if (alt2_41.isSuccess() && alt2_41.node != null) {
                children.add(alt2_41.node);
            }
            if (alt2_41.isSuccess()) {
                elem0_0 = alt2_41;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_42 = matchLiteralCst("short", false);
            if (alt2_42.isSuccess() && alt2_42.node != null) {
                children.add(alt2_42.node);
            }
            if (alt2_42.isSuccess()) {
                elem0_0 = alt2_42;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_43 = matchLiteralCst("static", false);
            if (alt2_43.isSuccess() && alt2_43.node != null) {
                children.add(alt2_43.node);
            }
            if (alt2_43.isSuccess()) {
                elem0_0 = alt2_43;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_44 = matchLiteralCst("strictfp", false);
            if (alt2_44.isSuccess() && alt2_44.node != null) {
                children.add(alt2_44.node);
            }
            if (alt2_44.isSuccess()) {
                elem0_0 = alt2_44;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_45 = matchLiteralCst("super", false);
            if (alt2_45.isSuccess() && alt2_45.node != null) {
                children.add(alt2_45.node);
            }
            if (alt2_45.isSuccess()) {
                elem0_0 = alt2_45;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_46 = matchLiteralCst("switch", false);
            if (alt2_46.isSuccess() && alt2_46.node != null) {
                children.add(alt2_46.node);
            }
            if (alt2_46.isSuccess()) {
                elem0_0 = alt2_46;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_47 = matchLiteralCst("synchronized", false);
            if (alt2_47.isSuccess() && alt2_47.node != null) {
                children.add(alt2_47.node);
            }
            if (alt2_47.isSuccess()) {
                elem0_0 = alt2_47;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_48 = matchLiteralCst("this", false);
            if (alt2_48.isSuccess() && alt2_48.node != null) {
                children.add(alt2_48.node);
            }
            if (alt2_48.isSuccess()) {
                elem0_0 = alt2_48;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_49 = matchLiteralCst("throw", false);
            if (alt2_49.isSuccess() && alt2_49.node != null) {
                children.add(alt2_49.node);
            }
            if (alt2_49.isSuccess()) {
                elem0_0 = alt2_49;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_50 = matchLiteralCst("throws", false);
            if (alt2_50.isSuccess() && alt2_50.node != null) {
                children.add(alt2_50.node);
            }
            if (alt2_50.isSuccess()) {
                elem0_0 = alt2_50;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_51 = matchLiteralCst("transient", false);
            if (alt2_51.isSuccess() && alt2_51.node != null) {
                children.add(alt2_51.node);
            }
            if (alt2_51.isSuccess()) {
                elem0_0 = alt2_51;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_52 = matchLiteralCst("true", false);
            if (alt2_52.isSuccess() && alt2_52.node != null) {
                children.add(alt2_52.node);
            }
            if (alt2_52.isSuccess()) {
                elem0_0 = alt2_52;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_53 = matchLiteralCst("try", false);
            if (alt2_53.isSuccess() && alt2_53.node != null) {
                children.add(alt2_53.node);
            }
            if (alt2_53.isSuccess()) {
                elem0_0 = alt2_53;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_54 = matchLiteralCst("var", false);
            if (alt2_54.isSuccess() && alt2_54.node != null) {
                children.add(alt2_54.node);
            }
            if (alt2_54.isSuccess()) {
                elem0_0 = alt2_54;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_55 = matchLiteralCst("void", false);
            if (alt2_55.isSuccess() && alt2_55.node != null) {
                children.add(alt2_55.node);
            }
            if (alt2_55.isSuccess()) {
                elem0_0 = alt2_55;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_56 = matchLiteralCst("volatile", false);
            if (alt2_56.isSuccess() && alt2_56.node != null) {
                children.add(alt2_56.node);
            }
            if (alt2_56.isSuccess()) {
                elem0_0 = alt2_56;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_57 = matchLiteralCst("when", false);
            if (alt2_57.isSuccess() && alt2_57.node != null) {
                children.add(alt2_57.node);
            }
            if (alt2_57.isSuccess()) {
                elem0_0 = alt2_57;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_58 = matchLiteralCst("while", false);
            if (alt2_58.isSuccess() && alt2_58.node != null) {
                children.add(alt2_58.node);
            }
            if (alt2_58.isSuccess()) {
                elem0_0 = alt2_58;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_59 = matchLiteralCst("yield", false);
            if (alt2_59.isSuccess() && alt2_59.node != null) {
                children.add(alt2_59.node);
            }
            if (alt2_59.isSuccess()) {
                elem0_0 = alt2_59;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_60 = matchLiteralCst("_", false);
            if (alt2_60.isSuccess() && alt2_60.node != null) {
                children.add(alt2_60.node);
            }
            if (alt2_60.isSuccess()) {
                elem0_0 = alt2_60;
            } else {
                restoreLocation(choiceStart2);
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            if (elem0_0 == null) {
                children.clear();
                children.addAll(savedChildren2);
                elem0_0 = CstParseResult.failure("one of alternatives");
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var notStart64 = location();
            var savedChildrenNot64 = new ArrayList<>(children);
            var notElem64 = matchCharClassCst("a-zA-Z0-9_$", false, false);
            restoreLocation(notStart64);
            children.clear();
            children.addAll(savedChildrenNot64);
            var elem0_1 = notElem64.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, "Keyword", children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    // === Helper Methods ===

    private List<Trivia> skipWhitespace() {
        var trivia = new ArrayList<Trivia>();
        if (inTokenBoundary) return trivia;
        while (!isAtEnd()) {
            var wsStartLoc = location();
            var wsStartPos = pos;
            CstParseResult wsResult = CstParseResult.success(null, "", location());
            var zomStart0 = location();
            while (true) {
                var beforeLoc0 = location();
                CstParseResult zomElem0 = null;
                var choiceStart2 = location();
                var alt2_0 = matchCharClassCst(" \\t\\r\\n", false, false);
                if (alt2_0.isSuccess()) {
                    zomElem0 = alt2_0;
                } else {
                    restoreLocation(choiceStart2);
                CstParseResult alt2_1 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (alt2_1.isSuccess()) {
                    var elem4_0 = matchLiteralCst("//", false);
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        alt2_1 = elem4_0;
                    }
                }
                if (alt2_1.isSuccess()) {
                    CstParseResult elem4_1 = CstParseResult.success(null, "", location());
                    var zomStart6 = location();
                    while (true) {
                        var beforeLoc6 = location();
                        var zomElem6 = matchCharClassCst("\\n", true, false);
                        if (zomElem6.isFailure() || location().offset() == beforeLoc6.offset()) {
                            restoreLocation(beforeLoc6);
                            break;
                        }
                    }
                    elem4_1 = CstParseResult.success(null, substring(zomStart6.offset(), pos), location());
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        alt2_1 = elem4_1;
                    }
                }
                if (alt2_1.isSuccess()) {
                    alt2_1 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (alt2_1.isSuccess()) {
                    zomElem0 = alt2_1;
                } else {
                    restoreLocation(choiceStart2);
                CstParseResult alt2_2 = CstParseResult.success(null, "", location());
                var seqStart8 = location();
                if (alt2_2.isSuccess()) {
                    var elem8_0 = matchLiteralCst("/*", false);
                    if (elem8_0.isFailure()) {
                        restoreLocation(seqStart8);
                        alt2_2 = elem8_0;
                    }
                }
                if (alt2_2.isSuccess()) {
                    CstParseResult elem8_1 = CstParseResult.success(null, "", location());
                    var zomStart10 = location();
                    while (true) {
                        var beforeLoc10 = location();
                        CstParseResult zomElem10 = CstParseResult.success(null, "", location());
                        var seqStart12 = location();
                        if (zomElem10.isSuccess()) {
                            var notStart13 = location();
                            var notElem13 = matchLiteralCst("*/", false);
                            restoreLocation(notStart13);
                            var elem12_0 = notElem13.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                            if (elem12_0.isFailure()) {
                                restoreLocation(seqStart12);
                                zomElem10 = elem12_0;
                            }
                        }
                        if (zomElem10.isSuccess()) {
                            var elem12_1 = matchAnyCst();
                            if (elem12_1.isFailure()) {
                                restoreLocation(seqStart12);
                                zomElem10 = elem12_1;
                            }
                        }
                        if (zomElem10.isSuccess()) {
                            zomElem10 = CstParseResult.success(null, substring(seqStart12.offset(), pos), location());
                        }
                        if (zomElem10.isFailure() || location().offset() == beforeLoc10.offset()) {
                            restoreLocation(beforeLoc10);
                            break;
                        }
                    }
                    elem8_1 = CstParseResult.success(null, substring(zomStart10.offset(), pos), location());
                    if (elem8_1.isFailure()) {
                        restoreLocation(seqStart8);
                        alt2_2 = elem8_1;
                    }
                }
                if (alt2_2.isSuccess()) {
                    var elem8_2 = matchLiteralCst("*/", false);
                    if (elem8_2.isFailure()) {
                        restoreLocation(seqStart8);
                        alt2_2 = elem8_2;
                    }
                }
                if (alt2_2.isSuccess()) {
                    alt2_2 = CstParseResult.success(null, substring(seqStart8.offset(), pos), location());
                }
                if (alt2_2.isSuccess()) {
                    zomElem0 = alt2_2;
                } else {
                    restoreLocation(choiceStart2);
                }
                }
                }
                if (zomElem0 == null) {
                    zomElem0 = CstParseResult.failure("one of alternatives");
                }
                if (zomElem0.isFailure() || location().offset() == beforeLoc0.offset()) {
                    restoreLocation(beforeLoc0);
                    break;
                }
            }
            wsResult = CstParseResult.success(null, substring(zomStart0.offset(), pos), location());
            if (wsResult.isFailure() || pos == wsStartPos) break;
            var wsText = substring(wsStartPos, pos);
            var wsSpan = SourceSpan.of(wsStartLoc, location());
            trivia.add(new Trivia.Whitespace(wsSpan, wsText));
        }
        return trivia;
    }

    private CstParseResult matchLiteralCst(String text, boolean caseInsensitive) {
        if (remaining() < text.length()) {
            return CstParseResult.failure("'" + text + "'");
        }
        var startLoc = location();
        for (int i = 0; i < text.length(); i++) {
            char expected = text.charAt(i);
            char actual = peek(i);
            if (caseInsensitive) {
                if (Character.toLowerCase(expected) != Character.toLowerCase(actual)) {
                    return CstParseResult.failure("'" + text + "'");
                }
            } else {
                if (expected != actual) {
                    return CstParseResult.failure("'" + text + "'");
                }
            }
        }
        for (int i = 0; i < text.length(); i++) {
            advance();
        }
        var span = SourceSpan.of(startLoc, location());
        var node = new CstNode.Terminal(span, "literal", text, List.of(), List.of());
        return CstParseResult.success(node, text, location());
    }

    private CstParseResult matchCharClassCst(String pattern, boolean negated, boolean caseInsensitive) {
        if (isAtEnd()) {
            return CstParseResult.failure("character class");
        }
        var startLoc = location();
        char c = peek();
        boolean matches = matchesPattern(c, pattern, caseInsensitive);
        if (negated) matches = !matches;
        if (!matches) {
            return CstParseResult.failure("character class");
        }
        advance();
        var text = String.valueOf(c);
        var span = SourceSpan.of(startLoc, location());
        var node = new CstNode.Terminal(span, "char", text, List.of(), List.of());
        return CstParseResult.success(node, text, location());
    }

    private boolean matchesPattern(char c, String pattern, boolean caseInsensitive) {
        char testChar = caseInsensitive ? Character.toLowerCase(c) : c;
        int i = 0;
        while (i < pattern.length()) {
            char start = pattern.charAt(i);
            if (start == '\\' && i + 1 < pattern.length()) {
                char escaped = pattern.charAt(i + 1);
                char expected = switch (escaped) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    case ']' -> ']';
                    case '-' -> '-';
                    default -> escaped;
                };
                if (caseInsensitive) expected = Character.toLowerCase(expected);
                if (testChar == expected) return true;
                i += 2;
                continue;
            }
            if (i + 2 < pattern.length() && pattern.charAt(i + 1) == '-') {
                char end = pattern.charAt(i + 2);
                if (caseInsensitive) {
                    start = Character.toLowerCase(start);
                    end = Character.toLowerCase(end);
                }
                if (testChar >= start && testChar <= end) return true;
                i += 3;
            } else {
                if (caseInsensitive) start = Character.toLowerCase(start);
                if (testChar == start) return true;
                i++;
            }
        }
        return false;
    }

    private CstParseResult matchAnyCst() {
        if (isAtEnd()) {
            return CstParseResult.failure("any character");
        }
        var startLoc = location();
        char c = advance();
        var text = String.valueOf(c);
        var span = SourceSpan.of(startLoc, location());
        var node = new CstNode.Terminal(span, "any", text, List.of(), List.of());
        return CstParseResult.success(node, text, location());
    }

    // === CST Parse Result ===

    private static final class CstParseResult {
        final boolean success;
        final CstNode node;
        final String text;
        final String expected;
        final SourceLocation endLocation;

        private CstParseResult(boolean success, CstNode node, String text, String expected, SourceLocation endLocation) {
            this.success = success;
            this.node = node;
            this.text = text;
            this.expected = expected;
            this.endLocation = endLocation;
        }

        boolean isSuccess() { return success; }
        boolean isFailure() { return !success; }

        static CstParseResult success(CstNode node, String text, SourceLocation endLocation) {
            return new CstParseResult(true, node, text, null, endLocation);
        }

        static CstParseResult failure(String expected) {
            return new CstParseResult(false, null, null, expected, null);
        }
    }
}
