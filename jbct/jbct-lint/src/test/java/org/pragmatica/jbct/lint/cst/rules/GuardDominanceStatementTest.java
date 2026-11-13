package org.pragmatica.jbct.lint.cst.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Boundary pins for the statement scans of [GuardDominance] (`if`-then reach, early exit, write detection, operand openers),
/// driven through [MapperSafety#containsUnguardedPartialOperation]: `true` means the body still fires. Each case is one
/// mutation of PR #3150 (ids b_xxx / d_xxx / f_opN in the comments) that [GuardDominanceTest] and [GuardDominanceScanTest]
/// did not kill.
class GuardDominanceStatementTest {
    private static boolean fires(String code) {
        return MapperSafety.containsUnguardedPartialOperation(code);
    }

    // ---- the guard must be the whole condition of an `if` (b_k01, b_z01)

    @Test
    void nonEmptyGuardInWhileCondition_isNotAnIfThen() {
        assertTrue(fires("while (!xs.isEmpty()) use(xs.getFirst());"));
    }

    @Test
    void emptyGuardInCompoundIfCondition_doesNotGuardTheCodeAfterIt() {
        assertTrue(fires("if (xs.isEmpty() || a) return d; return xs.getFirst();"));
    }

    // ---- the `if` of an early exit begins a statement (b_b02, b_b03, b_b04, b_v05)

    @Test
    void earlyExitIfAtTextStart_beginsAStatement() {
        assertFalse(fires("if (xs.isEmpty()) return d; return xs.getFirst();"));
    }

    @Test
    void earlyExitIfAfterLeadingBlanks_beginsAStatement() {
        assertFalse(fires("  if (xs.isEmpty()) return d; return xs.getFirst();"));
    }

    @Test
    void earlyExitIfAfterClosingBrace_beginsAStatement() {
        assertFalse(fires("if (a) { run(); } if (xs.isEmpty()) return d; return xs.getFirst();"));
    }

    @Test
    void earlyExitIfAfterOpeningBrace_beginsAStatement() {
        assertFalse(fires("{ if (xs.isEmpty()) return d; return xs.getFirst(); }"));
    }

    // ---- a block exits only through its last statement (b_q03, b_q06, b_q07)

    @Test
    void exitBlockWithoutBlankBeforeClosingBrace_exits() {
        assertFalse(fires("if (xs.isEmpty()) {return d;} return xs.getFirst();"));
    }

    @Test
    void exitBlockWhoseLastStatementFollowsANestedBlock_exits() {
        assertFalse(fires("if (xs.isEmpty()) { if (a) { x(); } return d; } return xs.getFirst();"));
    }

    @Test
    void exitBlockWhoseLastStatementFollowsAnotherStatement_exits() {
        assertFalse(fires("if (xs.isEmpty()) { log(); return d; } return xs.getFirst();"));
    }

    // ---- a block that closes between the exit and the accessor ends the guard (b_l03, b_l07, b_l09)

    @Test
    void blockOpenedAndClosedAfterTheExit_doesNotEndTheGuard() {
        assertFalse(fires("if (xs.isEmpty()) return d; if (a) { run(); } return xs.getFirst();"));
    }

    @Test
    void braceOnlyBlockAfterTheExit_doesNotEndTheGuard() {
        assertFalse(fires("if (xs.isEmpty()) return d; { n++; } return xs.getFirst();"));
    }

    @Test
    void closingBraceRightAfterTheExit_endsTheGuard() {
        assertTrue(fires("{ if (xs.isEmpty()) return d;} return xs.getFirst();"));
    }

    // ---- statement end (b_u03, b_u11)

    @Test
    void emptyThenStatement_endsAtItsOwnSemicolon() {
        assertTrue(fires("if (!xs.isEmpty()) ; use(xs.getFirst());"));
    }

    @Test
    void thenStatementWithoutSemicolon_runsToTheEndOfText() {
        assertFalse(fires("if (!xs.isEmpty()) return xs.getFirst()"));
    }

    // ---- what counts as a read of the receiver between guard and accessor (d_g3, d_g5 = `get`, d_g6, d_g8)

    @Test
    void isEmptyBetweenGuardAndAccessor_isARead() {
        assertFalse(fires("if (!xs.isEmpty()) { log(xs.isEmpty()); return xs.getFirst(); }"));
    }

    @Test
    void getFirstBetweenGuardAndAccessor_isARead() {
        assertFalse(fires("if (!xs.isEmpty()) return xs.getFirst() + xs.getFirst();"));
    }

    @Test
    void getBetweenGuardAndAccessor_isARead() {
        assertFalse(fires("if (!xs.isEmpty()) return xs.get(i) + xs.getFirst();"));
    }

    @Test
    void streamBetweenGuardAndAccessor_isARead() {
        assertFalse(fires("if (!xs.isEmpty()) return xs.stream().count() + xs.getFirst();"));
    }

    @Test
    void longerNameEndingInTheReceiver_isNotAWriteToIt() {
        assertFalse(fires("if (!xs.isEmpty()) { oxs.add(1); return xs.getFirst(); }"));
    }

    // ---- operand openers (d_g18, d_g25a..f, f_op1; f_op2..9 are already killed by the older suites)

    @Test
    void guardAtTextStart_opensNoOperand() {
        assertTrue(fires("!xs.isEmpty() && xs.getFirst() > 0"));
    }

    @Test
    void notEqualsBeforeTheGuard_isNotAnOpener() {
        assertTrue(fires("return flag != !xs.isEmpty() && xs.getFirst() > 0;"));
    }

    @Test
    void lessOrEqualBeforeTheGuard_isNotAnOpener() {
        assertTrue(fires("return flag <= !xs.isEmpty() && xs.getFirst() > 0;"));
    }

    @Test
    void greaterOrEqualBeforeTheGuard_isNotAnOpener() {
        assertTrue(fires("return flag >= !xs.isEmpty() && xs.getFirst() > 0;"));
    }

    @Test
    void plusAssignBeforeTheGuard_isNotAnOpener() {
        assertTrue(fires("total += !xs.isEmpty() && xs.getFirst() > 0;"));
    }

    @Test
    void andAssignBeforeTheGuard_isNotAnOpener() {
        assertTrue(fires("ok &= !xs.isEmpty() && xs.getFirst() > 0;"));
    }

    @Test
    void orAssignBeforeTheGuard_isNotAnOpener() {
        assertTrue(fires("ok |= !xs.isEmpty() && xs.getFirst() > 0;"));
    }

    @Test
    void openingParenthesis_opensTheGuardOperand() {
        assertFalse(fires("run(!xs.isEmpty() && xs.getFirst() > 0);"));
    }
}
