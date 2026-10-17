// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeReplacementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SystemTags;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 part D: the replacement pairing travels through consensus, so its key, value and phase need pinned wire
/// tags (an unpinned one makes every node fail to boot) and must survive the node codec intact.
class NodeReplacementCodecTest {
    @Test
    void pairingTypes_havePinnedSystemTags() {
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherKey.NodeReplacementKey")).isEqualTo(2129);
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue")).isEqualTo(2130);
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase")).isEqualTo(2131);
    }

    @Test
    void pairingPut_roundTrips_throughTheNodeCodec_inEveryPhase() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var key = new NodeReplacementKey(new NodeId("core-old"));

        for (var phase : NodeReplacementPhase.values()) {
            var value = new NodeReplacementValue(new NodeId("core-01JREPLACEMENT"), "core", phase, 1_760_000_000_000L);
            var buf = Unpooled.buffer();

            codec.write(buf, new KVCommand.Put<AetherKey, AetherValue>(key, value));
            KVCommand.Put<?, ?> decoded = codec.read(buf);

            assertThat(decoded.key()).isEqualTo(key);
            assertThat(decoded.value()).as(phase.name()).isEqualTo(value);
        }
        assertThat(key.asString()).isEqualTo("node-replacement/core-old");
    }
}
