package org.pragmatica.jbct.lint.cst.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins for the receiver-escape rule of [GuardDominance] (#3065, PR #3150), driven through
/// [MapperSafety#containsUnguardedPartialOperation]: `true` means the body still fires. The guard is
/// `!xs.isEmpty() && <middle> && xs.getFirst()`; a middle that lets `xs` escape makes the guard undecidable (fires), a middle
/// that merely mentions `xs` in parentheses does not (no finding).
class GuardDominanceEscapeOpenerTest {
    private static boolean firesWithMiddle(String middle) {
        return MapperSafety.containsUnguardedPartialOperation("return !xs.isEmpty() && " + middle + " && xs.getFirst() > 0;");
    }

    // ---- not an escape: the guard still dominates

    @Test
    void parenthesisedReceiver_isNotAnEscape() {
        assertFalse(firesWithMiddle("(xs).size() > 0"));
    }

    @Test
    void parenthesisedReceiverAfterCast_isNotAnEscape() {
        assertFalse(firesWithMiddle("((List<String>) (xs)).size() > 0"));
    }

    @Test
    void lambdaParameterListEndingInReceiver_isNotAnEscape() {
        assertFalse(firesWithMiddle("run((xs) -> 1) > 0"));
    }

    @Test
    void lambdaParameterListEndingInReceiverAfterComma_isNotAnEscape() {
        assertFalse(firesWithMiddle("run((a, xs) -> 1) > 0"));
    }

    @Test
    void parenthesisedReceiverAfterLambdaArrow_isNotAnEscape() {
        assertFalse(firesWithMiddle("run(a -> (xs).size()) > 0"));
    }

    @Test
    void parenthesisedReceiverAfterStatementKeyword_isNotAnEscape() {
        assertFalse(MapperSafety.containsUnguardedPartialOperation("if (!xs.isEmpty()) return (xs).size() + xs.getFirst();"));
    }

    // ---- escape: the guard is undecidable and the rule keeps firing

    @Test
    void firstCallArgument_isAnEscape() {
        assertTrue(firesWithMiddle("f(xs) > 0"));
    }

    @Test
    void laterCallArgument_isAnEscape() {
        assertTrue(firesWithMiddle("f(a, xs) > 0"));
    }

    /// Receiver followed by a comma, not a closing parenthesis (mutant m4: `[,)]` -> `\)`).
    @Test
    void receiverFollowedByComma_isAnEscape() {
        assertTrue(firesWithMiddle("f(xs, b) > 0"));
    }

    @Test
    void middleCallArgument_isAnEscape() {
        assertTrue(firesWithMiddle("f(a,xs,b) > 0"));
    }

    /// Whitespace between the receiver and `)` (mutant m5b: drop `\s*` before the closer).
    @Test
    void receiverWithSpacesInsideParentheses_isAnEscape() {
        assertTrue(firesWithMiddle("f( xs ) > 0"));
    }

    @Test
    void receiverOnItsOwnLinesBetweenArguments_isAnEscape() {
        assertTrue(firesWithMiddle("f(1,\nxs\n,2) > 0"));
    }

    @Test
    void spaceBetweenCalleeAndParenthesis_isAnEscape() {
        assertTrue(firesWithMiddle("f (xs) > 0"));
    }

    /// A callee that merely starts with a statement keyword is still a callee (`returnValue(xs)`, not `return (xs)`).
    @Test
    void calleeStartingWithStatementKeyword_isAnEscape() {
        assertTrue(firesWithMiddle("returnValue(xs) > 0"));
    }

    @Test
    void constructorArgument_isAnEscape() {
        assertTrue(firesWithMiddle("new Foo(xs).n() > 0"));
    }

    @Test
    void diamondConstructorArgument_isAnEscape() {
        assertTrue(firesWithMiddle("new Foo<>(xs).n() > 0"));
    }

    @Test
    void methodReferenceTarget_isAnEscape() {
        assertTrue(firesWithMiddle("g(xs::add) > 0"));
    }

    @Test
    void methodReferenceTargetWithSpaces_isAnEscape() {
        assertTrue(firesWithMiddle("g(xs :: add) > 0"));
    }

    @Test
    void lambdaParameterListWithReceiverBetweenOthers_isAnEscape() {
        assertTrue(firesWithMiddle("run((a, xs, b) -> 1) > 0"));
    }

    @Test
    void receiverInsideTernaryArgument_isAnEscape() {
        assertTrue(firesWithMiddle("f(a ? xs : ys) > 0"));
    }

    @Test
    void identifierContainingReceiverName_isNotAnEscape() {
        assertFalse(firesWithMiddle("f(xsList) > 0"));
    }
}
