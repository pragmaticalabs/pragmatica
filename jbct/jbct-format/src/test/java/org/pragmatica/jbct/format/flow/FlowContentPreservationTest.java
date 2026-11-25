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

    @Test void singleLineIfBody_insideLambda_stableIndentation() {
        // Bug 3 (B3/B4): a single-line `if` inside a lambda body over-indented (~28 cols) + split.
        var src = "import java.util.Map;\n"
                  + "class X {\n"
                  + "    void f(Map<String, Integer> byAlias) {\n"
                  + "        byAlias.forEach((alias, count) -> {\n"
                  + "            if (count <= 1) {return;}\n"
                  + "            process(alias);\n"
                  + "        });\n"
                  + "    }\n"
                  + "    void process(String s) {}\n"
                  + "}\n";
        var once = format(src);
        var twice = fmt.format(new SourceFile(Path.of("Test.java"), once)).unwrap().content();
        assertThat(twice).as("lambda if-body formatting is idempotent").isEqualTo(once);
        assertThat(once).doesNotContain("                            if");  // no ~28-col over-indent
    }

    // --- Residual comment-deletion bugs (post-PR#242, 2026-06-09) -------------------------------
    // Each asserts the comment survives AND that formatting is idempotent. The exhaustive guard is
    // FlowCodebaseCheckTest#formatEntireCodebase_preservesAllComments (whole-repo, @Disabled).

    private void assertPreservedAndIdempotent(String src, String... comments) {
        var once = format(src);
        for (var c : comments) {
            assertThat(once).as("comment preserved: " + c).contains(c);
        }
        var twice = fmt.format(new SourceFile(Path.of("Test.java"), once)).unwrap().content();
        assertThat(twice).as("idempotent").isEqualTo(once);
    }

    @Test void s1_docComment_betweenEnumConstantsAndFirstMethod_preserved() {
        assertPreservedAndIdempotent(
            "enum E {\n    A, B;\n\n    /// parse the wire value\n    public static E fromWire(String w) { return A; }\n}\n",
            "/// parse the wire value");
    }

    @Test void s3_lineComment_beforeFirstSwitchCase_preserved() {
        assertPreservedAndIdempotent(
            "class X {\n    int f(Object o) {\n        return switch (o) {\n            // first-case rationale\n            case String s -> 1;\n            default -> 0;\n        };\n    }\n}\n",
            "// first-case rationale");
    }

    @Test void s4_lineComment_betweenCaseArms_preserved() {
        assertPreservedAndIdempotent(
            "class X {\n    void f(Object o) {\n        switch (o) {\n            case Integer i -> g(i);\n            // why the next arm is special\n            case String s -> h();\n            default -> {}\n        }\n    }\n    void g(int x) {}\n    void h() {}\n}\n",
            "// why the next arm is special");
    }

    @Test void s5_lineComment_betweenChainedCalls_preserved() {
        assertPreservedAndIdempotent(
            "class X {\n    Object f() {\n        return a().flatMap(x -> b())\n                  // chain rationale\n                  .onSuccess(x -> c());\n    }\n    java.util.Optional<Object> a() { return null; }\n}\n",
            "// chain rationale");
    }

    // #1718: a short chain that would fit on one line was joined onto it and its inter-link comments dropped.
    private static String chainSource(String body) {
        return "class X {\n    Object f() {\n" + body + "        return b;\n    }\n    Object foo;\n    Object g;\n}\n";
    }

    @Test void chainOwnLineComment_inLocalVarInitializer_preserved() {
        assertPreservedAndIdempotent(
            chainSource("        var b = foo.group(g)\n                   // keep this one\n                   .channel(K.class);\n"),
            "// keep this one");
    }

    @Test void chainOwnLineComment_afterNewReceiver_preserved() {
        assertPreservedAndIdempotent(
            chainSource("        var b = new Bootstrap().group(g)\n                               // keep after new\n                               .channel(K.class);\n"),
            "// keep after new");
    }

    @Test void chainBlockComment_betweenLinks_preserved() {
        assertPreservedAndIdempotent(
            chainSource("        var b = foo.group(g)\n                   /* block between */\n                   .channel(K.class);\n"),
            "/* block between */");
    }

    @Test void chainTrailingLineComment_afterFirstLink_preserved() {
        assertPreservedAndIdempotent(
            chainSource("        var b = foo.group(g) // trailing on first link\n                   .channel(K.class);\n"),
            "// trailing on first link");
    }

    @Test void chainOwnLineComment_inStatementPosition_preserved() {
        assertPreservedAndIdempotent(
            chainSource("        foo.group(g)\n           // statement-position rationale\n           .channel(K.class);\n"),
            "// statement-position rationale");
    }

    @Test void chainWithoutComment_stillJoinsOntoOneLine() {
        var out = format(chainSource("        var b = foo.group(g)\n                   .channel(K.class);\n"));
        assertThat(out).contains("var b = foo.group(g).channel(K.class);");
    }

    @Test void annotationTrailingComment_beforeMethod_preserved() {
        assertPreservedAndIdempotent(
            "class X {\n    @SuppressWarnings(\"x\") // netty future callback chain\n    void f() {}\n}\n",
            "// netty future callback chain");
    }

    @Test void recordComponentLeadingComment_preserved() {
        assertPreservedAndIdempotent(
            "record R(\n    long startTime,\n    // T0: blueprint change\n    long loadTime\n) {}\n",
            "// T0: blueprint change");
    }

    @Test void trailingCommentOnLastArgument_preserved() {
        assertPreservedAndIdempotent(
            "class X {\n    int f() {\n        return g(\n            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,\n            bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb // trailing on last arg\n        );\n    }\n    int g(int a, int b) { return a; }\n}\n",
            "// trailing on last arg");
    }

    @Test void ownLineCommentAsLastArgument_preserved() {
        assertPreservedAndIdempotent(
            "import java.util.Map;\nclass X {\n    Object f() {\n        return Map.of(\n            \"k\", \"v\"\n            // missing the other entries\n        );\n    }\n}\n",
            "// missing the other entries");
    }

    @Test void blockComment_inEmptyArrowBody_preserved() {
        assertPreservedAndIdempotent(
            "class X {\n    void f(Object o) {\n        switch (o) {\n            case Integer i -> g(i);\n            default -> { /* ignore unknown */ }\n        }\n    }\n    void g(int x) {}\n}\n",
            "/* ignore unknown */");
    }

    @Test void inlineBlockComment_midExpression_preserved() {
        assertPreservedAndIdempotent(
            "class X {\n    boolean f(int k) {\n        return k == 92 /* LB */ || k == 93 /* RB */;\n    }\n}\n",
            "/* LB */", "/* RB */");
    }

    @Test void trailingCommentOnSwitchArmExpression_preserved() {
        assertPreservedAndIdempotent(
            "class X {\n    String f(int k) {\n        return switch (k) {\n            case 1 -> \"a\";\n            default -> null; // not reactive — interceptor\n        };\n    }\n}\n",
            "// not reactive — interceptor");
    }

    @Test void danglingCommentsAtEndOfClassBody_preserved() {
        assertPreservedAndIdempotent(
            "class X {\n    void a() {}\n\n    // tail note line one\n    // tail note line two\n}\n",
            "// tail note line one", "// tail note line two");
    }

    // --- Comments at places no node owns (#3698, #3691): among the imports and after the last type ---

    @Test void commentBetweenImports_preservedAheadOfTheSortedImportBlock() {
        var src = "package p;\n\nimport java.util.Map;\n// why List is imported\nimport java.util.List;\n\nclass X {}\n";
        var once = format(src);

        assertPreservedAndIdempotent(src, "// why List is imported");
        assertThat(once.indexOf("// why List is imported")).as("comment printed ahead of the import block")
                                                           .isLessThan(once.indexOf("import java.util.List;"));
    }

    @Test void commentAfterTheLastType_preserved() {
        assertPreservedAndIdempotent("class X {}\n// a trailing comment after the class\n",
                                     "// a trailing comment after the class");
    }

    @Test void commentAfterTheLastImport_beforeTheFirstType_preserved() {
        var once = format("package p;\n\nimport java.util.List;\n// why X\nclass X {}\n");

        assertPreservedAndIdempotent("package p;\n\nimport java.util.List;\n// why X\nclass X {}\n", "// why X");
        assertThat(once.indexOf("// why X")).as("comment stays after the import block")
                                            .isGreaterThan(once.indexOf("import java.util.List;"))
                                            .isLessThan(once.indexOf("class X"));
    }
}
