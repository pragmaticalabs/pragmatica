package org.pragmatica.jbct.format;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.format.JbctFormatter.jbctFormatter;

/// The content-preservation invariant (#3691): a formatted text is returned only when its comment sequence and its
/// non-whitespace, non-comment token sequence equal the input's. Unit rows drive [ContentGuard] with a hand-written
/// "formatted" text, so they do not depend on any printer gap; the end-to-end rows push the five positions of #3691
/// through the formatter.
class ContentGuardTest {
    private static final String OK = "OK";

    // --- unit rows: what the guard accepts

    @Test
    void identicalText_isAccepted() {
        var text = "class A {\n    // c\n    int x; /* b */\n}\n";

        assertThat(verdict(text, text)).isEqualTo(OK);
    }

    @Test
    void whitespaceOnlyChanges_areAccepted() {
        assertThat(verdict("class A {\n  int   x ;\n\n\n}\n", "class A {\n    int x;\n}\n")).isEqualTo(OK);
    }

    @Test
    void trailingWhitespaceOfAComment_isNotContent() {
        assertThat(verdict("class A {\n    // c   \n    int x;\n}\n", "class A {\n    // c\n    int x;\n}\n")).isEqualTo(OK);
        assertThat(verdict("class A {\n    /* a  \n       b */\n    int x;\n}\n",
                           "class A {\n    /* a\n       b */\n    int x;\n}\n")).isEqualTo(OK);
    }

    @Test
    void docBlockReindentation_isNotContent() {
        var input = "class A {\n/**\n * Doc.\n *   indented\n */\nint x;\n}\n";
        var output = "class A {\n    /**\n     * Doc.\n     *   indented\n     */\n    int x;\n}\n";

        assertThat(verdict(input, output)).isEqualTo(OK);
    }

    @Test
    void crlfInput_isAcceptedAgainstLfOutput() {
        var input = "class A {\r\n    // c\r\n    /* a\r\n b */\r\n    String s = \"\"\"\r\n        t\r\n        \"\"\";\r\n}\r\n";
        var output = "class A {\n    // c\n    /* a\n b */\n    String s = \"\"\"\n        t\n        \"\"\";\n}\n";

        assertThat(verdict(input, output)).isEqualTo(OK);
    }

    @Test
    void commentMarkersInsideStringLiteralsAndTextBlocks_areTokensNotComments() {
        var text = "class A {\n    String a = \"// not a comment\";\n    String b = \"/* nor this */\";\n"
                   + "    String c = \"\"\"\n        // text block\n        /* too */\n        \"\"\";\n}\n";

        assertThat(verdict(text, text)).isEqualTo(OK);
    }

    // --- unit rows: what the guard refuses, with the first-difference text

    @Test
    void lostLineComment_namesFileLineAndText() {
        var input = "class A {\n    int x; // keep me\n    int y;\n}\n";
        var output = "class A {\n    int x;\n    int y;\n}\n";

        assertThat(verdict(input, output)).isEqualTo("Formatter refused Test.java: comment lost: `// keep me` (input line 2);"
                                                     + " the file was left untouched");
    }

    @Test
    void lostBlockComment_isRefused() {
        var input = "class A {\n    int x;\n    /* gone */\n    int y;\n}\n";
        var output = "class A {\n    int x;\n    int y;\n}\n";

        assertThat(verdict(input, output)).contains("comment lost: `/* gone */` (input line 3)");
    }

    @Test
    void lostFirstOfSeveral_isReportedAtTheFirstLostOne() {
        var input = "class A {\n    // one\n    int x; // two\n    // three\n    int y;\n}\n";
        var output = "class A {\n    int x; // two\n    // three\n    int y;\n}\n";

        assertThat(verdict(input, output)).contains("comment lost: `// one` (input line 2)");
    }

    @Test
    void changedCommentText_isRefused() {
        var input = "class A {\n    // before\n    int x;\n}\n";
        var output = "class A {\n    // after\n    int x;\n}\n";

        assertThat(verdict(input, output)).contains("comment changed: `// before` became `// after` (input line 2)");
    }

    @Test
    void addedComment_isRefused() {
        var input = "class A {\n    int x;\n}\n";
        var output = "class A {\n    int x; // new\n}\n";

        assertThat(verdict(input, output)).contains("comment added: `// new`");
    }

    @Test
    void reorderedComments_areRefused() {
        var input = "class A {\n    // first\n    // second\n    int x;\n}\n";
        var output = "class A {\n    // second\n    // first\n    int x;\n}\n";

        assertThat(verdict(input, output)).contains("comment changed: `// first` became `// second` (input line 2)");
    }

    @Test
    void plainBlockCommentReindentation_isNotDocReindentation() {
        var input = "class A {\n/* a\n   b */\nint x;\n}\n";
        var output = "class A {\n/* a\n b */\nint x;\n}\n";

        assertThat(verdict(input, output)).contains("comment changed");
    }

    @Test
    void importReorder_isAcceptedAsTheFormatterSortsImports() {
        var input = "import org.b.B;\nimport static org.a.A.f;\nimport java.util.List;\n\nclass C {}\n";
        var output = "import java.util.List;\n\nimport org.b.B;\n\nimport static org.a.A.f;\n\nclass C {}\n";

        assertThat(verdict(input, output)).isEqualTo(OK);
    }

