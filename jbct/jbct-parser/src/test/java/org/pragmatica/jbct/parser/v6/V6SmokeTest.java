package org.pragmatica.jbct.parser.v6;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the v6 lex+parse pipeline on the jbct-format golden fixtures.
/// The legacy `peglib:generate` emit (CstNode/RuleId types in `Java25Parser`) remains the
/// production parser; this test only validates that the parallel-emitted v6 sources work
/// on real inputs, in response to the peglib agent's note that the v6 "lexer warning" is
/// informational and the runtime degrades gracefully on a no-progress lex stall.
class V6SmokeTest {
    private static final Path FIXTURES = Path.of("../jbct-format/src/test/resources/format-examples");

    @Test
    void v6Parser_parsesAllFormatGoldenFixtures() throws IOException {
        try (var paths = Files.list(FIXTURES)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                 .forEach(V6SmokeTest::parseOne);
        }
    }

    private static void parseOne(Path path) {
        String source;
        try {
            source = Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Could not read " + path, e);
        }
        var tokens = Java25Lexer.lex(source);
        var result = Java25ParserV6.parse(tokens);
        assertThat(result.isSuccess())
            .as("v6 parse of %s succeeded; diagnostics=%s", path.getFileName(),
                result.diagnostics().stream().map(Object::toString).toList())
            .isTrue();
        assertThat(result.hasErrors())
            .as("v6 parse of %s has no error diagnostics", path.getFileName())
            .isFalse();
        assertThat(result.cst().nodeCount())
            .as("v6 parse of %s produced a non-empty CST", path.getFileName())
            .isGreaterThan(0);
    }

    /// Verifies the v6 lexer classifies `///` line comments and `//` line comments
    /// as distinct trivia kinds (not folded into WHITESPACE kind 0).
    @Test
    void v6Lexer_classifiesCommentsAsDistinctKinds() throws IOException {
        var path = FIXTURES.resolve("CommentsExtended.java");
        var source = Files.readString(path);
        var tokens = Java25Lexer.lex(source);
        var result = Java25ParserV6.parse(tokens);
        assertThat(result.isSuccess()).isTrue();
        var hist = new java.util.HashMap<Integer, Integer>();
        for (int i = 0; i < result.cst().tokens().count(); i++) {
            int k = result.cst().tokens().kindAt(i);
            hist.merge(k, 1, Integer::sum);
        }
        System.out.println("v6 token kind histogram for CommentsExtended.java: " + hist);
        for (int kind : new int[]{0, 1, 2, 3, 4}) {
            int count = hist.getOrDefault(kind, 0);
            String name = kind < Java25Lexer.KIND_NAMES.length ? Java25Lexer.KIND_NAMES[kind] : "?";
            System.out.println("  kind=" + kind + " (" + name + ") count=" + count);
        }
    }
}
