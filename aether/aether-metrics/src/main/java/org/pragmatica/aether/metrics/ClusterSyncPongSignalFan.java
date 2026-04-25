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
import org.pragmatica.lang.Contract;

import java.util.function.BooleanSupplier;


/// Leader-side fan-out of peer observations carried on `ClusterSyncPong`s.
///
/// Followers piggyback SWIM-health and QUIC-connectivity observations on their
/// pongs (commit 2). This fan unpacks those observations on receipt and emits
/// one `HealthSignal.RemoteSwimHint` / `HealthSignal.RemoteConnectivity` per
/// observation into the leader's health-signal sink.
///
/// On followers (`isLeaderGate.getAsBoolean() == false`) the fan is a no-op —
/// only the Rabia leader feeds its `HealthReconciler` with decisions.
///
/// See `aether/docs/specs/clustersync-refactor-spec.md` commit 1.
@Contract public interface ClusterSyncPongSignalFan {
    void fan(ClusterSyncPong pong);

    static ClusterSyncPongSignalFan clusterSyncPongSignalFan(HealthSignalSink sink, BooleanSupplier isLeaderGate) {
        return pong -> fanIfLeader(pong, sink, isLeaderGate);
    }

    private static void fanIfLeader(ClusterSyncPong pong, HealthSignalSink sink, BooleanSupplier isLeaderGate) {
        if (!isLeaderGate.getAsBoolean()) {return;}
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
