package org.pragmatica.aether.deployment.cluster;

import java.time.Instant;
import java.util.List;

/// State machine for cluster node count reconciliation.
/// Transitions: INACTIVE -> FORMING -> CONVERGED <-> RECONCILING, Any -> INACTIVE.
public sealed interface NodeReconcilerState {
    /// No ComputeProvider available or manager deactivated.
    record Inactive(String reason) implements NodeReconcilerState{}

    /// Initial cluster formation — all seed nodes joining, no provisioning.
    record Forming(Instant since) implements NodeReconcilerState{}

    /// Cluster at desired size, monitoring for deficit.
    record Converged() implements NodeReconcilerState{}

    /// Provisioning or draining in progress to reach desired size.
    record Reconciling(int targetSize,
                       int currentSize,
                       List<ProvisionAttempt> inFlight,
                       Instant startedAt) implements NodeReconcilerState{}

    /// Tracks a single provision attempt.
    record ProvisionAttempt(Instant startedAt, int attemptNumber){}
}
