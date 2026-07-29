package org.pragmatica.jbct.lint.cst.rules;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Source-masking helpers shared by the mapper-safety rule family ([MapperSafety#blankStrings],
/// [MapperSafety#blankNonCode], [MapperSafety#maskForOps]).
///
/// The huge-literal cases are the #540 regression. [MapperSafety#STRING_LITERAL] used to compile
/// its bulk alternatives to a `Loop` node that recurses once per *character*, so masking a literal
/// longer than ~7250 characters overflowed the stack — the 7481-character scaffold text block in
/// `SliceProjectInitializer` is what took `jbct score jbct` down. Possessive matching makes the
/// depth proportional to the number of `"` / `\` characters instead of the literal's length.
///
/// The masking runs on a thread with an explicit 1 MB stack rather than on the default one: the
/// macOS main thread gets the 8 MB process stack, which is generous enough to hide a regression,
/// and a fixed budget keeps the test a real sensor no matter what `-Xss` the JVM was started with.
/// Measured budget at 1 MB: the fixed pattern tolerates ~750 quotes inside a 12 000-character text
/// block, so the 200-quote case below carries roughly a 3.7x margin, while every case here
/// overflows with the pre-fix pattern.
class MapperSafetyTest {
    /// Far past the ~7250-character limit measured for the pre-fix pattern.
    private static final int HUGE = 12_000;

    private static final int SMALL_STACK = 1024 * 1024;

    /// Filler is `z` so that `contains` assertions are not satisfied by the surrounding `var` /
    /// `drop()` scaffolding.
    private static final String HUGE_BODY = "z".repeat(HUGE);

    /// 12 000 characters carrying 200 interior quotes — long enough to have overflowed before the
    /// fix, quote-dense enough to exercise the `"(?!"")` alternative that still costs one iteration
    /// per quote.
    private static final String QUOTED_BODY = ("z".repeat(59) + "\"").repeat(200);

    @Test
    void blankStrings_hugeTextBlock_completesWithoutStackOverflow() throws Exception {
        var masked = onSmallStack(MapperSafety::blankStrings, hugeTextBlock(HUGE_BODY));

        assertFalse(masked.contains("z"), "text-block content must be blanked");
    }

    @Test
    void blankStrings_hugeTextBlock_keepsSurroundingCodeIntact() throws Exception {
        var masked = onSmallStack(MapperSafety::blankStrings, hugeTextBlock(HUGE_BODY));

        assertTrue(masked.startsWith("var x = "), "code before the literal must survive");
        assertTrue(masked.endsWith("; drop();"), "code after the literal must survive");
    }

    @Test
    void blankNonCode_hugeTextBlock_preservesOffsetsAndNewlines() throws Exception {
        var source = hugeTextBlock(HUGE_BODY);
        var masked = onSmallStack(MapperSafety::blankNonCode, source);

        assertEquals(source.length(), masked.length(), "offsets must stay aligned");
        assertEquals(newlineOffsets(source), newlineOffsets(masked), "line numbering must stay aligned");
    }

    @Test
    void maskForOps_hugeTextBlock_fillsLiteralWithHash() throws Exception {
        var source = hugeTextBlock(HUGE_BODY);
        var masked = onSmallStack(MapperSafety::maskForOps, source);

        assertEquals(source.length(), masked.length(), "offsets must stay aligned");
        assertEquals(newlineOffsets(source), newlineOffsets(masked), "line numbering must stay aligned");
        assertFalse(masked.contains("z"), "text-block content must be masked");
        assertTrue(masked.contains("#"), "masked literal must use the non-space fill");
    }

    @Test
    void blankNonCode_hugeTextBlockWithInteriorQuotes_completesWithoutStackOverflow() throws Exception {
        var source = hugeTextBlock(QUOTED_BODY);
        var masked = onSmallStack(MapperSafety::blankNonCode, source);

        assertFalse(masked.contains("z"), "interior quotes must not terminate the text block");
        assertEquals(source.length(), masked.length(), "offsets must stay aligned");
    }

    @Test
    void blankStrings_hugeSingleLineLiteral_completesWithoutStackOverflow() throws Exception {
        var masked = onSmallStack(MapperSafety::blankStrings, "var x = \"" + HUGE_BODY + "\"; drop();");

        assertFalse(masked.contains("z"), "literal content must be blanked");
        assertTrue(masked.endsWith("; drop();"), "code after the literal must survive");
    }

    @Test
    void containsPartialOperation_hugeTextBlockHidingThrow_reportsNothing() throws Exception {
        var source = hugeTextBlock("throw new IllegalStateException(); ".repeat(HUGE / 35));

        assertEquals("false", onSmallStack(MapperSafetyTest::partialOperation, source),
                     "a partial op inside a literal must not be reported");
    }

    @Test
    void blankStrings_escapedQuotes_blanksWholeLiteral() {
        var masked = MapperSafety.blankStrings("var x = \"he said \\\"hi\\\"\"; drop();");

        assertFalse(masked.contains("said"), "escaped-quote literal must be blanked as one unit");
        assertTrue(masked.endsWith("; drop();"), "code after the literal must survive");
    }

    @Test
    void blankStrings_textBlockWithInnerQuotes_blanksWholeLiteral() {
        var masked = MapperSafety.blankStrings("var x = \"\"\"\nsay \"\" now\n\"\"\"; drop();");

        assertFalse(masked.contains("say"), "inner quotes must not terminate the text block");
        assertTrue(masked.endsWith("; drop();"), "code after the literal must survive");
    }

    @Test
    void blankNonCode_literalAndComment_blanksBothAndKeepsLength() {
        var source = "var x = \"lit\"; // note \"quoted\"\ndrop();";
        var masked = MapperSafety.blankNonCode(source);

        assertEquals(source.length(), masked.length(), "offsets must stay aligned");
        assertFalse(masked.contains("lit"), "literal must be blanked");
        assertFalse(masked.contains("note"), "comment must be blanked");
        assertTrue(masked.endsWith("drop();"), "code after the comment must survive");
    }

    @Test
    void maskForOps_stringArgument_keepsParenthesesNonEmpty() {
        var masked = MapperSafety.maskForOps("map.get(\"key\")");

        assertFalse(masked.contains("get()"), "a string argument must not look like empty parens");
        assertTrue(masked.contains("#"), "a string argument must be masked as present-but-opaque");
    }

    private static String partialOperation(String source) {
        return String.valueOf(MapperSafety.containsPartialOperation(source));
    }

    private static String hugeTextBlock(String body) {
        return "var x = \"\"\"\n" + body + "\n\"\"\"; drop();";
    }

    private static List<Integer> newlineOffsets(String text) {
        return IntStream.range(0, text.length())
                        .filter(i -> text.charAt(i) == '\n')
                        .boxed()
                        .toList();
    }

    /// Runs `masker` on a thread with a fixed, deliberately small stack so per-character regex
    /// recursion fails loudly instead of hiding inside a generous default stack.
    private static String onSmallStack(UnaryOperator<String> masker, String source) throws Exception {
        var result = new String[1];
        var failure = new Throwable[1];
        var thread = new Thread(null, () -> result[0] = masker.apply(source), "small-stack", SMALL_STACK);

        thread.setUncaughtExceptionHandler((ignored, error) -> failure[0] = error);
        thread.start();
        thread.join();

        assertNull(failure[0], () -> "masking must not blow the stack, but got: " + failure[0]);

        return result[0];
    }
}
