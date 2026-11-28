package org.pragmatica.jbct.format.flow;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;
import org.pragmatica.peg.token.TokenArray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// The comment order invariant (#3698): comments are printed in source order, each exactly once, through one operation
/// (`CommentCursor.print`). Three kinds of test:
///
/// - the inputs where a direct emitter once printed comment N+1 before the still unprinted comment N (an empty body
///   whose first comment trails the `{` line, a comment pair after a chain link): each is formatted, in source order,
///   and formatting the output again changes nothing;
/// - the cursor itself: a layout that skips the catch-up makes the runtime check record both comments;
/// - the structure: no code in the printer reads the text of a token except the allow-listed reads of CODE tokens, so a
///   comment cannot reach the output other than through the cursor.
class FlowCommentOrderTest {
    private static final Pattern COMMENT = Pattern.compile("//[^\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private final FlowFormatter fmt = FlowFormatter.flowFormatter(FormatterConfig.defaultConfig());

    private static String inMethod(String body) {
        return "class X {\n    void m() {\n" + body + "    }\n}\n";
    }

    private String formatted(String input) {
        return fmt.format(new SourceFile(Path.of("Test.java"), input))
                  .unwrap()
                  .content();
    }

    private void assertInOrderOnceAndIdempotent(String input) {
        var once = formatted(input);

        assertThat(comments(once)).as("comments of the output, in order\n%s", once)
                                  .isEqualTo(comments(input));
        assertThat(formatted(once)).as("second pass")
                                   .isEqualTo(once);
    }

    private static List<String> comments(String source) {
        var found = new ArrayList<String>();
        var matcher = COMMENT.matcher(source);

        while (matcher.find()) {
            found.add(matcher.group().stripTrailing());
        }

        return found;
    }

    @Test void emptyMethodBody_trailingCommentThenOwnLineComment_isFormattedInOrder() {
        assertInOrderOnceAndIdempotent(inMethod("").replace("m() {\n", "m() { // a\n        // b\n"));
    }

    @Test void emptyClassBody_trailingCommentThenOwnLineComment_isFormattedInOrder() {
        assertInOrderOnceAndIdempotent("class X { // a\n    // b\n}\n");
    }

    @Test void emptyIfBody_trailingCommentThenOwnLineComment_isFormattedInOrder() {
        assertInOrderOnceAndIdempotent(inMethod("        if (a) { // a\n            // b\n        }\n"));
    }

    @Test void emptyBody_threeComments_areFormattedInOrder() {
        assertInOrderOnceAndIdempotent("class X {\n    void m() { // a\n        // b\n        // c\n    }\n}\n");
    }

    @Test void emptyBody_trailingCommentThenBlockComment_isFormattedInOrder() {
        assertInOrderOnceAndIdempotent("class X {\n    void m() { // a\n        /* b */\n    }\n}\n");
    }

    @Test void emptyBody_blockCommentThenOwnLineComment_isFormattedInOrder() {
        assertInOrderOnceAndIdempotent("class X {\n    void m() { /* a */\n        // b\n    }\n}\n");
    }

    @Test void chain_blockCommentThenLineCommentAfterALink_isFormattedInOrder() {
        assertInOrderOnceAndIdempotent(inMethod("        foo.bar() /* M1 */ // M2\n            .baz();\n"));
    }

    @Test void chain_withArgumentsBlockCommentThenLineCommentAfterALink_isFormattedInOrder() {
        assertInOrderOnceAndIdempotent(inMethod("        x.a().b(1) /* M1 */ // M2\n            .d();\n"));
    }

    @Test void statement_blockCommentThenLineCommentAfterTheLastStatement_isFormattedInOrder() {
        assertInOrderOnceAndIdempotent(inMethod("        a(); /* x */ // y\n"));
    }

    @Test void statement_blockCommentThenLineCommentBetweenStatements_isFormattedInOrder() {
        assertInOrderOnceAndIdempotent(inMethod("        a(); /* x */ // y\n        b();\n"));
    }

    @Test void cursor_layoutThatSkipsTheCatchUp_isRefusedNamingBothComments() {
        var source = "class X {\n    void m() { // first\n        // second\n    }\n}\n";
        var tokens = new Java25Parser().parse(source)
                                       .unwrap()
                                       .cst()
                                       .tokens();
        var comments = commentIndexes(tokens);
        var written = new ArrayList<String>();
        var cursor = CommentCursor.commentCursor(tokens, source, written::add, slot -> {});

        cursor.print(comments.get(1), slot -> slot.write(""));

        assertThat(written).containsExactly("// second");
        assertThat(cursor.violation()).isEqualTo(Option.some(new CommentCursor.Violation(3,
                                                                                         "comment `// second` (input line 3) would be printed before the earlier comment `// first` (input line 2)")));
    }

    @Test void cursor_print_printsEveryEarlierCommentFirstAndEachOnce() {
        var source = "class X {\n    void m() { // first\n        // second\n    }\n}\n";
        var tokens = new Java25Parser().parse(source)
                                       .unwrap()
                                       .cst()
                                       .tokens();
        var comments = commentIndexes(tokens);
        var written = new ArrayList<String>();
        var cursor = CommentCursor.commentCursor(tokens, source, written::add, slot -> slot.write(""));

        cursor.print(comments.get(1), slot -> slot.write(""));
        cursor.print(comments.get(0), slot -> slot.write(""));
        cursor.print(comments.get(1), slot -> slot.write(""));

        assertThat(written).containsExactly("// first", "// second");
        assertThat(cursor.violation()).isEqualTo(Option.none());
    }

    @Test void printer_readsTokenTextOnlyForCodeTokens_soNoCommentBypassesTheCursor() throws IOException {
        var source = Files.readString(Path.of("src/main/java/org/pragmatica/jbct/format/flow/FlowPrinter.java"));
        var reads = source.lines()
                          .map(String::strip)
                          .filter(line -> line.contains("textAt("))
                          .toList();

        assertThat(reads).as("""
            every read of token text in FlowPrinter. A comment's text is read only inside CommentCursor.Slot.write, so \
            a new line here must read a CODE token: add it to CODE_TOKEN_READS. If it reads a comment, route it through \
            CommentCursor.print instead""")
                         .containsExactlyInAnyOrderElementsOf(CODE_TOKEN_READS);
    }

    private static final List<String> CODE_TOKEN_READS = List.of("if (!tokens.isTrivia(t) && \"}\".contentEquals(tokens.textAt(t))) {",
                                                                 "emitToken(tokens.textAt(t).toString());",
                                                                 "emitToken(tokens.textAt(t).toString());",
                                                                 "tokens.textAt(t).toString());",
                                                                 "sb.append(tokens.textAt(t));",
                                                                 "if (!tokens.isTrivia(t)) emitToken(tokens.textAt(t).toString());",
                                                                 "if (!tokens.isTrivia(t)) emitToken(tokens.textAt(t).toString());",
                                                                 "while (close >= afterToken && (tokens.isTrivia(close) || !\"}\".contentEquals(tokens.textAt(close)))) {",
                                                                 "var txt = tokens.textAt(t).toString();",
                                                                 "var txt = tokens.textAt(t).toString();",
                                                                 "return text.contentEquals(tokens.textAt(t));",
                                                                 "if (!tokens.isTrivia(t) && text.contentEquals(tokens.textAt(t))) {",
                                                                 "var s = tokens.textAt(t).toString();",
                                                                 "return \"(\".equals(tokens.textAt(t).toString());",
                                                                 "if (\".\".equals(tokens.textAt(t).toString())) {",
                                                                 "suffix += tokens.textAt(t).length();",
                                                                 "if (\"&&\".equals(tokens.textAt(t).toString())) operatorCount++;",
                                                                 "while (at < tokens.count() && skipped <= CATCH_UP_WINDOW && (tokens.isTrivia(at) || !token.contentEquals(tokens.textAt(at)))) {");

    private static List<Integer> commentIndexes(TokenArray tokens) {
        var found = new ArrayList<Integer>();

        for (int t = 0; t < tokens.count(); t++) {
            int kind = tokens.kindAt(t);

            if (tokens.isTrivia(t) && kind >= 1 && kind <= 4) {
                found.add(t);
            }
        }

        return found;
    }
}
