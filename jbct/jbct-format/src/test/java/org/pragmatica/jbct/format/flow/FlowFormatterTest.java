package org.pragmatica.jbct.format.flow;

import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.shared.SourceFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Tests for the flow-based formatter.
///
/// Flow formatting produces comment-free output (comments are inserted in a separate pass).
/// Tests compare against golden examples with comments stripped.
class FlowFormatterTest {
    private static final Path FLOW_EXAMPLES_DIR = Path.of("src/test/resources/flow-format-examples");
    private static final Path GOLDEN_EXAMPLES_DIR = Path.of("src/test/resources/format-examples");

    /// Pattern matching single-line comments (// ...) including the preceding optional whitespace on the line.
    private static final Pattern LINE_COMMENT = Pattern.compile("^[ \\t]*//[^\\n]*\\n?", Pattern.MULTILINE);
    /// Pattern matching block comments (/* ... */) including doc comments.
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/\\s*\\n?");
    /// Pattern matching multiple consecutive blank lines (collapse to single).
    private static final Pattern MULTI_BLANK = Pattern.compile("\\n{3,}");

    private FlowFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = FlowFormatter.flowFormatter(FormatterConfig.defaultConfig());
    }

    /// Strip all comments from source code for comparison.
    /// This allows comparing FlowPrinter output (no comments) against golden examples (with comments).
    static String stripComments(String source) {
        var result = BLOCK_COMMENT.matcher(source).replaceAll("");
        result = LINE_COMMENT.matcher(result).replaceAll("");
        result = MULTI_BLANK.matcher(result).replaceAll("\n\n");
        // Clean up trailing whitespace on each line
        result = result.lines()
            .map(String::stripTrailing)
            .reduce((a, b) -> a + "\n" + b)
            .orElse("");
        // Ensure trailing newline
        if (!result.endsWith("\n")) {
            result = result + "\n";
        }
        return result;
    }

    @Nested
    class FlowExamples {
        @ParameterizedTest
        @ValueSource(strings = {"BlankLineRules.java"})
        void formatter_producesExpectedOutput_onFlowExamples(String fileName) throws IOException {
            var path = FLOW_EXAMPLES_DIR.resolve(fileName);
            var content = Files.readString(path);
            var source = new SourceFile(path, content);

            formatter.format(source)
                .onFailure(cause -> fail("Format failed for " + fileName + ": " + cause.message()))
                .onSuccess(formatted -> {
                    // Strip comments from expected for comparison (flow output has no comments)
                    var expectedNoComments = stripComments(content);
                    var actualNoComments = stripComments(formatted.content());

                    if (!actualNoComments.equals(expectedNoComments)) {
                        reportDifference(fileName, expectedNoComments, actualNoComments);
                    }
                });
        }

        @ParameterizedTest
        @ValueSource(strings = {"BlankLineRules.java"})
        void formatter_isIdempotent_onFlowExamples(String fileName) throws IOException {
            var path = FLOW_EXAMPLES_DIR.resolve(fileName);
            var content = Files.readString(path);
            var source = new SourceFile(path, content);

            formatter.format(source)
                .onFailure(cause -> fail("First format failed for " + fileName + ": " + cause.message()))
                .onSuccess(firstPass -> {
                    formatter.format(firstPass)
                        .onFailure(cause -> fail("Second format failed for " + fileName + ": " + cause.message()))
                        .onSuccess(secondPass -> {
                            // Strip comments for idempotency check (comments may shift positions)
                            var first = stripComments(firstPass.content());
                            var second = stripComments(secondPass.content());
                            if (!first.equals(second)) {
                                reportDifference(fileName + " (idempotency)", first, second);
                            }
                        });
                });
        }
    }

    @Nested
    class GoldenExampleComparison {
        @ParameterizedTest
        @ValueSource(strings = {"ChainAlignment.java",
                                "MultilineArguments.java",
                                "MultilineParameters.java",
                                "Lambdas.java"})
        void formatter_matchesGoldenExamples_withoutComments(String fileName) throws IOException {
            var path = GOLDEN_EXAMPLES_DIR.resolve(fileName);
            if (!Files.exists(path)) {
                return; // Skip if golden example doesn't exist
            }
            var content = Files.readString(path);
            var source = new SourceFile(path, content);

            formatter.format(source)
                .onFailure(cause -> fail("Format failed for " + fileName + ": " + cause.message()))
                .onSuccess(formatted -> {
                    var expectedNoComments = stripComments(content);
                    var actualNoComments = stripComments(formatted.content());

                    if (!actualNoComments.equals(expectedNoComments)) {
                        reportDifference("golden/" + fileName, expectedNoComments, actualNoComments);
                    }
                });
        }
    }

    @Nested
    class BasicFormatting {
        @Test
        void formatter_formatsSimpleClass() {
            var source = new SourceFile(Path.of("Test.java"),
                """
                package test;
                class Simple {
                    String name;
                    int age;
                }
                """);

            formatter.format(source)
                .onFailure(cause -> fail("Format failed: " + cause.message()))
                .onSuccess(formatted -> {
                    assertThat(formatted.content()).contains("package test;");
                    assertThat(formatted.content()).contains("class Simple");
                    assertThat(formatted.content()).contains("String name;");
                });
        }

        @Test
        void formatter_organizesImports() {
            var source = new SourceFile(Path.of("Test.java"),
                """
                package test;
                import java.util.List;
                import org.pragmatica.lang.Result;
                import static org.junit.jupiter.api.Assertions.fail;
                class Test {}
                """);

            formatter.format(source)
                .onFailure(cause -> fail("Format failed: " + cause.message()))
                .onSuccess(formatted -> {
                    var content = formatted.content();
                    int pragPos = content.indexOf("org.pragmatica");
                    int javaPos = content.indexOf("java.util");
                    int staticPos = content.indexOf("static ");
                    // pragmatica before java
                    assertThat(pragPos).isLessThan(javaPos);
                    // static last
                    assertThat(staticPos).isGreaterThan(javaPos);
                });
        }
    }

    private static void reportDifference(String fileName, String expected, String actual) {
        int minLen = Math.min(expected.length(), actual.length());
        int diffPos = minLen;
        for (int i = 0; i < minLen; i++) {
            if (expected.charAt(i) != actual.charAt(i)) {
                diffPos = i;
                break;
            }
        }
        int start = Math.max(0, diffPos - 50);
        int end = Math.min(Math.max(expected.length(), actual.length()), diffPos + 50);

        System.err.println("First diff at position " + diffPos);
        System.err.println("Expected: [" + expected.substring(start, Math.min(end, expected.length()))
            .replace("\n", "\\n").replace("\t", "\\t") + "]");
        System.err.println("Actual:   [" + actual.substring(start, Math.min(end, actual.length()))
            .replace("\n", "\\n").replace("\t", "\\t") + "]");

        fail("Flow formatter output differs for: " + fileName
             + "\nExpected length: " + expected.length()
             + ", Actual: " + actual.length()
             + ", Diff at: " + diffPos);
    }
}
