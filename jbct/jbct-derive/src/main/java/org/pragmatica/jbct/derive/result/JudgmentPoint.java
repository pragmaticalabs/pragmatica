package org.pragmatica.jbct.derive.result;

/// A named judgment point (SPEC.md §1; the golden-asserted hard constraint): a decision the book
/// names as judgment and the engine therefore EMITS rather than resolves. Auto-resolving any of
/// these would contradict ch. 12 and overclaim exactly the way the book warns against.
public record JudgmentPoint(Kind kind, String subject, String detail) {
    /// The kinds of judgment the engine refuses to make (Card 5's refusals).
    public enum Kind {
        /// Two or more recovery classes match an operation equally.
        RECOVERY_TIE,
        /// Which branch of a contradiction's renegotiation menu to take.
        CONTRADICTION_CHOICE,
        /// Setting a target the sheet leaves UNKNOWN (never invented).
        TARGET_SETTING,
        /// Picking a concrete product for a resolved position (the engine names no products).
        PRODUCT_PICK,
        /// How far up a rung ladder a directed pressure should climb (the ceiling is judgment).
        RUNG_DEPTH,
        /// The partition key a sharded position needs (a domain gift the engine will not guess).
        PARTITION_KEY,
        /// Whether a mandate's constraint splits a scope or hardens it (no strike was stated).
        CONSTRAINT_SHAPE,
        /// Which module/deployment shape a team- or cadence-pressure implies (F21; never team-size).
        TOPOLOGY_SHAPE
    }

    /// A judgment point of the given kind about a subject (an axis, operation, or scope).
    public static JudgmentPoint of(Kind kind, String subject, String detail) {
        return new JudgmentPoint(kind, subject, detail);
    }
}
