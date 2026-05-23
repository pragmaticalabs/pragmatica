// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.aether.slice.generation.ConnectivityReport;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.HealthHintWire;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.lang.Contract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Contract public interface ClusterSyncPongSignalFan {
    Logger log = LoggerFactory.getLogger(ClusterSyncPongSignalFan.class);

    /// Sink invoked when the leader observes a non-empty `ClusterSyncPong.readyCandidate`. The
    /// signal fan does not depend on `aether-deployment`; `AetherNode` wires the lambda that
    /// constructs `LifecycleCommand.ForceOnDuty(candidate, ...)` and routes it through
    /// `LifecycleWriter.applyCommand`. Default is a no-op so embeddings that pre-date
    /// Phase 2 PR-B (cluster-convergence-reconciler) keep working unchanged.
    @Contract @FunctionalInterface
    interface ReadyCandidateSink {
        @Contract void onReadyCandidate(NodeId sender, NodeId candidate);

        ReadyCandidateSink NOOP = (_, _) -> {};
    }

    void fan(ClusterSyncPong pong);

    /// Backward-compatible factory: no readiness sink wired — `readyCandidate` arrivals are
    /// observed silently. Preserves pre-Phase-2 behaviour for tests / embeddings that pre-date
    /// the convergence-reconciler wiring.
    static ClusterSyncPongSignalFan clusterSyncPongSignalFan(HealthSignalSink sink, LeaderManager leaderManager) {
        return clusterSyncPongSignalFan(sink, leaderManager, ReadyCandidateSink.NOOP);
    }

    /// Phase 2 PR-B factory. The supplied `readySink` is invoked for every non-empty
    /// `ClusterSyncPong.readyCandidate` observed while this node is leader. Idempotency lives
    /// in the downstream reducer — `ForceOnDuty` on already-`ON_DUTY` is a no-op there.
    static ClusterSyncPongSignalFan clusterSyncPongSignalFan(HealthSignalSink sink,
                                                              LeaderManager leaderManager,
                                                              ReadyCandidateSink readySink) {
        return pong -> fanIfLeader(pong, sink, leaderManager, readySink);
    }

    private static void fanIfLeader(ClusterSyncPong pong,
                                    HealthSignalSink sink,
                                    LeaderManager leaderManager,
                                    ReadyCandidateSink readySink) {
        if (!leaderManager.isLeader()) {return;}
        sink.emit(new HealthSignal.SwimHint(pong.sender(),
                                            HealthHint.HEALTHY,
                                            Epoch.epoch(pong.observedEpochTerm(), pong.observedEpochCounter())));
        pong.readyCandidate().onPresent(candidate -> readySink.onReadyCandidate(pong.sender(), candidate));
        pong.peerHealth().forEach(observation -> emitHealth(pong, observation, sink));
        pong.peerConnectivity().forEach(observation -> emitConnectivity(pong, observation, sink));
    }

    private static void emitHealth(ClusterSyncPong pong, PeerHealthObservation observation, HealthSignalSink sink) {
        sink.emit(new HealthSignal.RemoteSwimHint(pong.sender(),
                                                  observation.peerId(),
                                                  translateHint(observation.hint()),
                                                  Epoch.epoch(observation.observedEpochTerm(),
                                                              observation.observedEpochCounter()),
                                                  observation.producedAtMs()));
    }

    private static void emitConnectivity(ClusterSyncPong pong,
                                         PeerConnectivityObservation observation,
                                         HealthSignalSink sink) {
        sink.emit(new HealthSignal.RemoteConnectivity(pong.sender(),
                                                      observation.peerId(),
                                                      translateConnectivity(observation.state()),
                                                      Epoch.epoch(observation.observedEpochTerm(),
                                                                  observation.observedEpochCounter()),
                                                      observation.producedAtMs()));
    }

    private static HealthHint translateHint(HealthHintWire wire) {
        return switch (wire){
            case HEALTHY -> HealthHint.HEALTHY;
            case SUSPECTED -> HealthHint.SUSPECTED;
            case FAULTY -> HealthHint.FAULTY;
        };
    }

    private static ConnectivityReport translateConnectivity(ConnectivityState state) {
        return switch (state){
            case CONNECTED -> ConnectivityReport.CONNECTED;
            case DISCONNECTED -> ConnectivityReport.DISCONNECTED;
            case STALE -> ConnectivityReport.STALE;
        };
    }
}
