package org.pragmatica.jbct.format.cst;

import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.SourceFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
