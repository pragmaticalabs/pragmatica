package org.pragmatica.jbct.format.cst;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Manages alignment state for the CST printer.
 * Tracks chain alignment and lambda body alignment columns.
 */
final class AlignmentContext {
    private final Deque<Integer> lambdaAlignStack = new ArrayDeque<>();
    private int chainColumn = - 1;
    private boolean inBreakingChain = false;

    /**
     * Enter a breaking method chain context.
     * Returns a scope guard that restores state on close.
     */
    ChainScope enterChain(int column) {
        int prevColumn = this.chainColumn;
        boolean wasBreaking = this.inBreakingChain;
        this.chainColumn = column;
        this.inBreakingChain = true;
        return new ChainScope(prevColumn, wasBreaking);
    }

    /**
     * Push a lambda alignment column.
     * Returns a scope guard that pops on close.
     */
    LambdaScope pushLambdaAlign(int column) {
        lambdaAlignStack.push(column);
        return new LambdaScope();
    }

    /**
     * Get the current chain alignment column, or -1 if not in a chain.
     */
    int chainColumn() {
        return chainColumn;
    }

    /**
     * Check if we're inside a breaking chain.
     */
    boolean isInBreakingChain() {
        return inBreakingChain;
    }

    /**
     * Check if we have a lambda alignment context.
     */
    boolean hasLambdaAlign() {
        return !lambdaAlignStack.isEmpty();
    }

    /**
     * Get the current lambda alignment column, or -1 if none.
     */
    int lambdaColumn() {
        return lambdaAlignStack.isEmpty()
               ? - 1
               : lambdaAlignStack.peek();
    }

    /**
     * Scope guard for chain context - restores state on close.
     */
    final class ChainScope implements AutoCloseable {
        private final int prevColumn;
        private final boolean wasBreaking;

        ChainScope(int prevColumn, boolean wasBreaking) {
            this.prevColumn = prevColumn;
            this.wasBreaking = wasBreaking;
        }

        @Override
        public void close() {
            chainColumn = prevColumn;
            inBreakingChain = wasBreaking;
        }
    }

    /**
     * Scope guard for lambda alignment - pops stack on close.
     */
    final class LambdaScope implements AutoCloseable {
        @Override
        public void close() {
            if (!lambdaAlignStack.isEmpty()) {
                lambdaAlignStack.pop();
            }
        }
    }
}
