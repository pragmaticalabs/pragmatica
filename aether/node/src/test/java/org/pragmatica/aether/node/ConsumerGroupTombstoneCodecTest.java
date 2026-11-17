// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerGroupKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupTombstone;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SystemTags;

import static org.assertj.core.api.Assertions.assertThat;

/// #2635: the empty-group tombstone travels through consensus, so it needs a pinned wire tag (an unpinned one makes
/// every node fail to encode the clear at runtime) and must survive the node codec intact.
class ConsumerGroupTombstoneCodecTest {
    @Test
    void consumerGroupTombstone_hasAPinnedSystemTag() {
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupTombstone")).isEqualTo(2900);
    }

    @Test
    void consumerGroupTombstonePut_roundTrips_throughTheNodeCodec() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var key = ConsumerGroupKey.consumerGroupKey("group", "stream", 3);
        var value = ConsumerGroupTombstone.consumerGroupTombstone(Epoch.epoch(2L, 3L, 4L));
        var buf = Unpooled.buffer();

        codec.write(buf, new KVCommand.Put<AetherKey, AetherValue>(key, value));
        KVCommand.Put<?, ?> decoded = codec.read(buf);

        assertThat(decoded.key()).isEqualTo(key);
        assertThat(decoded.value()).isEqualTo(value);
    }
}
