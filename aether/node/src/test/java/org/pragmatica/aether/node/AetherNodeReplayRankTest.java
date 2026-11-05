// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.state.kvstore.NotificationDelivery;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/// Exit (z), aether part (KVStore dispatcher design §3.7): the node store replays a burst rank-major in the aether rank,
/// so `VersionRouting` precedes `SliceTarget` (D0 PAIR-1) and `AppBlueprint` precedes `SliceTarget` and `NodeArtifact`
/// (PAIR-2), with `ClusterIncarnationKey` first. Mutations: the node delivery passes the flat rank (the wiring arm red) /
/// a rank entry changed (the order arm red). The Active-CDM arm (no UNLOAD of an old version, no ACTIVATE ahead of its
/// dependencies under a rolling-update burst) is `ClusterDeploymentManagerReplayBurstTest`.
class AetherNodeReplayRankTest {
    @TempDir
    Path tempDir;

    static final ArtifactBase BASE = ArtifactBase.artifactBase("org.example:app").unwrap();
    static final AetherKey.ClusterIncarnationKey INCARNATION = new AetherKey.ClusterIncarnationKey();
    static final AetherKey.VersionRoutingKey ROUTING = new AetherKey.VersionRoutingKey(BASE);
    static final AetherKey.AppBlueprintKey BLUEPRINT = new AetherKey.AppBlueprintKey(BlueprintId.blueprintId("org.example:app:1.0.0").unwrap());
    static final AetherKey.SliceTargetKey TARGET = new AetherKey.SliceTargetKey(BASE);
    static final AetherKey.NodeArtifactKey LOADED = new AetherKey.NodeArtifactKey(NodeId.nodeId("rank-node").unwrap(),
                                                                                  Artifact.artifact("org.example:app:1.0.0").unwrap());

    /// Installs whatever map the test sets, so a replay needs no codec.
    static final class InstallingDeserializer implements Deserializer {
        volatile Map<StructuredKey, Object> next = Map.of();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(ByteBuf buffer) {
            return (T) next;
        }
    }

    @Test
    void replayRank_ordersThePairsTheDesignNames() {
        assertThat(List.of(INCARNATION, ROUTING, BLUEPRINT, TARGET, LOADED)
                       .stream()
                       .map(AetherNode::replayRank)
                       .toList()).containsExactly(-1, 1, 2, 3, 4);
        assertThat(AetherNode.replayRank(new AetherKey.SchemaVersionKey("ds"))).isEqualTo(2);
    }

    @Test
    void nodeDelivery_carriesTheAetherRank() {
        var config = AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                       Option.none(),
                                                                       ConfigurationProvider.builder().build(),
                                                                       tempDir);
        var delivery = AetherNode.kvDelivery(config,
                                                 DispatcherFailureSink.loggingOnly(),
                                                 KvDispatchOperatorEvents.kvDispatchOperatorEvents());

        assertThat(delivery).isInstanceOf(NotificationDelivery.Dispatched.class);
        assertThat(delivery.replayRank().rank(ROUTING)).isEqualTo(1);
        assertThat(delivery.replayRank().rank(LOADED)).isEqualTo(4);
    }

    @Test
    @Timeout(value = 30, unit = SECONDS)
    void rankedBurstEmitsInRankOrder() {
        var router = MessageRouter.mutable();
        var deserializer = new InstallingDeserializer();
        var store = new KVStore<StructuredKey, Object>(router,
                                                       new Serializer() {
                                                           @Override
                                                           public <T> void write(ByteBuf buffer, T value) {}
                                                       },
                                                       deserializer,
                                                       NotificationDelivery.dispatched("rank",
                                                                                       AetherNode::replayRank,
                                                                                       DispatcherFailureSink.loggingOnly()));
        var order = new CopyOnWriteArrayList<Object>();

        router.addRoute(KVStoreNotification.ValuePut.class,
                        (KVStoreNotification.ValuePut<StructuredKey, Object> put) -> order.add(put.cause().key()));
        try {
            deserializer.next = Map.of(LOADED, "x", TARGET, "x", BLUEPRINT, "x", ROUTING, "x", INCARNATION, "x");
            store.restoreSnapshot(new byte[0]);
            store.replayNotifications();

            assertThat(order).containsExactly(INCARNATION, ROUTING, BLUEPRINT, TARGET, LOADED);
        } finally {
            store.close();
        }
    }
}
