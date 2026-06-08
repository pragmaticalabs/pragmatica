package org.pragmatica.jbct.format.flow;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// Reproduction + regression guards for the jbct-format content-corruption bugs
/// (bug report 2026-06-08): operator spacing, comment deletion, if-body re-indentation.
/// These assert CONTENT PRESERVATION, which the idempotency sweep does not.
class FlowContentPreservationTest {
    private final FlowFormatter fmt = FlowFormatter.flowFormatter(FormatterConfig.defaultConfig());

    private String format(String src) {
        return fmt.format(new SourceFile(Path.of("Test.java"), src)).unwrap().content();
    }

    // --- Bug 2: operator spacing ---------------------------------------------------------------

    @Test void comparisonLt_keepsTrailingSpace() {
        var out = format("class X {\n    int f(int a) {\n        if (a < 0) {\n            return 1;\n        }\n        return 0;\n    }\n}\n");
        assertThat(out).contains("a < 0");
    }

    @Test void comparisonGt_keepsTrailingSpace() {
        var out = format("class X {\n    int f(int a) {\n        if (a > 0) {\n            return 1;\n        }\n        return 0;\n    }\n}\n");
        assertThat(out).contains("a > 0");
    }

    @Test void leftShift_keepsSpaces() {
        var out = format("class X {\n    int f(int v, int n) {\n        return v << n;\n    }\n}\n");
        assertThat(out).contains("v << n");
    }

    @Test void rightShift_keepsSpaces() {
        var out = format("class X {\n    int f(int x) {\n        return x >> 1;\n    }\n}\n");
        assertThat(out).contains("x >> 1");
    }

    @Test void compoundLeftShiftAssign_preserved() {
        var out = format("class X {\n    void f(int x) {\n        x <<= 1;\n    }\n}\n");
        assertThat(out).contains("x <<= 1");
    }

    @Test void genericType_hasNoInnerSpace() {
        var out = format("import java.util.List;\nclass X {\n    List<String> g() {\n        return null;\n    }\n}\n");
        assertThat(out).contains("List<String>");
    }

    @Test void nestedGeneric_hasNoInnerSpace() {
        var out = format("import java.util.List;\nimport java.util.Map;\nclass X {\n    Map<String, List<Integer>> g() {\n        return null;\n    }\n}\n");
        assertThat(out).contains("Map<String, List<Integer>>");
    }

    // --- Bug 1: comment deletion ---------------------------------------------------------------

    @Test void lineComment_inEmptyMethodBody_preserved() {
        var out = format("class X {\n    void f() {\n        // intentionally empty - default listener prior to wiring\n    }\n}\n");
        assertThat(out).contains("intentionally empty");
    }

    @Test void lineComment_beforeClosingBrace_preserved() {
        var out = format("class X {\n    void f() {\n        doThing();\n        // trailing rationale comment\n    }\n}\n");
        assertThat(out).contains("trailing rationale comment");
    }

    // --- Bug 3: single-line if body indentation -----------------------------------------------

    @Test void singleLineIfBody_stableIndentation() {
        var src = "class X {\n    String f(int a) {\n        if (a < 0) {return \"neg\";}\n        return \"pos\";\n    }\n}\n";
        var once = format(src);
        var twice = fmt.format(new SourceFile(Path.of("Test.java"), once)).unwrap().content();
        assertThat(twice).as("if-body formatting is idempotent").isEqualTo(once);
        assertThat(once).doesNotContain("                            if");  // no ~28-col over-indent
    }
}
