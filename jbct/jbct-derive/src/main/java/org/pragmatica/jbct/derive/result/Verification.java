package org.pragmatica.jbct.derive.result;

/// One line of the exit gate's arithmetic (SPEC.md §4 verify): a budget rule applied at a scope,
/// with its outcome. `UNVERIFIED` is a first-class result — a floor the sheet did not supply
/// produces it, never a silently assumed default. `HALT` marks a rule the arithmetic actually
/// broke (a floor exceeding its budget).
public record Verification(String rule, String scope, Status status, String detail) {
    /// The outcome of one verification rule.
    public enum Status {
        /// The arithmetic ran and the budget holds.
        VERIFIED,
        /// A required input (usually a floor) was absent — not a default, an explicit gap.
        UNVERIFIED,
        /// The arithmetic ran and the budget is exceeded — a halt.
        HALT
    }

    /// A rule that ran and holds.
    public static Verification verified(String rule, String scope, String detail) {
        return new Verification(rule, scope, Status.VERIFIED, detail);
    }

    /// A rule that could not run for want of an input.
    public static Verification unverified(String rule, String scope, String detail) {
        return new Verification(rule, scope, Status.UNVERIFIED, detail);
    }

    /// A rule that ran and failed its budget.
    public static Verification halt(String rule, String scope, String detail) {
        return new Verification(rule, scope, Status.HALT, detail);
    }
}
