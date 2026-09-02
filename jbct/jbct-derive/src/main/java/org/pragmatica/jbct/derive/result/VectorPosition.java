package org.pragmatica.jbct.derive.result;

import java.util.List;

import org.pragmatica.jbct.derive.model.Axis;

/// One position on the derived vector (SPEC.md §4 emit): the value the engine landed on for an
/// axis, the answers that cite it (F10), and *how* it was reached.
///
/// [Resolution] records honesty about the mechanism: [Resolution#FORCED] is a discrete move the
/// engine could make mechanically (a queue for a burst, an audit-log for an audit demand);
/// [Resolution#NULL_KEPT] is the cheapest position, undisplaced; [Resolution#DEFERRED] is a
/// directed pressure whose *rung* the engine will not pick — the ceiling that would decide it is
/// judgment (SPEC.md §1), emitted as a [JudgmentPoint], never resolved here.
public record VectorPosition(Axis axis, String value, List<String> citing, Resolution resolution) {
    public VectorPosition {
        citing = List.copyOf(citing);
    }

    /// How a vector position was reached.
    public enum Resolution {
        /// A discrete containing mechanism the engine selected mechanically.
        FORCED,
        /// The cheapest position, kept because no answer forced a move.
        NULL_KEPT,
        /// A directed pressure whose rung-depth is a named judgment point, left for the human.
        DEFERRED
    }
}
