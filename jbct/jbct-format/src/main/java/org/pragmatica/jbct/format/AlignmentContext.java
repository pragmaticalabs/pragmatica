package org.pragmatica.jbct.format;

import java.util.ArrayDeque;
import java.util.Deque;

/// Manages alignment state for the CST printer.
/// Tracks chain alignment, lambda body alignment columns, and ternary alignment columns.
///
///
/// **Thread Safety:** Not thread-safe. This class is designed to be used
/// by a single {@link CstPrinter} instance during a formatting operation.
/// All mutable state (chain column, lambda alignment stack, ternary column) is modified
/// during traversal. Create a new instance per formatting operation.
public final class AlignmentContext {
    private final Deque<Integer> lambdaAlignStack = new ArrayDeque<>();
    private int chainColumn = - 1;
    private boolean inBreakingChain = false;
    private int ternaryColumn = - 1;

    /// Enter a breaking method chain context.
    /// Returns a scope guard that restores state on close.
    public ChainScope enterChain(int column) {
        int prevColumn = this.chainColumn;
        boolean wasBreaking = this.inBreakingChain;
        this.chainColumn = column;
        this.inBreakingChain = true;
        return new ChainScope(prevColumn, wasBreaking);
    }

    /// Push a lambda alignment column.
    /// Returns a scope guard that pops on close.
    public LambdaScope pushLambdaAlign(int column) {
        lambdaAlignStack.push(column);
        return new LambdaScope();
    }

    /// Enter a ternary alignment context.
    /// Nested ternaries inherit the outer's alignment column via {@link #ternaryColumn()};
    /// the scope restores the previous column on close so siblings don't observe it.
    public TernaryScope enterTernary(int column) {
        int prevColumn = this.ternaryColumn;
        this.ternaryColumn = column;
        return new TernaryScope(prevColumn);
    }

    /// Get the current chain alignment column, or -1 if not in a chain.
    public int chainColumn() {
        return chainColumn;
    }

    /// Check if we're inside a breaking chain.
    public boolean isInBreakingChain() {
        return inBreakingChain;
    }

    /// Check if we have a lambda alignment context.
    public boolean hasLambdaAlign() {
        return ! lambdaAlignStack.isEmpty();
    }

    /// Get the current lambda alignment column, or -1 if none.
    public int lambdaColumn() {
        return lambdaAlignStack.isEmpty()
               ? - 1
               : lambdaAlignStack.peek();
    }

    /// True if currently inside a ternary alignment context.
    public boolean inTernary() {
        return ternaryColumn >= 0;
    }

    /// Get the current ternary alignment column, or -1 if not in a ternary.
    public int ternaryColumn() {
        return ternaryColumn;
    }

    /// Scope guard for chain context - restores state on close.
    public final class ChainScope implements AutoCloseable {
        private final int prevColumn;
        private final boolean wasBreaking;
        private boolean lastPostOpSpannedNonLambdaLines = false;

        ChainScope(int prevColumn, boolean wasBreaking) {
            this.prevColumn = prevColumn;
            this.wasBreaking = wasBreaking;
        }

        /// Record that a post-op was just emitted. Used to decide whether the next
        /// dot-method aligns to the chain column or to body-indent.
        /// `spannedLines` is true if the post-op's emit crossed at least one newline;
        /// `containedLambda` is true if the post-op carries a lambda anywhere inside
        /// (so the multi-line span comes from the lambda body, not broken args).
        public void notePostOpEmitted(boolean spannedLines, boolean containedLambda) {
            this.lastPostOpSpannedNonLambdaLines = spannedLines && ! containedLambda;
        }

        /// Return the column the next dot-method should align to. If the previous
        /// post-op spanned multiple lines without containing a lambda (i.e. broken
        /// args, nested chain), the next dot-method continues from body indent;
        /// otherwise it continues from the chain column.
        public int nextDotMethodAnchor(int defaultBodyIndent) {
            return lastPostOpSpannedNonLambdaLines
                   ? defaultBodyIndent
                   : chainColumn;
        }

        @Override
        public void close() {
            chainColumn = prevColumn;
            inBreakingChain = wasBreaking;
        }
    }

    /// Scope guard for lambda alignment - pops stack on close.
    public final class LambdaScope implements AutoCloseable {
        @Override
        public void close() {
            if (!lambdaAlignStack.isEmpty()) {
                lambdaAlignStack.pop();
            }
        }
    }

    /// Scope guard for ternary alignment - restores prior column on close.
    public final class TernaryScope implements AutoCloseable {
        private final int prevColumn;

        TernaryScope(int prevColumn) {
            this.prevColumn = prevColumn;
        }

        @Override
        public void close() {
            ternaryColumn = prevColumn;
        }
    }
}
