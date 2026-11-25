package org.pragmatica.jbct.format.flow;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.peg.token.TokenArray;

import static org.assertj.core.api.Assertions.assertThat;

/// The catch-up window of the comment claim (#3698): `FlowPrinter.findCodeToken` finds the token a printer emits among the next
/// source tokens, looking past at most `CATCH_UP_WINDOW` other code tokens and never counting comments. A token with no match in
/// the window is not found: a far match would print the comments in between at a wrong place.
class FlowCodeTokenWindowTest {
    private static TokenArray tokensOf(String source) {
        return new Java25Parser().parse(source)
                                 .unwrap()
                                 .cst()
                                 .tokens();
    }

    private static int firstIndexOf(TokenArray tokens, String text) {
        int at = 0;

        while (tokens.isTrivia(at) || !text.contentEquals(tokens.textAt(at))) {
            at++;
        }

        return at;
    }

    private static int codeTokensBefore(TokenArray tokens, int index) {
        int count = 0;

        for (int t = 0; t < index; t++) {
            if (!tokens.isTrivia(t)) {
                count++;
            }
        }

        return count;
    }

    @Test void findCodeToken_findsTheTokenExactlyWhenAtMostTheWindowOfCodeTokensPrecedeIt() {
        int found = 0;
        int notFound = 0;
        boolean atTheEdge = false;

        for (var prefix : new String[]{"class X { int a = 1", "class X { final int a = 1"}) {
            for (int plus = 0; plus < 14; plus++) {
                var tokens = tokensOf(prefix + " + 1".repeat(plus) + "; }");
                int target = firstIndexOf(tokens, ";");
                int skipped = codeTokensBefore(tokens, target);
                var result = FlowPrinter.findCodeToken(tokens, 0, ";");

                if (skipped <= 16) {
                    assertThat(result.or(-1)).as("%d code tokens before the target", skipped).isEqualTo(target);
                    found++;
                    atTheEdge |= skipped == 16;
                } else {
                    assertThat(result.isPresent()).as("%d code tokens before the target", skipped).isFalse();
                    notFound++;
                }
            }
        }

        assertThat(found).as("cases inside the window").isGreaterThan(0);
        assertThat(notFound).as("cases beyond the window").isGreaterThan(0);
        assertThat(atTheEdge).as("a case with exactly 16 code tokens before the target").isTrue();
    }

    @Test void findCodeToken_doesNotCountCommentsAgainstTheWindow() {
        var tokens = tokensOf("class X { /* a */ // b\n /** c */ int a = 1 /* d */ ; }");
        int target = firstIndexOf(tokens, ";");

        assertThat(FlowPrinter.findCodeToken(tokens, 0, ";").or(-1)).isEqualTo(target);
    }

    @Test void findCodeToken_startsAtTheGivenIndex() {
        var tokens = tokensOf("class X { int a; int b; }");
        int first = firstIndexOf(tokens, ";");

        assertThat(FlowPrinter.findCodeToken(tokens, first + 1, ";").or(-1)).isGreaterThan(first);
    }
}
