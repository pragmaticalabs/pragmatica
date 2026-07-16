// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.OwnershipResponse;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #345 item 1f — exercises the committed-ownership + fence-diagnostic assembler behind
/// `GET /api/ownership/{domain}`. Seeds a real `KVStore` with one ownership atom of each type, builds
/// the node's LOCAL epoch high-water table over it, and asserts the per-domain response carries the
/// right identity/owner/fence-epoch, that `highWater` mirrors the committed epoch (so `fenced` is
/// `false`) in steady state, that advancing a domain's high-water past the committed epoch flips
/// `fenced` to `true`, that an unknown domain is a clean typed failure (not an exception), and that
/// an empty domain yields an empty (successful) list.
class ClusterOwnershipRoutesTest {
    private static final NodeId GOVERNOR = NodeId.nodeId("gov-1").unwrap();
    private static final NodeId DHT_OWNER = NodeId.nodeId("core-1").unwrap();
    private static final NodeId STREAM_OWNER = NodeId.nodeId("core-2").unwrap();
    private static final Epoch COMMUNITY_EPOCH = Epoch.epoch(5, 2);
    private static final Epoch DHT_EPOCH = Epoch.epoch(6, 1);
    private static final Epoch STREAM_EPOCH = Epoch.epoch(7, 3);

