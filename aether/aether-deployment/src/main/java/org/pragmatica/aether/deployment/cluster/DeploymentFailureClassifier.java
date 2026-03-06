package org.pragmatica.aether.deployment.cluster;

import java.util.List;

/// Classifies deployment failure reasons as deterministic (non-retriable) or transient (retriable).
///
/// Deterministic failures indicate structural problems (missing classes, manifest errors, etc.)
/// that will never succeed on retry. Transient failures may succeed on retry (timeouts,
/// temporary unavailability, etc.).
public final class DeploymentFailureClassifier {
    private static final List<String> DETERMINISTIC_PATTERNS = List.of("ClassNotFound",
                                                                       "NoClassDefFound",
                                                                       "NoSuchMethod",
                                                                       "manifest",
                                                                       "Manifest",
                                                                       "factory method not found",
                                                                       "parameter",
                                                                       "circular dependency",
                                                                       "Circular dependency",
                                                                       "mismatch",
                                                                       "UNSUPPORTED",
                                                                       "not an interface",
                                                                       "can only be applied to");

    private DeploymentFailureClassifier() {}

    /// Returns true if the failure reason indicates a deterministic (non-retriable) failure.
    public static boolean isDeterministic(String failureReason) {
        return DETERMINISTIC_PATTERNS.stream()
                                     .anyMatch(failureReason::contains);
    }
}
