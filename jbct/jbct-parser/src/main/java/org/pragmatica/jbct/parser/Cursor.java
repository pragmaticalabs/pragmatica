package org.pragmatica.jbct.parser;

import java.util.Collections;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.token.TokenArray;


/// Navigation primitive over the v6 flat-`int[]` CST. A Cursor pairs a `CstArray` with a
/// node index — 16 bytes per instance, no tree materialization.
///
/// Sealed into three variants matching the CST node taxonomy:
/// - `Leaf` — a terminal node (no CST children; corresponds to grammar-literal or
///   token-boundary rules that produce a single token range)
/// - `Branch` — a non-terminal node with one or more CST children
/// - `ErrorNode` — a synthetic node produced for an error-recovery skipped range
///
/// Exhaustive switches over Cursor are checked at compile time. Switches over `kind()`
/// (RuleKind enum) likewise.
public sealed interface Cursor permits Cursor.Leaf, Cursor.Branch, Cursor.ErrorNode {
    CstArray cst();
    int idx();

    default int kindId() {
        return cst().kindAt(idx());
    }

    default RuleKind kind() {
        return RuleKind.of(kindId());
    }

    default boolean kindIs(RuleKind k) {
        return kindId() == k.kindId();
    }

    default boolean kindIsAny(RuleKind... ks) {
        int my = kindId();

        for (var k : ks) {
            if (k.kindId() == my) {
                return true;
            }
        }

        return false;
    }

    default int spanStart() {
        return cst().spanStart(idx());
    }

    default int spanEnd() {
        return cst().spanEnd(idx());
    }

    default int startLine() {
        return lineIndex(cst()).lineAt(spanStart());
    }

    default int startColumn() {
        return lineIndex(cst()).columnAt(spanStart());
    }

    default int firstTokenIdx() {
        return cst().firstTokenAt(idx());
    }

    default int lastTokenIdx() {
        return cst().lastTokenAt(idx());
    }

    default IntStream leadingTriviaTokens() {
        return cst().leadingTriviaTokens(idx());
    }

    default IntStream trailingTriviaTokens() {
        return cst().trailingTriviaTokens(idx());
    }

    default Stream<TriviaToken> leadingTrivia() {
        var tokens = cst().tokens();

        return leadingTriviaTokens().mapToObj(i -> new TriviaToken(tokens, i));
    }

    default Stream<TriviaToken> trailingTrivia() {
        var tokens = cst().tokens();

        return trailingTriviaTokens().mapToObj(i -> new TriviaToken(tokens, i));
    }

    default Optional<Cursor> parent() {
        int p = cst().parentAt(idx());

        return p == CstArray.NO_NODE
               ? Optional.empty()
               : Optional.of(wrap(cst(), p));
    }

    default Stream<Cursor> ancestors() {
        Iterable<Cursor> iter = () -> new java.util.Iterator<Cursor>() {
            Cursor curr = parent().orElse(null);

            @Override
            public boolean hasNext() {
                return curr != null;
            }

            @Override
            public Cursor next() {
                var out = curr;

                curr = out.parent()
                          .orElse(null);

                return out;
            }
        };

        return StreamSupport.stream(iter.spliterator(), false);
    }

    /// Wrap a (cst, idx) pair as the appropriate Cursor variant. Returns Leaf for nodes
    /// with no children, ErrorNode for error nodes, Branch otherwise.
    static Cursor wrap(CstArray cst, int idx) {
        if (cst.isError(idx)) {
            return new ErrorNode(cst, idx);
        }

        if (cst.firstChildAt(idx) == CstArray.NO_NODE) {
            return new Leaf(cst, idx);
        }

        return new Branch(cst, idx);
    }

    /// Cursor for the root of the CST.
    static Cursor root(CstArray cst) {
        return wrap(cst, cst.rootIndex());
    }

    record Leaf(CstArray cst, int idx) implements Cursor {
        public CharSequence text() {
            return cst.textAt(idx);
        }
    }

    record Branch(CstArray cst, int idx) implements Cursor {
        public Optional<Cursor> firstChild() {
            int c = cst.firstChildAt(idx);

            return c == CstArray.NO_NODE
                   ? Optional.empty()
                   : Optional.of(wrap(cst, c));
        }

        public Optional<Cursor> nextSibling() {
            int s = cst.nextSiblingAt(idx);

            return s == CstArray.NO_NODE
                   ? Optional.empty()
                   : Optional.of(wrap(cst, s));
        }

        public Stream<Cursor> children() {
            return cst.children(idx)
                      .mapToObj(i -> wrap(cst, i));
        }

        public Stream<Cursor> descendants() {
            return cst.descendants(idx)
                      .mapToObj(i -> wrap(cst, i));
        }

        public int childCount() {
            int n = 0;

            for (int c = cst.firstChildAt(idx); c != CstArray.NO_NODE; c = cst.nextSiblingAt(c)) {
                n++;
            }

            return n;
        }
    }

    record ErrorNode(CstArray cst, int idx) implements Cursor {
        public CharSequence skippedText() {
            return cst.textAt(idx);
        }
    }

    // ===== LineIndex cache =====
    /// Per-CstArray LineIndex cache. Keyed by identity so concurrent parses don't share.
    /// WeakHashMap so the cached LineIndex is reclaimed when its CstArray is GC'd.
    java.util.Map<CstArray, LineIndex> LINE_INDEX_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private static LineIndex lineIndex(CstArray cst) {
        return LINE_INDEX_CACHE.computeIfAbsent(cst, c -> new LineIndex(c.input()));
    }
}
