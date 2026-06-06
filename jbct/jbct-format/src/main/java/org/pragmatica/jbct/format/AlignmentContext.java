package org.pragmatica.jbct.format;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

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
    private int inlineExpressionDepth = 0;
    private int tailContextDepth = 0;

    /// Enter an "inline expression" context — inside an inline-rendered args list, nested
    /// chains do NOT break vertically but render inline. Stacks so nested args restore
    /// correctly on close.
    public InlineExprScope enterInlineExpression() {
        inlineExpressionDepth++;
        return new InlineExprScope();
    }

    public boolean isInInlineExpression() {
        return inlineExpressionDepth > 0;
    }

    public final class InlineExprScope implements AutoCloseable {
        @Override public void close() { inlineExpressionDepth--; }
    }

    /// Enter a "tail expression" context — the expression we're about to print is the
    /// value of a `return`/`throw` statement or the body of an arrow lambda. Used to
    /// gate chain breaking — chains break vertically only in tail contexts. Suspends
    /// any active inline-expression context (chain inside lambda body breaks even when
    /// the lambda itself is a single-arg call).
    public TailScope enterTailContext() {
        tailContextDepth++;
        int suspended = inlineExpressionDepth;
        inlineExpressionDepth = 0;
        return new TailScope(suspended);
    }

    public boolean isInTailContext() {
        return tailContextDepth > 0;
    }

    private int ternaryCond = 0;

    /// Enter a ternary-cond context. LOG_AND/LOG_OR inside a ternary cond stay on one
    /// line regardless of operator count — only the `?`/`:` lines break.
    public TernaryCondScope enterTernaryCond() {
        ternaryCond++;
        return new TernaryCondScope();
    }

    public boolean isInTernaryCond() {
        return ternaryCond > 0;
    }

    public final class TernaryCondScope implements AutoCloseable {
        @Override public void close() { ternaryCond--; }
    }

    public final class TailScope implements AutoCloseable {
        private final int suspendedInline;
        TailScope(int suspendedInline) { this.suspendedInline = suspendedInline; }
        @Override public void close() {
            tailContextDepth--;
            inlineExpressionDepth = suspendedInline;
        }
    }

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
        private boolean lastPostOpWasBrokenArgs = false;
        private int postBrokenArgsAnchor = - 1;

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

        /// Note that the previous post-op was a bare `(...)` invocation whose args
        /// broke onto multiple lines. The next dot-method stays inline on the same line
        /// as the closing `)`, and subsequent dot-methods align under the args-open-paren
        /// column `argsOpenParenColumn`.
        public void noteBrokenArgsPostOp(int argsOpenParenColumn) {
            this.lastPostOpWasBrokenArgs = true;
            this.postBrokenArgsAnchor = argsOpenParenColumn;
        }

        public boolean lastPostOpWasBrokenArgs() {
            return lastPostOpWasBrokenArgs;
        }

        public void consumeBrokenArgsInlineSlot() {
            this.lastPostOpWasBrokenArgs = false;
            // postBrokenArgsAnchor stays — it's the column for subsequent breaks.
        }

        /// Clear the args-open-paren anchor so subsequent dot-methods use the chain
        /// column instead. Used when the chain breaks immediately after broken args
        /// (only one follow-up remains).
        public void clearBrokenArgsAnchor() {
            this.lastPostOpWasBrokenArgs = false;
            this.postBrokenArgsAnchor = - 1;
        }

        public int postBrokenArgsAnchor() {
            return postBrokenArgsAnchor;
        }

        /// Return the column the next dot-method should align to. If the previous
        /// post-op spanned multiple lines without containing a lambda (i.e. broken
        /// args, nested chain), the next dot-method continues from body indent;
        /// otherwise it continues from the chain column.
        public int nextDotMethodAnchor(int defaultBodyIndent) {
            if (postBrokenArgsAnchor >= 0) {
                return postBrokenArgsAnchor;
            }
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

    /// Immutable snapshot of all mutable alignment state. Captured before a speculative
    /// trial render and restored afterwards, so the trial cannot corrupt real alignment
    /// state even if it follows an unbalanced force-break/early-return path. The lambda
    /// alignment stack is deep-copied (its elements are immutable `Integer`s).
    public record Snapshot(List<Integer> lambdaAlignStack,
                           int chainColumn,
                           boolean inBreakingChain,
                           int ternaryColumn,
                           int inlineExpressionDepth,
                           int tailContextDepth,
                           int ternaryCond) {}

    /// Capture the current mutable alignment state into an immutable snapshot.
    public Snapshot snapshot() {
        return new Snapshot(List.copyOf(lambdaAlignStack),
                            chainColumn,
                            inBreakingChain,
                            ternaryColumn,
                            inlineExpressionDepth,
                            tailContextDepth,
                            ternaryCond);
    }

    /// Restore mutable alignment state from a previously captured snapshot. Rebuilds the
    /// lambda alignment stack so its iteration order matches the captured order.
    public void restore(Snapshot snapshot) {
        lambdaAlignStack.clear();
        // List.copyOf preserves the Deque's iteration order (head-first); push in reverse
        // so the head element ends up back on top.
        var saved = snapshot.lambdaAlignStack();
        for (int i = saved.size() - 1; i >= 0; i--) {
            lambdaAlignStack.push(saved.get(i));
        }
        this.chainColumn = snapshot.chainColumn();
        this.inBreakingChain = snapshot.inBreakingChain();
        this.ternaryColumn = snapshot.ternaryColumn();
        this.inlineExpressionDepth = snapshot.inlineExpressionDepth();
        this.tailContextDepth = snapshot.tailContextDepth();
        this.ternaryCond = snapshot.ternaryCond();
    }
}
