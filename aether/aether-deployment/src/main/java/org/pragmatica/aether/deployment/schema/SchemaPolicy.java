package org.pragmatica.aether.deployment.schema;
/// Configuration controlling failure and failover behavior during schema migrations.
///
/// @param failureMode  how to handle a migration step failure
/// @param failoverMode how to handle migration resumption after node failover
public record SchemaPolicy(FailureMode failureMode, FailoverMode failoverMode) {
    /// Controls behavior when a migration step fails.
    public enum FailureMode {
        /// Leave the database in its current state (partially migrated).
        LEAVE_PARTIAL,
        /// Roll back all migrations applied in the current batch.
        ROLLBACK_BATCH
    }

    /// Controls behavior when migration resumes after a node failover.
    public enum FailoverMode {
        /// Automatically resume pending migrations on the new leader.
        AUTO_RESUME,
        /// Require manual intervention to resume migrations.
        MANUAL_ONLY
    }

    /// Creates a policy with default settings: LEAVE_PARTIAL failure mode, AUTO_RESUME failover mode.
    public static SchemaPolicy schemaPolicy() {
        return new SchemaPolicy(FailureMode.LEAVE_PARTIAL, FailoverMode.AUTO_RESUME);
    }

    /// Creates a policy with the specified failure and failover modes.
    ///
    /// @param failureMode  how to handle a migration step failure
    /// @param failoverMode how to handle migration resumption after node failover
    public static SchemaPolicy schemaPolicy(FailureMode failureMode, FailoverMode failoverMode) {
        return new SchemaPolicy(failureMode, failoverMode);
    }
}
