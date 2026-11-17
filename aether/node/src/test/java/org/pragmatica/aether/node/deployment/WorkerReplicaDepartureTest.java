// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.node.ReplicaIncarnationSource;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.ObservedSliceListener;
import org.pragmatica.aether.slice.kvstore.ReplicaFilter;
import org.pragmatica.cluster.replica.ReplicaApplier;
import org.pragmatica.cluster.state.kvstore.CommitRing;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThat;

/// F5a (know `63ff3388bd`, R-1.6.6, G-N.1): a departure record reaches a WORKER's traffic feed over the replica path. The worker store is a
/// real replica (production codec and `ReplicaFilter`) installed from a real core store's filtered snapshot; the readers are bound by the
/// production `DeploymentWiring.registerReaders` for a worker, and the traffic listener is a real `EndpointRegistry`. Red if `ReplicaFilter`
/// EXCLUDES `NodeDepartureKey`: the worker never sees the record and keeps routing to the departed node's endpoint.
class WorkerReplicaDepartureTest {
    private static final NodeId CORE = Fsm2Harness.SELF;
    private static final NodeId DEPARTING = Fsm2Harness.PEER;
    private static final Artifact ORDERS = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final MethodName HANDLE = MethodName.methodName("handle").unwrap();

    private static final ObservedSliceListener IGNORING = new ObservedSliceListener() {
        @Override
        public void onObservedSlice(SliceObservedKey key, SliceObservedValue row) {}

        @Override
        public void onObservedSliceGone(SliceObservedKey key) {}
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void workerReplica_departureRecord_dropsTheDepartedNodesEndpoints() {
        try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, true)) {
            var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
            var workerRouter = MessageRouter.mutable();
            var core = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), codec, codec, ReplicaFilter.replicaFilter(), ReplicaIncarnationSource.replicaIncarnationSource(), CommitRing.DEFAULT_BUDGET_BYTES);
            var worker = new KVStore<AetherKey, AetherValue>(workerRouter, codec, codec, ReplicaFilter.replicaFilter(), ReplicaIncarnationSource.replicaIncarnationSource(), CommitRing.DEFAULT_BUDGET_BYTES);
            var applier = ReplicaApplier.replicaApplier(worker, 1 << 20);
            var registry = EndpointRegistry.endpointRegistry();
            var builder = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class);

            harness.wiring.registerReaders(builder, List.of(IGNORING), List.of(registry));
            builder.build()
                   .asRouteEntries()
                   .forEach(entry -> entry.entries()
                                          .forEach(route -> workerRouter.addRoute((Class) route.first(), (Consumer) route.last())));

            var key = SliceObservedKey.sliceObservedKey(DEPARTING, ORDERS);
            var leader = new LeaderValue(CORE, 3L);
            var row = new SliceObservedValue(SliceObservedState.ACTIVE, 1L, new Generation(1L, 1L), new ProcessIncarnation(1L, 0L), Option.none(), false, 1, List.of("handle"), 0L);
            var departed = new NodeDepartureValue(DepartureState.DEPARTED, 1L, new ProcessIncarnation(1L, 0L), Option.none(), 0L);

            commit(core, 1L, new KVCommand.Put(LeaderKey.INSTANCE, leader), new KVCommand.Put(key, row));
            resync(core, worker, applier);

            assertThat(registry.findEndpoints(ORDERS, HANDLE)).as("control: the replicated ACTIVE row registers the endpoint on the worker")
                                                              .extracting(EndpointRegistry.Endpoint::nodeId)
                                                              .containsExactly(DEPARTING);

            var departureKey = NodeDepartureKey.nodeDepartureKey(DEPARTING);

            commit(core,
                   2L,
                   new KVCommand.LeaderTransaction(departureKey, "tx-1", leader, List.of(), List.of(new KVCommand.Mutation(departureKey, Option.none(), Option.some(departed)))));
            resync(core, worker, applier);

            assertThat(worker.get(departureKey).isPresent()).as("the departure record is in the worker replica").isTrue();
            assertThat(registry.findEndpoints(ORDERS, HANDLE)).as("G-N.1: traffic drops the node once its DEPARTED record commits").isEmpty();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void commit(KVStore<AetherKey, AetherValue> core, long slot, KVCommand... commands) {
        var raw = (KVStore) core;

        raw.processCommitted(raw.createBatch((List) List.of(commands)), slot);
        raw.onPhaseCompleted(slot);
    }

    private static void resync(KVStore<AetherKey, AetherValue> core, KVStore<AetherKey, AetherValue> worker, ReplicaApplier<AetherKey, AetherValue> applier) {
        var snapshot = core.captureReplicaSnapshot(ReplicaFilter.replicaFilter()).unwrap();

        applier.beginBuffering();
        applier.install(snapshot.bytes(), snapshot.tag(), snapshot.revision(), snapshot.anchor()).unwrap();
        applier.beginLive();
    }
}
