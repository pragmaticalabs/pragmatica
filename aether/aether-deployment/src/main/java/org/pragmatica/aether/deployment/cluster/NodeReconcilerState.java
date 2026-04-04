package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.consensus.NodeId;

import java.time.Instant;
import java.util.List;


/// State machine for cluster node count reconciliation.
/// Transitions: INACTIVE -> FORMING -> CONVERGED <-> RECONCILING, Any -> INACTIVE.
public sealed interface NodeReconcilerState {
    record Inactive(String reason) implements NodeReconcilerState{}

    record Forming(Instant since) implements NodeReconcilerState{}

    record Converged() implements NodeReconcilerState{}

    record Reconciling(int targetSize,
                       int currentSize,
                       List<ProvisionAttempt> inFlight,
                       List<NodeId> terminating,
                       Instant startedAt) implements NodeReconcilerState{}

    record ProvisionAttempt(Instant startedAt, int attemptNumber){}
}
