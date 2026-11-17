package org.pragmatica.jbct.lint.cst.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Boundary pins for the ternary and guard-shape scans of [GuardDominance] (#3065), driven through
/// [MapperSafety#containsUnguardedPartialOperation]: `true` means the body still fires. Each case is one mutation of PR #3150
/// (ids b_xxx in the comments) that the broader [GuardDominanceTest] did not kill.
class GuardDominanceScanTest {
    private static boolean fires(String code) {
        return MapperSafety.containsUnguardedPartialOperation(code);
    }

    // ---- every guard occurrence is tried, not only the first (b_d08, b_d09)

    @Test
    void laterNonEmptyGuard_isTriedAfterAnEarlierNonReachingOne() {
        assertFalse(fires("boolean a = !xs.isEmpty(); if (!xs.isEmpty()) return xs.getFirst();"));
    }

    @Test
    void laterEmptyGuard_isTriedAfterAnEarlierNonReachingOne() {
        assertFalse(fires("boolean e = xs.isEmpty(); if (xs.isEmpty()) return d; return xs.getFirst();"));
    }

    // ---- guard shapes start at an identifier boundary (b_g03). b_r14 / b_r15 (the trailing `\\b` of the size shapes) are
    // equivalent: every form reads the character right after the guard (`&&`, `?`, `)`), and the `\\b` only matters when that
    // character is a word character.

    @Test
    void guardGluedToAPrecedingWord_isNotAGuard() {
        assertTrue(fires("returnxs.isEmpty() ? d : xs.getFirst();"));
    }

    // ---- the receiver is matched literally, not as a regex (b_g04)

    @Test
    void receiverWithDollar_isMatchedLiterally() {
        assertFalse(fires("if (!x$.isEmpty()) return x$.getFirst();"));
    }

    // ---- a ternary without its colon reaches nothing (b_t04, b_t13)

    @Test
    void emptyGuardTernaryWithoutColon_doesNotReachTheAccessor() {
        assertTrue(fires("return xs.isEmpty() ? xs.getFirst()"));
    }

    @Test
    void nonEmptyGuardTernaryWithoutColon_doesNotReachTheAccessor() {
        assertTrue(fires("return !xs.isEmpty() ? xs.getFirst()"));
    }

    // ---- the else side ends with its expression (b_t08)

    @Test
    void emptyGuardTernary_accessorAfterTheElseExpression_isNotReached() {
        assertTrue(fires("String s = xs.isEmpty() ? d : e; use(xs.getFirst());"));
    }

    // ---- the then side ends at the first boundary token before the colon (b_c03, b_c04, b_c05)

    @Test
    void semicolonBeforeTheColon_endsTheThenSide() {
        assertTrue(fires("v = !xs.isEmpty() ? xs.getFirst(); w = c : d;"));
    }

    @Test
    void openingBraceBeforeTheColon_endsTheThenSide() {
        assertTrue(fires("return !xs.isEmpty() ? xs.getFirst() {a : b}"));
    }

    @Test
    void closingBraceBeforeTheColon_endsTheThenSide() {
        assertTrue(fires("v = !xs.isEmpty() ? xs.getFirst() } : d"));
    }

    // ---- the colon search: nesting, method references, end of text (b_c07, b_c09, b_c10, b_c14, b_c16, b_c17)

    @Test
    void nestedTernaryInsideParentheses_doesNotConsumeTheOuterColon() {
        assertFalse(fires("return !xs.isEmpty() ? g(a ? b : c, xs.getFirst()) : d;"));
    }

    @Test
    void colonInsideParentheses_isNotTheTernaryColon() {
        assertFalse(fires("return !xs.isEmpty() ? use(Foo::bar, xs.getFirst()) : d;"));
    }

    @Test
    void methodReferenceBeforeTheColon_isNotTheTernaryColon() {
        assertFalse(fires("return !xs.isEmpty() ? Foo::bar == xs.getFirst() : d;"));
    }

    @Test
    void colonAsLastCharacter_isTheTernaryColon() {
        assertFalse(fires("return !xs.isEmpty() ? xs.getFirst() :"));
    }

    // ---- where the else expression ends (b_p01 .. b_p08, with b_t08)

    @Test
    void closingParenOpenedBeforeTheGuard_endsTheElseExpression() {
        assertTrue(fires("return fmt(xs.isEmpty() ? d : e) + xs.getFirst();"));
    }

    @Test
    void commaInsideParentheses_doesNotEndTheElseExpression() {
        assertFalse(fires("return xs.isEmpty() ? d : f(a, xs.getFirst());"));
    }

    @Test
    void commaAtTheGuardsLevel_endsTheElseExpression() {
        assertTrue(fires("return f(xs.isEmpty() ? d : e, xs.getFirst());"));
    }

    @Test
    void closingBrace_endsTheElseExpression() {
        assertTrue(fires("{ v = xs.isEmpty() ? d : e } w = xs.getFirst();"));
    }

    @Test
    void elseExpressionRunningToTheEndOfText_reachesTheAccessor() {
        assertFalse(fires("return xs.isEmpty() ? d : xs.getFirst()"));
    }
}
