package org.pragmatica.jbct.derive.result;

import java.util.List;

/// A halt (SPEC.md §4; ch. 8's five halts): a point where the derivation stops and hands back to
/// the human. `renegotiationMenu` is populated only for [Kind#CONTRADICTION] — each branch priced
/// and marked "re-enters the derivation"; it is a skeleton, never a pick.
public record Halt(Kind kind, String detail, List<String> renegotiationMenu) {
    public Halt {
        renegotiationMenu = List.copyOf(renegotiationMenu);
    }

    /// The five halts of ch. 8.
    public enum Kind {
        /// A fake answer failed the entry gate (handled upstream as gate findings).
        FAKE_ANSWER,
        /// Two same-scope pressures opposed and could not be decomposed further.
        CONTRADICTION,
        /// A path's physical floor exceeds its stated latency budget.
        FLOORS_EXCEED_BUDGET,
        /// The standing-mechanism bill exceeds the Q8 cost & capacity envelope.
        ENVELOPE_EXCEEDED,
        /// Unexplored territory — a demand no rule prices, emitted verbatim.
        INSTRUMENT_GAP
    }

    /// A halt with no renegotiation menu (every kind except contradiction).
    public static Halt of(Kind kind, String detail) {
        return new Halt(kind, detail, List.of());
    }

    /// A contradiction halt carrying its priced renegotiation-menu skeleton.
    public static Halt contradiction(String detail, List<String> menu) {
        return new Halt(Kind.CONTRADICTION, detail, menu);
    }
}
