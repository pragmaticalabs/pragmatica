/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.rabia;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// Metrics collection interface for consensus engine observability.
///
/// Implementations can integrate with monitoring systems like Micrometer.
/// Use [#noop()] for a no-op implementation when metrics are not needed.
///
/// Class-level `@Contract`: every method is a fire-and-forget metric recorder whose void
/// return is the contract (no meaningful result to surface to the caller).
@Contract
public interface ConsensusMetrics {
    /// Record a decision being made.
    ///
    /// @param nodeId     The node making the decision
    /// @param phase      The phase number
    /// @param stateValue The decided value (V0 or V1)
    /// @param durationNs Duration from phase start to decision in nanoseconds
    void recordDecision(NodeId nodeId, Phase phase, StateValue stateValue, long durationNs);

    /// Record a proposal being sent or received.
    ///
    /// @param nodeId The node sending the proposal
    /// @param phase  The phase number
    void recordProposal(NodeId nodeId, Phase phase);

    /// Record a round 1 vote.
    ///
    /// @param nodeId     The voting node
    /// @param phase      The phase number
    /// @param stateValue The voted value
    void recordVoteRound1(NodeId nodeId, Phase phase, StateValue stateValue);

    /// Record a round 2 vote.
    ///
    /// @param nodeId     The voting node
    /// @param phase      The phase number
    /// @param stateValue The voted value
    void recordVoteRound2(NodeId nodeId, Phase phase, StateValue stateValue);

    /// Record a synchronization attempt.
    ///
    /// @param nodeId  The node requesting sync
    /// @param success Whether sync was successful
    void recordSyncAttempt(NodeId nodeId, boolean success);

    /// Update the pending batch count gauge.
    ///
    /// @param nodeId The node
    /// @param count  Current number of pending batches
    void updatePendingBatches(NodeId nodeId, int count);

    /// Record one `RabiaEngine.apply` that was never answered within `ProtocolConfig.applyTimeout` (#1117). A batch
    /// the cluster never decided looks, from the caller, like a slow commit; this counter is what tells the two apart.
    ///
    /// @param nodeId The node whose submission timed out
    default void recordApplyTimeout(NodeId nodeId) {}

    /// Hand the metrics sink a probe for the age, in milliseconds, of the OLDEST batch still awaiting a decision
    /// on this node, `0` when none is pending (#1117). A probe rather than a pushed value: the age grows with no
    /// event to push it, so a gauge fed only on batch arrival and commit would read stale exactly when a batch is
    /// stuck. Called once, at engine construction; the sink calls the probe when it is read.
    ///
    /// @param nodeId The node
    /// @param ageMs  Age of the oldest pending batch in milliseconds, 0 when nothing is pending
    default void registerOldestPendingBatchAgeProbe(NodeId nodeId, java.util.function.LongSupplier ageMs) {}

    /// Records when fast path optimization is used.
    ///
    /// @param nodeId The node using fast path
    /// @param phase  The phase number
    /// @param value  The decided value (V0 or V1)
    void recordFastPath(NodeId nodeId, Phase phase, StateValue value);

    /// Returns a no-op implementation that does nothing.
    static ConsensusMetrics noop() {
        return NoopMetrics.INSTANCE;
    }

    /// No-op implementation for when metrics are disabled.
    @Contract
    enum NoopMetrics implements ConsensusMetrics {
        INSTANCE;
        @Override
        public void recordDecision(NodeId nodeId, Phase phase, StateValue stateValue, long durationNs) {}
        @Override
        public void recordProposal(NodeId nodeId, Phase phase) {}
        @Override
        public void recordVoteRound1(NodeId nodeId, Phase phase, StateValue stateValue) {}
        @Override
        public void recordVoteRound2(NodeId nodeId, Phase phase, StateValue stateValue) {}
        @Override
        public void recordSyncAttempt(NodeId nodeId, boolean success) {}
        @Override
        public void updatePendingBatches(NodeId nodeId, int count) {}
        @Override
        public void recordFastPath(NodeId nodeId, Phase phase, StateValue value) {}
    }
}
