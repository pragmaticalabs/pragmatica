// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HashMap;
import java.util.List;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentEngineKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.RuntimeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngineValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderAuthorized;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SystemTags;

import static org.assertj.core.api.Assertions.assertThat;

/// The engine marker's records (phase-3 design 8.2, W-34 durability and value-shape arms, MT-33, spec P3-Q29, H-5): the key, the value and
/// the engine enum cross consensus, so each is pinned and round-trips; the key is durable (a backup carries it), never a `RuntimeKey`.
class DeploymentEngineMarkerRecordsTest {
    private static final String KEY = "org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentEngineKey";
    private static final String VALUE = "org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngineValue";
    private static final String ENGINE = "org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine";
    private static final NodeId MINTER = NodeId.nodeId("core-3").unwrap();

    @Test
    void theThreeTypes_haveTheirAssignedSystemTags_andAreRegisteredInTheNodeCodec() {
        assertThat(SystemTags.tagFor(KEY)).isEqualTo(2160);
        assertThat(SystemTags.tagFor(VALUE)).isEqualTo(2161);
        assertThat(SystemTags.tagFor(ENGINE)).isEqualTo(2162);

        var tagsByName = new HashMap<String, Integer>();

        NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs())
                  .registeredTypes()
                  .forEach((type, codec) -> tagsByName.put(type.getCanonicalName(), codec.tag()));
        assertThat(tagsByName).containsEntry(KEY, 2160).containsEntry(VALUE, 2161).containsEntry(ENGINE, 2162);
    }

    /// MT-33 / P3-Q29: a `RuntimeKey` is excluded from backups, so no backup could carry the marker and a restore would turn an FSM2
    /// cluster into LEGACY. The marker is cluster state, and its entry goes into a backup.
    @Test
    void theKey_isDurableClusterState_notARuntimeKey() {
        var key = DeploymentEngineKey.deploymentEngineKey();

        assertThat(key).isInstanceOf(ClusterStateKey.class).isNotInstanceOf(RuntimeKey.class);
        assertThat(((ClusterStateKey) key).isBackedUp()).as("a backup carries the marker").isTrue();
    }

    @Test
    void theKey_parsesFromItsOwnString_andRefusesAnyOther() {
        var key = DeploymentEngineKey.deploymentEngineKey();

        assertThat(key.asString()).as("the string is in every backup and every mirror: it is a wire fact").isEqualTo("deployment-engine");
        assertThat(DeploymentEngineKey.deploymentEngineKey(key.asString()).unwrap()).isSameAs(key);
        assertThat(DeploymentEngineKey.deploymentEngineKey("cluster-incarnation").isFailure()).isTrue();
    }

    @Test
    void theValue_isLeaderAuthorized() {
        assertThat(LeaderAuthorized.class.isAssignableFrom(DeploymentEngineValue.class)).isTrue();
    }

    /// W-34 value shape (H-5): the marker carries the minting leader and the time, so a refusal can name the writer.
    @Test
    void theValue_roundTripsEveryEngine_withTheMintingLeaderAndTime() {
        for (var engine : List.of(DeploymentEngine.LEGACY, DeploymentEngine.FSM2)) {
            var value = new DeploymentEngineValue(engine, MINTER, 1_760_000_999_000L);

            roundTrip(DeploymentEngineKey.deploymentEngineKey(), value);
            assertThat(value.mintedBy()).isEqualTo(MINTER);
            assertThat(value.mintedAt()).isEqualTo(1_760_000_999_000L);
        }
    }

    private void roundTrip(AetherKey key, AetherValue value) {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var first = Unpooled.buffer();

        codec.write(first, new KVCommand.Put<AetherKey, AetherValue>(key, value));
        var encoded = ByteBufUtil.getBytes(first);
        KVCommand.Put<?, ?> decoded = codec.read(first);
        var second = Unpooled.buffer();

        codec.write(second, decoded);

        assertThat(decoded.key()).isEqualTo(key);
        assertThat(decoded.value()).isEqualTo(value);
        assertThat(ByteBufUtil.getBytes(second)).isEqualTo(encoded);
        assertThat(first.readableBytes()).as("bytes left after decoding").isZero();
    }
}
