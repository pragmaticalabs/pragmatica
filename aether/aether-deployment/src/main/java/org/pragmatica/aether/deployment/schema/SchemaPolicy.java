package org.pragmatica.aether.deployment.schema;

public record SchemaPolicy(FailureMode failureMode, FailoverMode failoverMode) {
    public enum FailureMode {
        LEAVE_PARTIAL,
        ROLLBACK_BATCH
    }

    public enum FailoverMode {
        AUTO_RESUME,
        MANUAL_ONLY
    }

    public static SchemaPolicy schemaPolicy() {
        return new SchemaPolicy(FailureMode.LEAVE_PARTIAL, FailoverMode.AUTO_RESUME);
    }

    public static SchemaPolicy schemaPolicy(FailureMode failureMode, FailoverMode failoverMode) {
        return new SchemaPolicy(failureMode, failoverMode);
    }
}
