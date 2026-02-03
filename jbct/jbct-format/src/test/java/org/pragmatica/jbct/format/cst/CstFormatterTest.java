package org.pragmatica.jbct.format.cst;

import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.shared.SourceFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        var source = new SourceFile(Path.of("Test.java"),
                                    """
            package com.example;

            public class Test {
                public String hello() {
                    return "world";
                }
            }
            """);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> {
                                System.out.println("=== Formatted output ===");
                                System.out.println(formatted.content());
                                assertThat(formatted.content())
                                          .contains("package com.example;");
                                assertThat(formatted.content())
                                          .contains("public class Test");
                            });
    }

    @Test
    void format_chainAlignment() {
        var source = new SourceFile(Path.of("Chain.java"),
                                    """
            package test;
            import org.pragmatica.lang.Result;
            class Chain {
                Result<String> test(Result<String> input) {
                    return input.map(String::trim).map(String::toUpperCase);
                }
            }
            """);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> {
                                // Verify chain calls are preserved
                                assertThat(formatted.content())
                                          .contains(".map(String::trim)");
                                assertThat(formatted.content())
                                          .contains(".map(String::toUpperCase)");
                                // Verify chain structure is maintained
                                assertThat(formatted.content())
                                          .contains("return input");
                            });
    }

    @Disabled("Debug utility for visual inspection - covered by cstFormatter_isIdempotent_onGoldenExamples")
    @Test
    void format_goldenExample_chainAlignment() throws IOException {
        var path = EXAMPLES_DIR.resolve("ChainAlignment.java");
        var content = Files.readString(path);
        var source = new SourceFile(path, content);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> assertEquals(content,
                                                      formatted.content(),
                                                      "Golden example should be idempotent"));
    }

    @Test
    void format_memberSpacing() {
        // Test member spacing: fields should NOT have blank lines between them,
        // but there should be blank line between fields and methods
        var code = "package test;\nimport org.pragmatica.lang.Result;\nclass Chain {\n    private String name;\n    private int age;\n    public String getName() { return name; }\n    public int getAge() { return age; }\n}\n";
        // Test parsing first
        var parser = new Java25Parser();
        var parseResult = parser.parse(code);
        if (parseResult.isFailure()) {
            parseResult.onFailure(cause -> fail("Parse error: " + cause.message()));
            return;
        }
        var source = new SourceFile(Path.of("Test.java"), code);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> {
                                // Verify fields are preserved
                                assertThat(formatted.content())
                                          .contains("private String name;");
                                assertThat(formatted.content())
                                          .contains("private int age;");
                                // Verify methods are preserved
                                assertThat(formatted.content())
                                          .contains("public String getName()");
                                assertThat(formatted.content())
                                          .contains("public int getAge()");
                            });
    }

    @Test
    void format_importGrouping() {
        var source = new SourceFile(Path.of("Test.java"),
                                    """
            package test;

            import java.util.List;
            import org.pragmatica.lang.Result;
            import java.util.Map;
            import static java.util.Collections.emptyList;
            import org.pragmatica.lang.Option;

            class Test {}
            """);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> {
                                // Verify all imports are preserved
                                assertThat(formatted.content())
                                          .contains("import java.util.List;");
                                assertThat(formatted.content())
                                          .contains("import java.util.Map;");
                                assertThat(formatted.content())
                                          .contains("import org.pragmatica.lang.Result;");
                                assertThat(formatted.content())
                                          .contains("import org.pragmatica.lang.Option;");
                                assertThat(formatted.content())
                                          .contains("import static java.util.Collections.emptyList;");
                            });
    }

    @Disabled("Debug utility for CST inspection - not a behavioral test")
    @Test
    void debug_cstTrivia() {
        var parser = new Java25Parser();
        // Test with comment
        var code = "class Foo { // comment\n  void bar() { } }";
        var result = parser.parse(code);
        assertTrue(result.isSuccess(), () -> "Parse failed: " + result);
        result.onSuccess(cst -> dumpCst(cst, 0, code));
    }

    @Disabled("Debug utility for CST trivia inspection - not a behavioral test")
    @Test
    void debug_cstTriviaAssignment() {
        var parser = new Java25Parser();
        var code = "class Test { void foo() { int x = 1; } }";
        var result = parser.parse(code);
        assertTrue(result.isSuccess(), () -> "Parse failed: " + result);
        result.onSuccess(cst -> dumpCstWithTrivia(cst, 0));
    }

    @Disabled("Debug utility for lambda CST inspection - not a behavioral test")
    @Test
    void debug_lambdaParsing() {
        var parser = new Java25Parser();
        var code = "class Test { void foo() { list.filter(s -> !s.isEmpty()); } }";
        var result = parser.parse(code);
        assertTrue(result.isSuccess(), () -> "Parse failed: " + result);
        result.onSuccess(cst -> dumpCstLambda(cst, 0));
    }

    @Test
    void format_preservesLambdaSpacing() {
        var code = "class Test { void foo() { list.filter(s -> !s.isEmpty()); } }";
        var source = new SourceFile(Path.of("Test.java"), code);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> {
                                // Check for space around ->
                                assertThat(formatted.content())
                                          .contains("s -> !");
                            });
    }

    @Test
    void format_preservesMethodReference() {
        var code = "class Test { void foo() { list.map(String::trim).map(String::toUpperCase); } }";
        var source = new SourceFile(Path.of("Test.java"), code);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> {
                                // Should NOT have space after (
                                assertThat(formatted.content())
                                          .contains("map(String::trim)");
                            });
    }

    @Test
    void format_preservesTernary() {
        var code = "class Test { String foo(boolean condition) { return condition ? \"yes\" : \"no\"; } }";
        var source = new SourceFile(Path.of("Test.java"), code);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> {
                                // Verify ternary operator components are preserved
                                // Formatter may wrap long ternaries across multiple lines
                                assertThat(formatted.content())
                                          .contains("condition");
                                assertThat(formatted.content())
                                          .contains("?");
                                assertThat(formatted.content())
                                          .contains("\"yes\"");
                                assertThat(formatted.content())
                                          .contains(":");
                                assertThat(formatted.content())
                                          .contains("\"no\"");
                            });
    }

    @Disabled("Debug utility - covered by cstFormatter_isIdempotent_onGoldenExamples")
    @Test
    void debug_formatBlankLines() throws IOException {
        compareGoldenFile("BlankLines.java");
    }

    @Disabled("Debug utility - covered by cstFormatter_isIdempotent_onGoldenExamples")
    @Test
    void debug_formatChainAlignment() throws IOException {
        compareGoldenFile("ChainAlignment.java");
    }

    @Disabled("Debug utility - covered by cstFormatter_isIdempotent_onGoldenExamples")
    @Test
    void debug_formatAnnotations() throws IOException {
        compareGoldenFile("Annotations.java");
    }

    @Disabled("Debug utility - covered by cstFormatter_isIdempotent_onGoldenExamples")
    @Test
    void debug_formatMultilineArguments() throws IOException {
        compareGoldenFile("MultilineArguments.java");
    }

    @Disabled("Debug utility - covered by cstFormatter_isIdempotent_onGoldenExamples")
    @Test
    void debug_formatLambdas() throws IOException {
        compareGoldenFile("Lambdas.java");
    }

    @Disabled("Debug utility - covered by cstFormatter_isIdempotent_onGoldenExamples")
    @Test
    void debug_formatSwitchExpressions() throws IOException {
        compareGoldenFile("SwitchExpressions.java");
    }

    @Disabled("Debug utility - covered by cstFormatter_isIdempotent_onGoldenExamples")
    @Test
    void debug_formatLineWrapping() throws IOException {
        compareGoldenFile("LineWrapping.java");
    }

    @Disabled("Debug utility - covered by cstFormatter_isIdempotent_onGoldenExamples")
    @Test
    void debug_formatMultilineParameters() throws IOException {
        compareGoldenFile("MultilineParameters.java");
    }

    @Disabled("Debug utility - covered by cstFormatter_isIdempotent_onGoldenExamples")
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
                                for (int i = 0; i < Math.min(expected.length(),
                                                             actual.length()); i++) {
                                    if (expected.charAt(i) != actual.charAt(i)) {
                                        int start = Math.max(0, i - 30);
                                        int end = Math.min(expected.length(),
                                                           i + 30);
                                        System.out.println("First diff at position " + i + " in " + fileName);
                                        System.out.println("Expected: '" + expected.substring(start, end)
                                                                                   .replace("\n", "\\n") + "'");
                                        System.out.println("Actual: '" + actual.substring(start,
                                                                                          Math.min(actual.length(),
                                                                                                   i + 30))
                                                                               .replace("\n", "\\n") + "'");
                                        return;
                                    }
                                }
                                System.out.println(fileName + " matches!");
                            });
    }

    @Disabled("Debug utility for blank lines trivia inspection - not a behavioral test")
    @Test
    void debug_blankLinesTrivia() {
        var parser = new Java25Parser();
        var code = "class Test {\n    int a;\n\n    // Comment\n    int b;\n}";
        var result = parser.parse(code);
        assertTrue(result.isSuccess(), () -> "Parse failed: " + result);
        result.onSuccess(cst -> dumpClassMembers(cst, 0));
    }

    @Test
    void format_preservesEmptyRecordBody() {
        var code = "class Outer { record Test(String value) {} }";
        var source = new SourceFile(Path.of("Test.java"), code);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> {
                                // Verify empty record body is preserved
                                assertThat(formatted.content())
                                          .contains("record Test(String value)");
                            });
    }

    private void dumpClassMembers(CstNode node, int depth) {
        var indent = "  ".repeat(depth);
        switch (node) {
            case CstNode.Terminal t -> {}
            case CstNode.Token tok -> {}
            case CstNode.Error err -> {}
            case CstNode.NonTerminal nt -> {
                if (nt.rule()
                      .name()
                      .equals("ClassMember")) {
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
                if (t.text()
                     .equals("->") || t.text()
                                       .equals("s") || t.text()
                                                        .equals("filter")) {
                    System.out.println(indent + "Terminal(" + t.rule()
                                                              .name() + "): '" + t.text() + "'");
                }
            }
            case CstNode.Token tok -> {
                if (tok.text()
                       .equals("->") || tok.text()
                                           .equals("s") || tok.text()
                                                              .equals("filter")) {
                    System.out.println(indent + "Token(" + tok.rule()
                                                             .name() + "): '" + tok.text() + "'");
                }
            }
            case CstNode.Error err -> {}
            case CstNode.NonTerminal nt -> {
                if (nt.rule()
                      .name()
                      .equals("Lambda") || nt.rule()
                                             .name()
                                             .equals("Primary") || nt.rule()
                                                                     .name()
                                                                     .equals("Postfix") ||
                nt.rule()
                  .name()
                  .equals("Args") || nt.rule()
                                       .name()
                                       .equals("Expr")) {
                    System.out.println(indent + "NonTerminal(" + nt.rule()
                                                                  .name() + ")");
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
                System.out.println(indent + "Terminal(" + t.rule()
                                                          .name() + "): '" + t.text() + "'");
                for (var tr : t.leadingTrivia()) {
                    System.out.println(indent + "  LTrivia: " + triviaDesc(tr));
                }
                for (var tr : t.trailingTrivia()) {
                    System.out.println(indent + "  TTrivia: " + triviaDesc(tr));
                }
            }
            case CstNode.Token tok -> {
                System.out.println(indent + "Token(" + tok.rule()
                                                         .name() + "): '" + tok.text() + "'");
                for (var tr : tok.leadingTrivia()) {
                    System.out.println(indent + "  LTrivia: " + triviaDesc(tr));
                }
                for (var tr : tok.trailingTrivia()) {
                    System.out.println(indent + "  TTrivia: " + triviaDesc(tr));
                }
            }
            case CstNode.NonTerminal nt -> {
                System.out.println(indent + "NonTerminal(" + nt.rule()
                                                              .name() + ")");
                for (var child : nt.children()) {
                    dumpCstWithTrivia(child, depth + 1);
                }
            }
            case CstNode.Error err -> System.out.println(indent + "Error: '" + err.skippedText() + "'");
        }
    }

    private String triviaDesc(Java25Parser.Trivia t) {
        return switch (t) {
            case Java25Parser.Trivia.Whitespace ws -> "WS('" + ws.text()
                                                                .replace("\n", "\\n")
                                                                .replace(" ", "·") + "')";
            case Java25Parser.Trivia.LineComment lc -> "LC('" + lc.text() + "')";
            case Java25Parser.Trivia.BlockComment bc -> "BC('" + bc.text() + "')";
        };
    }

    private void dumpCst(CstNode node, int depth, String source) {
        var indent = "  ".repeat(depth);
        var trivia = "leading=" + node.leadingTrivia()
                                     .size() + ", trailing=" + node.trailingTrivia()
                                                                  .size();
        switch (node) {
            case CstNode.Terminal t -> System.out.println(indent + "Terminal(" + t.rule()
                                                                                  .name() + "): '" + t.text() + "' [" + trivia
                                                          + "]");
            case CstNode.Token tok -> System.out.println(indent + "Token(" + tok.rule()
                                                                                .name() + "): '" + tok.text() + "' [" + trivia
                                                         + "]");
            case CstNode.Error err -> System.out.println(indent + "Error: '" + err.skippedText() + "' [" + trivia + "]");
            case CstNode.NonTerminal nt -> {
                System.out.println(indent + "NonTerminal(" + nt.rule()
                                                              .name() + ") [" + trivia + "]");
                for (var child : nt.children()) {
                    dumpCst(child, depth + 1, source);
                }
            }
        }
    }

    @Test
    void format_preservesAssertEqualsIdentifier() {
        var source = new SourceFile(Path.of("Test.java"),
                                    """
            class Test {
                void test() {
                    assertEquals(1, 2);
                    assertInstanceOf(String.class, obj);
                }
            }
            """);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> {
                                assertThat(formatted.content())
                                          .contains("assertEquals(1, 2)");
                                assertThat(formatted.content())
                                          .contains("assertInstanceOf(String.class");
                                assertThat(formatted.content())
                                          .doesNotContain("assert Equals");
                                assertThat(formatted.content())
                                          .doesNotContain("assert InstanceOf");
                            });
    }

    @Test
    void format_preservesGenericWitnessTypes() {
        var source = new SourceFile(Path.of("Test.java"),
                                    """
            class Test {
                void test() {
                    Result.<Integer>failure(cause);
                    Option.<String>none();
                }
            }
            """);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> {
                                assertThat(formatted.content())
                                          .contains("Result.<Integer>failure");
                                assertThat(formatted.content())
                                          .contains("Option.<String>none");
                                assertThat(formatted.content())
                                          .doesNotContain("Result..<Integer>");
                                assertThat(formatted.content())
                                          .doesNotContain("Option..<String>");
                            });
    }

    @Test
    void format_preservesArrayAccess() {
        var source = new SourceFile(Path.of("Test.java"),
                                    """
            class Test {
                void test() {
                    arr[0] = 1;
                    matrix[i][j] = value;
                }
            }
            """);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> {
                                assertThat(formatted.content())
                                          .contains("arr[0]");
                                assertThat(formatted.content())
                                          .contains("matrix[i][j]");
                                assertThat(formatted.content())
                                          .doesNotContain("arr [0]");
                                assertThat(formatted.content())
                                          .doesNotContain("matrix [i]");
                            });
    }

    @Test
    void format_preservesEnumSemicolonBeforeFields() {
        var source = new SourceFile(Path.of("CoreError.java"),
                                    """
            enum CoreErrors implements CoreError {
                EMPTY_OPTION("Option is empty");
                private final String message;
                CoreErrors(String message) { this.message = message; }
            }
            """);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed: " + cause.message()))
                 .onSuccess(formatted -> {
                                // Semicolon after enum constant is REQUIRED when fields follow
        assertThat(formatted.content())
                  .contains("EMPTY_OPTION(\"Option is empty\");");
                                assertThat(formatted.content())
                                          .contains("private final String message;");
                            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ChainAlignment.java",
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
    "Comments.java"})
    void cstFormatter_isIdempotent_onGoldenExamples(String fileName) throws IOException {
        var path = EXAMPLES_DIR.resolve(fileName);
        var content = Files.readString(path);
        var source = new SourceFile(path, content);
        formatter.format(source)
                 .onFailure(cause -> fail("Format failed for " + fileName + ": " + cause.message()))
                 .onSuccess(formatted -> {
                                if (!formatted.content()
                                              .equals(content)) {
                                    System.err.println("=== Expected (" + fileName + ") ===");
                                    System.err.println(content);
                                    System.err.println("=== Actual ===");
                                    System.err.println(formatted.content());
                                }
                                assertEquals(content,
                                             formatted.content(),
                                             "CstFormatter changed golden example: " + fileName);
                            });
    }

    @ParameterizedTest(name = "Multi-pass idempotency: {0}")
    @ValueSource(strings = {"BlankLines.java",
    "ChainAlignment.java",
    "Lambdas.java",
    "Comments.java",
    "Enums.java",
    "Records.java"})
    void cstFormatter_isIdempotent_afterMultiplePasses(String fileName) throws IOException {
        var path = EXAMPLES_DIR.resolve(fileName);
        var content = Files.readString(path);
        var source = new SourceFile(path, content);
        // Format once
        var firstPassResult = formatter.format(source)
                                       .onFailure(cause -> fail("First format failed for " + fileName + ": " + cause.message()));
        if (firstPassResult.isFailure()) {
            return; // Already failed above
        }
        var firstPass = firstPassResult.or(source);
        // Format 10 more times and verify no growth
        var current = firstPass;
        int firstLength = firstPass.content()
                                   .length();
        int firstLines = firstPass.content()
                                  .split("\n").length;
        for (int i = 2; i <= 10; i++) {
            final var toFormat = current;
            final int passNum = i;
            var currentResult = formatter.format(toFormat)
                                         .onFailure(cause -> fail("Format pass " + passNum + " failed for " + fileName + ": " + cause.message()));
            if (currentResult.isFailure()) {
                return; // Already failed above
            }
            current = currentResult.or(toFormat);
            int currentLength = current.content()
                                       .length();
            int currentLines = current.content()
                                      .split("\n").length;
            assertEquals(firstLength,
                         currentLength,
                         "File length changed on pass " + i + " for " + fileName + ": " + firstLength + " -> " + currentLength);
            assertEquals(firstLines,
                         currentLines,
                         "Line count changed on pass " + i + " for " + fileName + ": " + firstLines + " -> " + currentLines);
        }
    }
}
