// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistryHolderKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistryHolderValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThat;

/// #1978 / #2160 (NT-174, I-36): the per-holder stream-registry key and value cross the wire: they survive an encode and a decode, and
/// their hand-assigned tags are the ones in the baseline (`WireAssignmentTripwireTest` and `SystemCodecPinningTest` hold the tags
/// themselves; this holds that a value written under them reads back).
class StreamRegistryHolderWireTest {
    private static final ResourceAddress ORDERS = ResourceAddress.resourceAddress("com.example.app:orders:1.0.0").unwrap();
    private static final Artifact SLICE = Artifact.artifact("com.example:orders-slice:1.0.0").unwrap();

    @Test
    void holderKey_roundTripsThroughTheNodeCodec() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var key = StreamRegistryHolderKey.streamRegistryHolderKey(ORDERS, NodeId.nodeId("node-1").unwrap(), SLICE, 7L);
        var buffer = Unpooled.buffer();

        codec.write(buffer, key);

        assertThat((Object) codec.read(buffer)).isEqualTo(key);
    }

    @Test
    void holderValue_roundTripsThroughTheNodeCodec() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var value = StreamRegistryHolderValue.streamRegistryHolderValue(1_700_000_000_000L);
        var buffer = Unpooled.buffer();

        codec.write(buffer, value);

        assertThat((Object) codec.read(buffer)).isEqualTo(value);
    }

    @Test
    void holderKeys_differByEveryComponent() {
        var node = NodeId.nodeId("node-1").unwrap();
        var base = StreamRegistryHolderKey.streamRegistryHolderKey(ORDERS, node, SLICE, 1L);

        assertThat(base.asString()).isNotEqualTo(StreamRegistryHolderKey.streamRegistryHolderKey(ORDERS, NodeId.nodeId("node-2").unwrap(), SLICE, 1L).asString());
        assertThat(base.asString()).isNotEqualTo(StreamRegistryHolderKey.streamRegistryHolderKey(ORDERS, node, SLICE, 2L).asString());
        assertThat(base.asString()).isNotEqualTo(StreamRegistryHolderKey.streamRegistryHolderKey(ORDERS, node, Artifact.artifact("com.example:other:1.0.0").unwrap(), 1L).asString());
    }
}
