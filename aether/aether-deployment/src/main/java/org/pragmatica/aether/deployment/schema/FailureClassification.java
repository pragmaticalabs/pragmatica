package org.pragmatica.aether.deployment.schema;
public enum FailureClassification {
    /// Transient failures — auto-retry with backoff (connection timeout, lock contention)
    TRANSIENT,
    /// Permanent failures — needs human/LLM intervention (SQL syntax, checksum mismatch)
    PERMANENT,
    /// Unclassified failures — treated as permanent (no auto-retry)
    UNKNOWN
}