    @Test
    void lostImport_isRefused() {
        assertThat(verdict("import java.util.List;\nimport java.util.Map;\n\nclass C {}\n",
                           "import java.util.Map;\n\nclass C {}\n")).contains("import lost: `import java . util . List ;` (input line 1)");
    }

    @Test
    void changedImport_isRefused() {
        assertThat(verdict("import java.util.List;\n\nclass C {}\n",
                           "import java.util.Set;\n\nclass C {}\n")).contains("import changed: `import java . util . List ;` became `import java . util . Set ;`");
    }

    @Test
    void lostToken_isRefused() {
        assertThat(verdict("class A {\n    int x;\n}\n", "class A {\n    int ;\n}\n")).contains("token lost: `x` (input line 2)");
    }

    @Test
    void changedToken_isRefused() {
        assertThat(verdict("class A {\n    int x;\n}\n", "class A {\n    int y;\n}\n")).contains("token changed: `x` became `y` (input line 2)");
    }

    @Test
    void changedStringLiteral_isATokenChangeNotACommentChange() {
        var input = "class A {\n    String s = \"// one\";\n}\n";
        var output = "class A {\n    String s = \"// two\";\n}\n";

        assertThat(verdict(input, output)).contains("token changed: `\"// one\"` became `\"// two\"`");
    }

    @Test
    void commentLostNextToStringWithMarker_isStillRefused() {
        var input = "class A {\n    String s = \"// x\"; // real\n}\n";
        var output = "class A {\n    String s = \"// x\";\n}\n";

        assertThat(verdict(input, output)).contains("comment lost: `// real` (input line 2)");
    }

    @Test
    void commentIsCheckedBeforeToken() {
        var input = "class A {\n    // c\n    int x;\n}\n";
        var output = "class A {\n    int y;\n}\n";

        assertThat(verdict(input, output)).contains("comment lost");
    }

    // --- end-to-end rows: the five positions of #3691 (each is a printer gap at this base; the guard turns it into a refusal)

    @Test
    void position1_commentBeforeSemicolonOfChainedReturn_isRefusedNotDeleted() {
        assertRefused("class A {\n    String f(Object o) {\n        return o.toString()\n                .trim() // POS1\n                ;\n    }\n}\n", "// POS1");
    }

    @Test
    void position2_commentBeforeSemicolonOfLocalInitializer_isRefusedNotDeleted() {
        assertRefused("class A {\n    void f(Object o) {\n        var x = o.toString() // POS2\n                ;\n    }\n}\n", "// POS2");
    }

    @Test
    void position3_commentAfterLeftOperandOfBinaryExpression_isRefusedNotDeleted() {
        assertRefused("class A {\n    int f(int a, int b) {\n        return a // POS3\n               + b;\n    }\n}\n", "// POS3");
    }

    @Test
    void position4_commentAfterTernaryCondition_isRefusedNotDeleted() {
        assertRefused("class A {\n    int f(boolean c) {\n        return c // POS4\n               ? 1 : 2;\n    }\n}\n", "// POS4");
    }

    @Test
    void position5_commentAfterTernaryTrueArm_isRefusedNotDeleted() {
        assertRefused("class A {\n    int f(boolean c) {\n        return c ? 1 // POS5\n                 : 2;\n    }\n}\n", "// POS5");
    }

    @Test
    void keptPosition_commentsInsideCallArgumentList_stillFormat() {
        var source = "class A {\n    void f() {\n        g(1, // KEPT1\n          2 /* KEPT2 */);\n    }\n\n    void g(int a, int b) {}\n}\n";
        var result = jbctFormatter().format(file(source));

        assertThat(result.isSuccess()).as(message(result)).isTrue();
        assertThat(result.unwrap().content()).contains("// KEPT1").contains("/* KEPT2 */");
    }

    @Test
    void refusalNamesTheFileAndTheInputLine() {
        var message = message(jbctFormatter().format(file("class A {\n    int f(boolean c) {\n        return c // POS4\n               ? 1 : 2;\n    }\n}\n")));

        assertThat(message).startsWith("Formatter refused Test.java: comment lost: `// POS4` (input line 3)");
    }

    @Test
    void isFormatted_ofARefusedFile_isAFailureNotFalse() {
        var result = jbctFormatter().isFormatted(file("class A {\n    int f(boolean c) {\n        return c // POS4\n               ? 1 : 2;\n    }\n}\n"));

        assertThat(result.isFailure()).isTrue();
    }

    private static void assertRefused(String source, String comment) {
        var message = message(jbctFormatter().format(file(source)));

        assertThat(message).startsWith("Formatter refused Test.java: comment lost: `" + comment + "`")
                           .endsWith("the file was left untouched");
    }

    private static String verdict(String input, String output) {
        return message(ContentGuard.verify("Test.java", input, output).map(_ -> OK));
    }

    private static String message(Result<?> result) {
        return result.fold(Cause::message, _ -> OK);
    }

    private static SourceFile file(String content) {
        return new SourceFile(Path.of("Test.java"), content);
    }
}
