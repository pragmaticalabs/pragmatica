package org.pragmatica.jbct.derive.result;

import org.pragmatica.jbct.derive.model.Axis;

/// A decision record (SPEC.md §4 emit): one resolved axis position with its full provenance —
/// `position · forced by · via · costs · revisit when`. Every mechanical move the engine makes
/// leaves one; moves it refuses to make leave a [JudgmentPoint] instead.
public record DecisionRecord(Axis axis, String position, String forcedBy, String via, String costs, String revisitWhen) {}
