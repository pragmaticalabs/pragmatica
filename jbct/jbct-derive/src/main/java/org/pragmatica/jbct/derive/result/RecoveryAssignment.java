package org.pragmatica.jbct.derive.result;

/// The recovery posture derived for one effectful operation (SPEC.md §4 resolve), read from its
/// `[[domain_shape]]` row with design-out checked first.
///
/// A [RecoveryClass#TIE] is NOT a resolution — it is the engine refusing to pick between equally
/// matching classes and emitting the choice as a [JudgmentPoint] (Card 5; the golden-asserted hard
/// constraint). The `tie()` factory is the only one that produces a TIE.
public record RecoveryAssignment(String operation, RecoveryClass recoveryClass, String rationale) {
    /// A recovery class the derivation can assign, plus the TIE non-resolution.
    public enum RecoveryClass {
        /// Reshape the operation so the failure cannot matter (idempotent / append-only / no inverse).
        DESIGN_OUT,
        /// Forward error recovery: a bounded, visible degraded window (decaying / re-derivable state).
        FER,
        /// Backward error recovery: a per-case defined inverse, residuals remain (money, rectification).
        BER,
        /// Equally matching classes — a named judgment point, emitted and not resolved.
        TIE
    }

    /// A resolved recovery posture for an operation.
    public static RecoveryAssignment of(String operation, RecoveryClass recoveryClass, String rationale) {
        return new RecoveryAssignment(operation, recoveryClass, rationale);
    }

    /// An unresolved recovery tie — emitted as a judgment point, never picked by the engine.
    public static RecoveryAssignment tie(String operation, String rationale) {
        return new RecoveryAssignment(operation, RecoveryClass.TIE, rationale);
    }

    /// Whether this assignment is an unresolved tie (a judgment point).
    public boolean isTie() {
        return recoveryClass == RecoveryClass.TIE;
    }
}
