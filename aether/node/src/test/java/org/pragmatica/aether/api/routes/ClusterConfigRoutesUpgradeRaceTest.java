// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import io.netty.buffer.ByteBuf;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import org.pragmatica.aether.api.ManagementApiResponses.UpgradeRequest;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// v-cw1 probe for #1424 (b): the upgrade fence must be ATOMIC with the write. A competing write (a scale) commits
/// AFTER the route read the config and passed the fence, but BEFORE the upgrade commits. The REAL KVStore applier
/// is used (not a map fake), so the RFC-0018 successor arm is the production one. The upgrade must be refused as a
/// VersionConflict and the scale must survive.
class ClusterConfigRoutesUpgradeRaceTest {
    private static final NodeId CORE = new NodeId("core");
    private static final TopologyEntry CORE_3 = new TopologyEntry("eu", "core", 3);
    private static final TopologyEntry CORE_5 = new TopologyEntry("eu", "core", 5);

    @Test
    void upgrade_whoseFencePassed_butAScaleCommitsBeforeItsWrite_isRefused_andTheScaleSurvives() {
        var store = new RacingStore(seed(1, "1.0.0", CORE_3), seed(2, "1.0.0", CORE_5));

        var result = ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store))
                                        .handleUpgrade(new UpgradeRequest("1.1.0", 1))
                                        .await();

        assertThat(store.injected).as("instrument: the competing write must have been injected mid-request").isTrue();
        assertThat(result.isFailure()).as("an upgrade built on a read the scale invalidated must be refused, got " + result)
                                      .isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.VersionConflict.class));

        var committed = (ClusterConfigValue) store.get(ClusterConfigKey.CURRENT).unwrap();

        assertThat(committed.desiredTopology()).as("the concurrent scale must not be lost").containsExactly(CORE_5);
        assertThat(committed.version()).as("the refused upgrade must not land").isEqualTo("1.0.0");
        assertThat(committed.configVersion()).isEqualTo(2);
    }

    /// Control: with no competing write the same request lands, so the refusal above is the race, not the harness.
    @Test
    void upgrade_withNoCompetingWrite_lands() {
        var store = new RacingStore(seed(1, "1.0.0", CORE_3), null);

        var result = ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store))
                                        .handleUpgrade(new UpgradeRequest("1.1.0", 1))
                                        .await();

        assertThat(result.isSuccess()).as(result.toString()).isTrue();
        var committed = (ClusterConfigValue) store.get(ClusterConfigKey.CURRENT).unwrap();

        assertThat(committed.version()).isEqualTo("1.1.0");
        assertThat(committed.configVersion()).isEqualTo(2);
    }

    private static ClusterConfigValue seed(long configVersion, String version, TopologyEntry core) {
        return ClusterConfigValue.bootstrapSeed("prod", version, List.of(core), 3, 9, "hetzner", configVersion);
    }

    private static ManageableNode nodeWith(RacingStore store) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> dispatch(store, method, args));
    }

    @SuppressWarnings("unchecked")
    private static Object dispatch(RacingStore store, Method method, Object[] args) {
        return switch (method.getName()) {
            case "kvStore" -> store;
            case "isLeader" -> true;
            case "apply" -> Promise.success(store.process(store.createBatch((List<KVCommand<AetherKey>>) args[0])));
            default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
        };
    }

    /// The real applier. The FIRST read of the cluster config (the route's fence read) returns the committed value;
    /// the competing write is committed immediately after it, so every later read and the commit see it.
    private static final class RacingStore extends KVStore<AetherKey, AetherValue> {
        private final ClusterConfigValue competing;
        private boolean injected;
        private int configReads;

        @SuppressWarnings({"rawtypes", "unchecked"})
        RacingStore(ClusterConfigValue initial, ClusterConfigValue competing) {
            super(MessageRouter.mutable(), new Serializer() {
                @Override
                public <T> void write(ByteBuf buffer, T value) {}
            }, new Deserializer() {
                @Override
                public <T> T read(ByteBuf buffer) {
                    return null;
                }
            });
            this.competing = competing;
            process(createBatch(List.of((KVCommand) new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 1)))));
            process(createBatch(List.of((KVCommand) new KVCommand.Put<>(ClusterConfigKey.CURRENT, initial))));
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public synchronized Option<AetherValue> get(AetherKey key) {
            var value = super.get(key);

            if (key == ClusterConfigKey.CURRENT && configReads++ == 0 && competing != null) {
                process(createBatch(List.of((KVCommand) new KVCommand.Put<>(ClusterConfigKey.CURRENT, competing))));
                injected = super.get(key).equals(Option.some(competing));
            }

            return value;
        }
    }
}