    private KVStore<AetherKey, AetherValue> store;
    private OwnershipEpochHighWater highWater;
    private ManageableNode node;

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        seed(GovernorAnnouncementKey.forCommunity("prod:us-east-1"),
             GovernorAnnouncementValue.governorAnnouncementValue(GOVERNOR,
                                                                 List.of(GOVERNOR),
                                                                 "10.0.0.1:7201",
                                                                 1000L,
                                                                 COMMUNITY_EPOCH.rabiaTerm(),
                                                                 COMMUNITY_EPOCH,
                                                                 Epoch.ZERO,
                                                                 HlcTimestamp.ZERO,
                                                                 false));
        seed(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey("partition-3"),
             DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(DHT_OWNER,
                                                                   "core",
                                                                   DHT_EPOCH,
                                                                   DHT_EPOCH.localCounter(),
                                                                   HlcTimestamp.ZERO));
        seed(StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 4),
             StreamPartitionOwnershipValue.streamPartitionOwnershipValue(STREAM_OWNER,
                                                                         STREAM_EPOCH,
                                                                         STREAM_EPOCH.localCounter(),
                                                                         HlcTimestamp.ZERO));
        highWater = OwnershipEpochHighWater.ownershipEpochHighWater(store);
        node = nodeOver(store, highWater);
    }

    @Nested
    class PerDomain {
        @Test
        void assembleOwnershipResponse_communityEntry_carriesGovernorAndFenceEpoch() {
            ClusterTopologyRoutes.assembleOwnershipResponse(node, "community")
                                 .onFailure(cause -> fail("community ownership must succeed: " + cause.message()))
                                 .onSuccess(response -> {
                                     assertThat(response.domain()).isEqualTo("community");
                                     assertThat(response.entries()).hasSize(1);

                                     var entry = response.entries().getFirst();

                                     assertThat(entry.identity()).isEqualTo("prod:us-east-1");
                                     assertThat(entry.owner()).isEqualTo("gov-1");
                                     assertThat(entry.epoch().rabiaTerm()).isEqualTo(5);
                                     assertThat(entry.epoch().localCounter()).isEqualTo(2);
                                     assertThat(entry.highWater().rabiaTerm()).isEqualTo(5);
                                     assertThat(entry.highWater().localCounter()).isEqualTo(2);
                                     assertThat(entry.fenced()).isFalse();
                                 });
        }

        @Test
        void assembleOwnershipResponse_dhtEntry_carriesOwnerAndFenceEpoch() {
            ClusterTopologyRoutes.assembleOwnershipResponse(node, "dht")
                                 .onFailure(cause -> fail("dht ownership must succeed: " + cause.message()))
                                 .onSuccess(response -> {
                                     assertThat(response.domain()).isEqualTo("dht");
                                     assertThat(response.entries()).hasSize(1);

                                     var entry = response.entries().getFirst();

                                     assertThat(entry.identity()).isEqualTo("partition-3");
                                     assertThat(entry.owner()).isEqualTo("core-1");
                                     assertThat(entry.epoch().rabiaTerm()).isEqualTo(6);
                                     assertThat(entry.epoch().localCounter()).isEqualTo(1);
                                     assertThat(entry.highWater().rabiaTerm()).isEqualTo(6);
                                     assertThat(entry.highWater().localCounter()).isEqualTo(1);
                                     assertThat(entry.fenced()).isFalse();
                                 });
        }

        @Test
        void assembleOwnershipResponse_streamEntry_identityIsStreamColonPartition() {
            ClusterTopologyRoutes.assembleOwnershipResponse(node, "stream")
                                 .onFailure(cause -> fail("stream ownership must succeed: " + cause.message()))
                                 .onSuccess(response -> {
                                     assertThat(response.domain()).isEqualTo("stream");
                                     assertThat(response.entries()).hasSize(1);

                                     var entry = response.entries().getFirst();

                                     assertThat(entry.identity()).isEqualTo("orders:4");
                                     assertThat(entry.owner()).isEqualTo("core-2");
                                     assertThat(entry.epoch().rabiaTerm()).isEqualTo(7);
                                     assertThat(entry.epoch().localCounter()).isEqualTo(3);
                                     assertThat(entry.highWater().rabiaTerm()).isEqualTo(7);
                                     assertThat(entry.highWater().localCounter()).isEqualTo(3);
                                     assertThat(entry.fenced()).isFalse();
                                 });
        }
    }

    @Nested
    class FenceWindow {
        @Test
        void assembleOwnershipResponse_highWaterAheadOfCommitted_marksEntryFenced() {
            highWater.advance(OwnershipDomain.dhtPartition("partition-3"), Epoch.epoch(9, 0));

            ClusterTopologyRoutes.assembleOwnershipResponse(node, "dht")
                                 .onFailure(cause -> fail("dht ownership must succeed: " + cause.message()))
                                 .onSuccess(response -> {
                                     var entry = response.entries().getFirst();

                                     assertThat(entry.fenced()).isTrue();
                                     assertThat(entry.epoch().rabiaTerm()).isEqualTo(6);
                                     assertThat(entry.epoch().localCounter()).isEqualTo(1);
                                     assertThat(entry.highWater().rabiaTerm()).isEqualTo(9);
                                     assertThat(entry.highWater().localCounter()).isEqualTo(0);
                                 });
        }

        @Test
        void assembleOwnershipResponse_highWaterEqualsCommitted_isNotFenced() {
            ClusterTopologyRoutes.assembleOwnershipResponse(node, "stream")
                                 .onFailure(cause -> fail("stream ownership must succeed: " + cause.message()))
                                 .onSuccess(response -> {
                                     var entry = response.entries().getFirst();

                                     assertThat(entry.fenced()).isFalse();
                                     assertThat(entry.highWater().rabiaTerm()).isEqualTo(7);
                                     assertThat(entry.highWater().localCounter()).isEqualTo(3);
                                 });
        }
    }

    @Nested
    class EdgeCases {
        @Test
        void assembleOwnershipResponse_unknownDomain_isCleanTypedFailure() {
            ClusterTopologyRoutes.assembleOwnershipResponse(node, "bogus")
                                 .onSuccess(_ -> fail("unknown domain must fail"))
                                 .onFailure(cause -> assertThat(cause.message()).contains("Unknown ownership domain")
                                                                                .contains("bogus"));
        }

        @Test
        void assembleOwnershipResponse_emptyStore_isEmptySuccessList() {
            var emptyStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
            var emptyNode = nodeOver(emptyStore, OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore));

            ClusterTopologyRoutes.assembleOwnershipResponse(emptyNode, "dht")
                                 .onFailure(cause -> fail("empty store must succeed: " + cause.message()))
                                 .onSuccess(response -> assertThat(response.entries()).isEmpty());
        }

        @Test
        void assembleOwnershipResponse_domainIsolation_dhtDoesNotLeakCommunity() {
            ClusterTopologyRoutes.assembleOwnershipResponse(node, "dht")
                                 .onSuccess(response -> assertThat(response.entries())
                                     .allSatisfy(entry -> assertThat(entry.identity()).isEqualTo("partition-3")));
        }
    }

    private void seed(AetherKey key, AetherValue value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
    }

    private static ManageableNode nodeOver(KVStore<AetherKey, AetherValue> store, OwnershipEpochHighWater highWater) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "kvStore" -> store;
                                                           case "ownershipEpochHighWater" -> Option.some(highWater);
                                                           default -> unsupported(method.getName());
                                                       });
    }

    private static Object unsupported(String name) {
        throw new UnsupportedOperationException("Not implemented in test proxy: " + name);
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
