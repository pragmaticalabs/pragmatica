package org.pragmatica.jbct.lint.cst.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins for two mutations of PR #3150 that the other [GuardDominance] suites did not kill (ids b_z06, d_g26), driven through
/// [MapperSafety#containsUnguardedPartialOperation]: `true` means the body still fires.
class GuardDominanceShiftAndLambdaExitTest {
    private static boolean fires(String code) {
        return MapperSafety.containsUnguardedPartialOperation(code);
    }

    /// d_g26: a `>` opens the guard operand only as the arrow of a lambda (`->`); the second `>` of a right shift does not.
    @Test
    void rightShiftBeforeTheGuard_isNotAnOpener() {
        assertTrue(fires("return n >> xs.size() > 0 && xs.getFirst() > 0;"));
    }

    /// b_z06: statementEnd stops at the first `;` (inside the lambda block), so the lambda's closing `}` lies after it and reads
    /// as leaving a block: a known false positive, cheaper than a false negative. The block scan must start at the statement END;
    /// starting it at the statement start would balance that `{` against its `}` and exempt the access.
    @Test
    void earlyExitWhoseFirstSemicolonIsInsideALambdaBlock_staysConservative() {
        assertTrue(fires("if (xs.isEmpty()) return run(() -> { a(); }); return xs.getFirst();"));
    }
}
