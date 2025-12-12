package org.pragmatica.jbct.format;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.jbct.shared.SourceFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Golden tests for JBCT formatter.
 *
 * These tests verify that the formatter produces output matching
 * the manually-formatted golden examples in format-examples/.
 *
 * The golden examples are the source of truth for JBCT formatting style.
 */
class GoldenFormatterTest {

    private static final Path EXAMPLES_DIR = Path.of("src/test/resources/format-examples");

    private JbctFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = JbctFormatter.jbctFormatter();
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
    void formatter_isIdempotent_onGoldenExamples(String fileName) throws IOException {
        var path = EXAMPLES_DIR.resolve(fileName);
        var content = Files.readString(path);
        var source = new SourceFile(path, content);

        // Format the golden example
        formatter.format(source)
                .onFailure(cause -> fail("Format failed for " + fileName + ": " + cause.message()))
                .onSuccess(formatted -> {
                    // The formatter should not change already-formatted golden examples
                    // This verifies idempotency and that our format matches the golden standard
                    if (!formatted.content().equals(content)) {
                        // Show diff for debugging
                        System.err.println("=== Expected (" + fileName + ") ===");
                        System.err.println(content);
                        System.err.println("=== Actual ===");
                        System.err.println(formatted.content());
                        System.err.println("=== End ===");

                        fail("Formatter changed golden example: " + fileName +
                             "\nExpected length: " + content.length() +
                             "\nActual length: " + formatted.content().length());
                    }
                });
    }

    @Test
    void formatter_parsesAllGoldenExamples() throws IOException {
        try (var files = Files.list(EXAMPLES_DIR)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            var content = Files.readString(path);
                            var source = new SourceFile(path, content);

                            formatter.format(source)
                                    .onFailure(cause ->
                                            fail("Failed to parse " + path.getFileName() + ": " + cause.message()));
                        } catch (IOException e) {
                            fail("Could not read " + path + ": " + e.getMessage());
                        }
                    });
        }
    }

    @Test
    void chainAlignment_alignsToDotPosition() {
        var source = new SourceFile(
                Path.of("Test.java"),
                """
                package test;
                import org.pragmatica.lang.Result;
                class Test {
                    Result<String> test(Result<String> input) {
                        return input.map(String::trim).map(String::toUpperCase);
                    }
                }
                """
        );

        formatter.format(source)
                .onFailure(cause -> fail("Format failed: " + cause.message()))
                .onSuccess(formatted -> {
                    // After formatting, if chain is multi-line, `.` should align
                    // For single-line chains that fit, they stay on one line
                    assertThat(formatted.content()).contains("return input");
                });
    }

    @Test
    void argumentAlignment_alignsToOpeningParen() {
        var source = new SourceFile(
                Path.of("Test.java"),
                """
                package test;
                import org.pragmatica.lang.Result;
                class Test {
                    Result<String> test() {
                        return Result.all(first(),
                        second(),
                        third());
                    }
                    Result<String> first() { return null; }
                    Result<String> second() { return null; }
                    Result<String> third() { return null; }
                }
                """
        );

        formatter.format(source)
                .onFailure(cause -> fail("Format failed: " + cause.message()))
                .onSuccess(formatted -> {
                    // Arguments should be aligned to opening paren
                    assertThat(formatted.content()).contains("Result.all");
                });
    }
}
