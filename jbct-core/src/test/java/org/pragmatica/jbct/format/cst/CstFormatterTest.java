package org.pragmatica.jbct.format.cst;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.shared.SourceFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for CST-based formatter.
 */
class CstFormatterTest {

    private static final Path EXAMPLES_DIR = Path.of("src/test/resources/format-examples");

    private CstFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = CstFormatter.cstFormatter();
    }

    @Test
    void format_simpleClass() {
        var source = new SourceFile(
            Path.of("Test.java"),
            """
            package com.example;

            public class Test {
                public String hello() {
                    return "world";
                }
            }
            """
        );

        formatter.format(source)
            .onFailure(cause -> fail("Format failed: " + cause.message()))
            .onSuccess(formatted -> {
                System.out.println("=== Formatted output ===");
                System.out.println(formatted.content());
                assertThat(formatted.content()).contains("package com.example;");
                assertThat(formatted.content()).contains("public class Test");
            });
    }

    @Test
    void format_chainAlignment() {
        var source = new SourceFile(
            Path.of("Chain.java"),
            """
            package test;
            import org.pragmatica.lang.Result;
            class Chain {
                Result<String> test(Result<String> input) {
                    return input.map(String::trim).map(String::toUpperCase);
                }
            }
            """
        );

        formatter.format(source)
            .onFailure(cause -> fail("Format failed: " + cause.message()))
            .onSuccess(formatted -> {
                System.out.println("=== Chain output ===");
                System.out.println(formatted.content());
                // Should align continuation to receiver end
            });
    }

    @Test
    void format_goldenExample_chainAlignment() throws IOException {
        var path = EXAMPLES_DIR.resolve("ChainAlignment.java");
        var content = Files.readString(path);
        var source = new SourceFile(path, content);

        formatter.format(source)
            .onFailure(cause -> fail("Format failed: " + cause.message()))
            .onSuccess(formatted -> {
                System.out.println("=== ChainAlignment.java output ===");
                System.out.println(formatted.content());
                System.out.println("=== Expected ===");
                System.out.println(content);
            });
    }

    @Test
    void format_memberSpacing() {
        // Test member spacing: fields should NOT have blank lines between them,
        // but there should be blank line between fields and methods
        var code = "package test;\nimport org.pragmatica.lang.Result;\nclass Chain {\n    private String name;\n    private int age;\n    public String getName() { return name; }\n    public int getAge() { return age; }\n}\n";
        System.out.println("=== Input code ===");
        System.out.println(code);

        // Test parsing first
        var parser = new Java25Parser();
        var parseResult = parser.parse(code);
        System.out.println("Parse result: " + (parseResult.isSuccess() ? "success" : "failure"));
        if (parseResult.isFailure()) {
            parseResult.onFailure(cause -> System.out.println("Parse error: " + cause.message()));
            fail("Parse failed");
            return;
        }

        var source = new SourceFile(Path.of("Test.java"), code);
        formatter.format(source)
            .onFailure(cause -> fail("Format failed: " + cause.message()))
            .onSuccess(formatted -> {
                System.out.println("=== Member spacing output ===");
                System.out.println(formatted.content());
            });
    }

    @Test
    void format_importGrouping() {
        var source = new SourceFile(
            Path.of("Test.java"),
            """
            package test;

            import java.util.List;
            import org.pragmatica.lang.Result;
            import java.util.Map;
            import static java.util.Collections.emptyList;
            import org.pragmatica.lang.Option;

            class Test {}
            """
        );

        formatter.format(source)
            .onFailure(cause -> fail("Format failed: " + cause.message()))
            .onSuccess(formatted -> {
                System.out.println("=== Import grouping output ===");
                System.out.println(formatted.content());
                // Should group imports: pragmatica, then java, then static
            });
    }

    @Test
    void debug_cstTrivia() {
        var parser = new Java25Parser();
        // Test with comment
        var code = "class Foo { // comment\n  void bar() { } }";
        var result = parser.parse(code);
        System.out.println("Parse result: " + result);
        assertTrue(result.isSuccess(), () -> "Parse failed: " + result);
        result.onSuccess(cst -> {
            dumpCst(cst, 0, code);
        });
    }

    @Test
    void debug_cstTriviaAssignment() {
        var parser = new Java25Parser();
        var code = "class Test { void foo() { int x = 1; } }";
        var result = parser.parse(code);
        System.out.println("Parse code: " + code);
        System.out.println("Parse result: " + (result.isSuccess() ? "success" : "failure"));
        assertTrue(result.isSuccess(), () -> "Parse failed: " + result);
        result.onSuccess(cst -> dumpCstWithTrivia(cst, 0));
    }

    @Test
    void debug_lambdaParsing() {
        var parser = new Java25Parser();
        var code = "class Test { void foo() { list.filter(s -> !s.isEmpty()); } }";
        var result = parser.parse(code);
        System.out.println("Parse code: " + code);
        System.out.println("Parse result: " + (result.isSuccess() ? "success" : "failure"));
        assertTrue(result.isSuccess(), () -> "Parse failed: " + result);
        result.onSuccess(cst -> dumpCstLambda(cst, 0));
    }

    @Test
    void debug_formatLambda() {
        var code = "class Test { void foo() { list.filter(s -> !s.isEmpty()); } }";
        var source = new SourceFile(Path.of("Test.java"), code);

        formatter.format(source)
            .onFailure(cause -> fail("Format failed: " + cause.message()))
            .onSuccess(formatted -> {
                System.out.println("=== Input ===");
                System.out.println(code);
                System.out.println("=== Output ===");
                System.out.println(formatted.content());
                // Check for space around ->
                assertThat(formatted.content()).contains("s -> !");
            });
    }

    @Test
    void debug_formatMethodRef() {
        var code = "class Test { void foo() { list.map(String::trim).map(String::toUpperCase); } }";
        var source = new SourceFile(Path.of("Test.java"), code);

        formatter.format(source)
            .onFailure(cause -> fail("Format failed: " + cause.message()))
            .onSuccess(formatted -> {
                System.out.println("=== Input ===");
                System.out.println(code);
                System.out.println("=== Output ===");
                System.out.println(formatted.content());
                // Should NOT have space after (
                assertThat(formatted.content()).contains("map(String::trim)");
            });
    }

    @Test
    void debug_formatTernary() {
        var code = "class Test { String foo(boolean condition) { return condition ? \"yes\" : \"no\"; } }";
        var source = new SourceFile(Path.of("Test.java"), code);

        formatter.format(source)
            .onFailure(cause -> fail("Format failed: " + cause.message()))
            .onSuccess(formatted -> {
                System.out.println("=== Input ===");
                System.out.println(code);
                System.out.println("=== Output ===");
                System.out.println(formatted.content());
            });
    }

    @Test
    void debug_formatBlankLines() throws IOException {
        compareGoldenFile("BlankLines.java");
    }

    @Test
    void debug_formatTernaryOperators() throws IOException {
        compareGoldenFile("TernaryOperators.java");
    }

    private void compareGoldenFile(String fileName) throws IOException {
        var path = EXAMPLES_DIR.resolve(fileName);
        var content = Files.readString(path);
        var source = new SourceFile(path, content);

        formatter.format(source)
            .onFailure(cause -> fail("Format failed: " + cause.message()))
            .onSuccess(formatted -> {
                // Find first difference
                var expected = content;
                var actual = formatted.content();
                for (int i = 0; i < Math.min(expected.length(), actual.length()); i++) {
                    if (expected.charAt(i) != actual.charAt(i)) {
                        int start = Math.max(0, i - 30);
                        int end = Math.min(expected.length(), i + 30);
                        System.out.println("First diff at position " + i + " in " + fileName);
                        System.out.println("Expected: '" + expected.substring(start, end).replace("\n", "\\n") + "'");
                        System.out.println("Actual: '" + actual.substring(start, Math.min(actual.length(), i + 30)).replace("\n", "\\n") + "'");
                        return;
                    }
                }
                System.out.println(fileName + " matches!");
            });
    }

    @Test
    void debug_blankLinesTrivia() {
        var parser = new Java25Parser();
        var code = "class Test {\n    int a;\n\n    // Comment\n    int b;\n}";
        System.out.println("=== Input code ===");
        System.out.println(code);
        var result = parser.parse(code);
        result.onSuccess(cst -> {
            System.out.println("=== CST with trivia (ClassMember nodes only) ===");
            dumpClassMembers(cst, 0);
        });
    }

    @Test
    void debug_emptyRecordBody() {
        var code = "class Outer { record Test(String value) {} }";
        System.out.println("=== Input code ===");
        System.out.println(code);
        var source = new SourceFile(Path.of("Test.java"), code);
        formatter.format(source)
            .onFailure(cause -> fail("Format failed: " + cause.message()))
            .onSuccess(formatted -> {
                System.out.println("=== Output ===");
                System.out.println(formatted.content());
            });
    }

    private void dumpClassMembers(CstNode node, int depth) {
        var indent = "  ".repeat(depth);
        switch (node) {
            case CstNode.Terminal t -> {}
            case CstNode.Token tok -> {}
            case CstNode.NonTerminal nt -> {
                if (nt.rule().equals("ClassMember")) {
                    System.out.println(indent + "ClassMember:");
                    for (var tr : nt.leadingTrivia()) {
                        System.out.println(indent + "  LTrivia: " + triviaDesc(tr));
                    }
                    for (var tr : nt.trailingTrivia()) {
                        System.out.println(indent + "  TTrivia: " + triviaDesc(tr));
                    }
                }
                for (var child : nt.children()) {
                    dumpClassMembers(child, depth + 1);
                }
            }
        }
    }

    private void dumpCstLambda(CstNode node, int depth) {
        var indent = "  ".repeat(depth);
        switch (node) {
            case CstNode.Terminal t -> {
                if (t.text().equals("->") || t.text().equals("s") || t.text().equals("filter")) {
                    System.out.println(indent + "Terminal(" + t.rule() + "): '" + t.text() + "'");
                }
            }
            case CstNode.Token tok -> {
                if (tok.text().equals("->") || tok.text().equals("s") || tok.text().equals("filter")) {
                    System.out.println(indent + "Token(" + tok.rule() + "): '" + tok.text() + "'");
                }
            }
            case CstNode.NonTerminal nt -> {
                if (nt.rule().equals("Lambda") || nt.rule().equals("Primary") || nt.rule().equals("Postfix") ||
                    nt.rule().equals("Args") || nt.rule().equals("Expr")) {
                    System.out.println(indent + "NonTerminal(" + nt.rule() + ")");
                }
                for (var child : nt.children()) {
                    dumpCstLambda(child, depth + 1);
                }
            }
        }
    }

    private void dumpCstWithTrivia(CstNode node, int depth) {
        var indent = "  ".repeat(depth);
        switch (node) {
            case CstNode.Terminal t -> {
                System.out.println(indent + "Terminal(" + t.rule() + "): '" + t.text() + "'");
                for (var tr : t.leadingTrivia()) {
                    System.out.println(indent + "  LTrivia: " + triviaDesc(tr));
                }
                for (var tr : t.trailingTrivia()) {
                    System.out.println(indent + "  TTrivia: " + triviaDesc(tr));
                }
            }
            case CstNode.Token tok -> {
                System.out.println(indent + "Token(" + tok.rule() + "): '" + tok.text() + "'");
                for (var tr : tok.leadingTrivia()) {
                    System.out.println(indent + "  LTrivia: " + triviaDesc(tr));
                }
                for (var tr : tok.trailingTrivia()) {
                    System.out.println(indent + "  TTrivia: " + triviaDesc(tr));
                }
            }
            case CstNode.NonTerminal nt -> {
                System.out.println(indent + "NonTerminal(" + nt.rule() + ")");
                for (var child : nt.children()) {
                    dumpCstWithTrivia(child, depth + 1);
                }
            }
        }
    }

    private String triviaDesc(Java25Parser.Trivia t) {
        return switch (t) {
            case Java25Parser.Trivia.Whitespace ws -> "WS('" + ws.text().replace("\n", "\\n").replace(" ", "·") + "')";
            case Java25Parser.Trivia.LineComment lc -> "LC('" + lc.text() + "')";
            case Java25Parser.Trivia.BlockComment bc -> "BC('" + bc.text() + "')";
        };
    }

    private void dumpCst(CstNode node, int depth, String source) {
        var indent = "  ".repeat(depth);
        var trivia = "leading=" + node.leadingTrivia().size() + ", trailing=" + node.trailingTrivia().size();
        switch (node) {
            case CstNode.Terminal t -> System.out.println(indent + "Terminal(" + t.rule() + "): '" + t.text() + "' [" + trivia + "]");
            case CstNode.Token tok -> System.out.println(indent + "Token(" + tok.rule() + "): '" + tok.text() + "' [" + trivia + "]");
            case CstNode.NonTerminal nt -> {
                System.out.println(indent + "NonTerminal(" + nt.rule() + ") [" + trivia + "]");
                for (var child : nt.children()) {
                    dumpCst(child, depth + 1, source);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ChainAlignment.java",
        "MultilineArguments.java",
        "MultilineParameters.java",
        "Lambdas.java",
        "Annotations.java",
        "Imports.java",
        "Records.java",
        "SwitchExpressions.java",
        "TernaryOperators.java",
        "BlankLines.java",
        "LineWrapping.java",
        "Comments.java"
    })
    void cstFormatter_isIdempotent_onGoldenExamples(String fileName) throws IOException {
        var path = EXAMPLES_DIR.resolve(fileName);
        var content = Files.readString(path);
        var source = new SourceFile(path, content);

        formatter.format(source)
            .onFailure(cause -> fail("Format failed for " + fileName + ": " + cause.message()))
            .onSuccess(formatted -> {
                if (!formatted.content().equals(content)) {
                    System.err.println("=== Expected (" + fileName + ") ===");
                    System.err.println(content);
                    System.err.println("=== Actual ===");
                    System.err.println(formatted.content());
                }
                assertEquals(content, formatted.content(), "CstFormatter changed golden example: " + fileName);
            });
    }
}
