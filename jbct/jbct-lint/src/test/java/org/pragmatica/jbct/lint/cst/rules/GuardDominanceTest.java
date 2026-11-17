package org.pragmatica.jbct.lint.cst.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// JBCT-TOT-02 guard-dominance text scan (#3065), driven through [MapperSafety#containsUnguardedPartialOperation]: `true` means the
/// body still fires, `false` means every partial operation is exempted by a guard. Each case pins one construct of
/// [GuardDominance]; the mutation survivors of PR #3150 (v3150b, v3150d) are named in the test names' section comments.
class GuardDominanceTest {
    private static boolean fires(String code) {
        return MapperSafety.containsUnguardedPartialOperation(code);
    }

    // ---- other partial operations are never exempted by a guard

    @Test
    void guardedGetFirst_thenThrow_stillFires() {
        assertTrue(fires("if (!xs.isEmpty()) { return xs.getFirst(); } throw new IllegalStateException();"));
    }

    @Test
    void guardedGetFirst_thenIndexedGet_stillFires() {
        assertTrue(fires("if (!xs.isEmpty()) { return xs.getFirst(); } return ys.get(0);"));
    }

    @Test
    void guardedGetFirst_thenOrElseThrow_stillFires() {
        assertTrue(fires("if (!xs.isEmpty()) { return xs.getFirst(); } return o.orElseThrow();"));
    }

    // ---- receivers that are not a plain identifier are never guarded

    @Test
    void callResultReceiver_isNeverGuarded() {
        assertTrue(fires("if (!f().isEmpty()) return f().getFirst();"));
    }

    @Test
    void qualifiedReceiver_isNeverGuardedByACallResultGuard() {
        assertTrue(fires("if (!f().isEmpty()) return this.xs.getFirst();"));
    }

    @Test
    void accessorAtTextStart_hasNoReceiver() {
        assertTrue(fires(".getFirst()"));
    }

    @Test
    void receiverAtTextStart_isReadWithoutLookingBehind() {
        assertTrue(fires("xs.getFirst()"));
    }

    @Test
    void longReceiverName_isReadWholeAndGuarded() {
        assertFalse(fires("if (!items.isEmpty()) return items.getFirst();"));
    }

    // ---- masking: strings and comments carry no operations

    @Test
    void operationNamedInStringLiteral_isNotAnOperation() {
        assertFalse(fires("return \"xs.getFirst()\";"));
    }

    @Test
    void operationNamedInComment_isNotAnOperation() {
        assertFalse(fires("// xs.getFirst()\nreturn 1;"));
    }

    // ---- the && chain: what ends it

    @Test
    void singleAmpersand_doesNotShortCircuit() {
        assertTrue(fires("boolean ok = !xs.isEmpty() & xs.getFirst() > 0;"));
    }

    @Test
    void chainWithGroupedOperandWithoutSpace_isStillGuarded() {
        assertFalse(fires("if (!xs.isEmpty() &&(flag) && xs.getFirst() > 0) { run(); }"));
    }

    @Test
    void ternaryQuestionEndsTheChain() {
        assertTrue(fires("return !xs.isEmpty() && flag ? xs.getFirst() : d;"));
    }

    @Test
    void ternaryColonEndsTheChain() {
        assertTrue(fires("return flag ? !xs.isEmpty() && n > 0 : xs.getFirst();"));
    }

    @Test
    void semicolonEndsTheChain() {
        assertTrue(fires("boolean ok = !xs.isEmpty() && n > 0; use(xs.getFirst());"));
    }

    @Test
    void openingBraceEndsTheChain() {
        assertTrue(fires("return !xs.isEmpty() && run(() -> { return xs.getFirst(); });"));
    }

    @Test
    void closingBraceEndsTheChain() {
        assertTrue(fires("return new boolean[] { a, !xs.isEmpty() && n > 0 }.length + xs.getFirst();"));
    }

    @Test
    void closingParenOpenedBeforeTheGuardEndsTheChain() {
        assertTrue(fires("return fmt(!xs.isEmpty() && n > 0) + xs.getFirst();"));
    }

    // ---- early exit: only a statement that always leaves counts

    @Test
    void nonExitSingleStatement_doesNotGuard() {
        assertTrue(fires("if (xs.isEmpty()) log(); return xs.getFirst();"));
    }

}
