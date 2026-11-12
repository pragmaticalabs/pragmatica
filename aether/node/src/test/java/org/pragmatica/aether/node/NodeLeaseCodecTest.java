// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HashMap;
import java.util.Map;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLeaseKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLeaseValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SystemTags;
import org.pragmatica.serialization.UnknownTypeTagException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #2981 step 1: the node lease renewal row crosses consensus, so its key and value need pinned tags (2910, 2911) and a
/// lossless binary round trip through the node codec.
class NodeLeaseCodecTest {
    private static final String KEY = "org.pragmatica.aether.slice.kvstore.AetherKey.NodeLeaseKey";
    private static final String VALUE = "org.pragmatica.aether.slice.kvstore.AetherValue.NodeLeaseValue";
    private static final NodeId NODE = NodeId.nodeId("lease-7").unwrap();

    @Test
    void keyAndValue_haveTheirAssignedSystemTags() {
        assertThat(SystemTags.tagFor(KEY)).isEqualTo(2910);
        assertThat(SystemTags.tagFor(VALUE)).isEqualTo(2911);
    }

    @Test
    void keyAndValue_areRegisteredInTheNodeCodecUnderTheirPins() {
        var tagsByName = new HashMap<String, Integer>();

        NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs())
                  .registeredTypes()
                  .forEach((type, codec) -> tagsByName.put(type.getCanonicalName(), codec.tag()));

        assertThat(tagsByName.get(KEY)).isEqualTo(2910);
        assertThat(tagsByName.get(VALUE)).isEqualTo(2911);
    }

    @Test
    void put_roundTripsForBoundarySeqValues() {
        for (var seq : new long[] {0L, 1L, 1_760_000_000_123_456L, Long.MAX_VALUE, -1L, Long.MIN_VALUE}) {
            roundTrip(NodeLeaseValue.nodeLeaseValue(seq));
        }
    }

    /// The executable form of design §7 step 6's stop-the-world claim (v3054 should-fix): a node codec that predates tag 2910 (an rc5 node
    /// without the lease) cannot decode a lease renewal Put from a consensus batch; it throws `UnknownTypeTagException` instead of skipping
    /// the command, which is why a mixed-version window is refused. Control: the same bytes decode with the full node codec.
    @Test
    void put_isUndecodable_byACodecWithoutTheLeaseKeyTag() {
        var full = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var buffer = Unpooled.buffer();

        full.write(buffer, new KVCommand.Put<AetherKey, AetherValue>(NodeLeaseKey.nodeLeaseKey(NODE), NodeLeaseValue.nodeLeaseValue(1)));
        var encoded = ByteBufUtil.getBytes(buffer);
        var withoutLease = codecWithout(NodeLeaseKey.class);
        KVCommand.Put<?, ?> control = full.read(Unpooled.wrappedBuffer(encoded));

        assertThat(control.key()).as("control: the full codec decodes the renewal").isEqualTo(NodeLeaseKey.nodeLeaseKey(NODE));
        assertThatThrownBy(() -> withoutLease.read(Unpooled.wrappedBuffer(encoded))).isInstanceOf(UnknownTypeTagException.class);
    }

    private static SliceCodec codecWithout(Class<?> removed) {
        var framework = FrameworkCodecs.frameworkCodecs();
        var inherited = framework.registeredTypes()
                                 .keySet();
        var codecs = NodeCodecs.nodeCodecs(framework)
                               .registeredTypes()
                               .entrySet()
                               .stream()
                               .filter(entry -> !inherited.contains(entry.getKey()) && !entry.getKey().equals(removed))
                               .<SliceCodec.TypeCodec<?>>map(Map.Entry::getValue)
                               .toList();

        return SliceCodec.sliceCodec(framework, codecs);
    }

    private void roundTrip(AetherValue value) {
        var key = NodeLeaseKey.nodeLeaseKey(NODE);
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
