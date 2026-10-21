// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicPublisherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicPublisherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicTier;
import org.pragmatica.aether.slice.kvstore.AetherKey_TopicPublisherKeyCodec;
import org.pragmatica.aether.slice.kvstore.AetherValue_TopicPublisherValueCodec;
import org.pragmatica.aether.slice.kvstore.AetherValue_TopicSubscriptionValueCodec;
import org.pragmatica.aether.slice.kvstore.AetherValue_TopicTierCodec;
import org.pragmatica.aether.slice.kvstore.LegacyTopicSubscriptionValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// #1337: `TopicSubscriptionValue` gained `tier`, and a record is a positional concatenation, so the new shape cannot read the old
/// bytes. A snapshot decodes as one unit, so one old-shape row under the tag that now meant the new shape would fail the whole
/// restore and leave the node syncing. The change is therefore ADDITIVE: the new shape has its own tag (2169), and tag 1655 keeps
/// decoding the old shape, as the current type with an UNDECLARED tier. Encoding always writes the new tag.
///
/// The old bytes below are not produced by this build's writer: they were captured by running the PRE-change encoder
/// (`NodeCodecs` at the rc5 commit this change is based on) over the same two objects.
class TopicSubscriptionValueWireCompatibilityTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final NodeId NODE = new NodeId("node-a");
    private static final ResourceAddress ADDRESS = ResourceAddress.resourceAddress(ResourceAddress.DEFAULT_NAMESPACE, "orders", ResourceVersion.defaultVersion()).unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:order-sub:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("onOrder").unwrap();

    /// Tag 1655 (VLQ f70c), then the one component: NodeId `node-a`. What a build without `tier` wrote for `topicSubscriptionValue(node-a)`.
    private static final String OLD_VALUE_BYTES = "f70c1511066e6f64652d61";
    /// The key of that row, from the same encoder run. The key is unchanged by this change.
    private static final String KEY_BYTES = "ef08cbf556d8f277110764656661756c74eeac6f11066f7264657273dfe1320d000000010d000000000d00000000c001c301110b6f72672e6578616d706c65c20111096f726465722d737562c4010d000000010d000000000d00000000110067076f6e4f726465721511066e6f64652d61";

    private static TopicSubscriptionKey key() {
        return TopicSubscriptionKey.topicSubscriptionKey(ADDRESS, ARTIFACT, METHOD, NODE);
    }

    @Test
    void theTagsAreAssigned_theOldTagKeepsTheOldShape_theCurrentShapeAndItsEnumTakeNewOnes() {
        assertThat(LegacyTopicSubscriptionValue.TAG).isEqualTo(1655);
        assertThat(AetherValue_TopicSubscriptionValueCodec.TAG).isEqualTo(2169);
        assertThat(AetherValue_TopicTierCodec.TAG).isEqualTo(2170);
        assertThat(AetherKey_TopicPublisherKeyCodec.TAG).as("2171..2173 belong to another branch's gossip/prefetch types").isEqualTo(2174);
        assertThat(AetherValue_TopicPublisherValueCodec.TAG).isEqualTo(2175);
    }

    /// The publisher row is new in this change, so there are no old bytes for it; it must still round-trip with every tier.
    @Test
    void aPublisherRow_roundTripsItsKeyAndEveryTier() {
        var key = TopicPublisherKey.topicPublisherKey(ADDRESS, ARTIFACT, NODE);

        assertThat((Object) CODEC.decode(CODEC.encode(key))).isEqualTo(key);
        for (var tier : TopicTier.values()) {
            var row = TopicPublisherValue.topicPublisherValue(NODE, tier);

            assertThat((Object) CODEC.decode(CODEC.encode(row))).as("tier %s", tier).isEqualTo(row);
        }
    }

    /// The tier enum gained EPHEMERAL (publisher rows) before UNKNOWN: pin the ordinals the wire carries.
    @Test
    void theTierOrdinals_areThePinnedOnes() {
        assertThat(TopicTier.values()).containsExactly(TopicTier.UNDECLARED, TopicTier.ANY, TopicTier.DURABLE, TopicTier.EPHEMERAL, TopicTier.UNKNOWN);
    }

    @Test
    void anOldTagRow_decodesAsTheCurrentTypeWithAnUndeclaredTier() {
        var decoded = (TopicSubscriptionValue) CODEC.decode(HexFormat.of().parseHex(OLD_VALUE_BYTES));

        assertThat(decoded).isEqualTo(TopicSubscriptionValue.topicSubscriptionValue(NODE, TopicTier.UNDECLARED));
        assertThat(decoded.tier().requiresDurable()).as("an old row imposes no requirement").isFalse();
    }

    @Test
    void theOldKeyStillDecodes_soAWholeOldRowIsReadable() {
        var decoded = (TopicSubscriptionKey) CODEC.decode(HexFormat.of().parseHex(KEY_BYTES));

        assertThat(decoded).isEqualTo(key());
        assertThat(HexFormat.of().formatHex(CODEC.encode(key()))).as("the key encoding is unchanged").isEqualTo(KEY_BYTES);
    }

    @Test
    void theOldShapeEncodesToTheBytesAnOlderBuildWrote() {
        assertThat(HexFormat.of().formatHex(CODEC.encode(new LegacyTopicSubscriptionValue(NODE)))).isEqualTo(OLD_VALUE_BYTES);
    }

    @Test
    void aCurrentRow_alwaysEncodesUnderTheNewTag_andRoundTripsItsTier() {
        for (var tier : TopicTier.values()) {
            var row = TopicSubscriptionValue.topicSubscriptionValue(NODE, tier);
            var bytes = CODEC.encode(row);
            Object decoded = CODEC.decode(bytes);

            assertThat(HexFormat.of().formatHex(bytes)).as("tier %s", tier).startsWith("f910");
            assertThat(decoded).as("tier %s", tier).isEqualTo(row);
        }
    }

    @Test
    void aSnapshotMixingAnOldRowAndNewRows_restores() {
        var writer = SliceCodec.sliceCodec(CODEC, List.of(LegacyTopicSubscriptionValue.CODEC));
        var older = new KVStore<AetherKey, Object>(MessageRouter.mutable(), writer, writer);
        var other = TopicSubscriptionKey.topicSubscriptionKey(ADDRESS, ARTIFACT, METHOD, new NodeId("node-b"));
        var third = TopicSubscriptionKey.topicSubscriptionKey(ADDRESS, ARTIFACT, MethodName.methodName("onOther").unwrap(), NODE);

        older.process(older.createBatch(List.of(new Put<>(key(), new LegacyTopicSubscriptionValue(NODE)),
                                                new Put<>(other, TopicSubscriptionValue.topicSubscriptionValue(new NodeId("node-b"), TopicTier.DURABLE)),
                                                new Put<>(third, TopicSubscriptionValue.topicSubscriptionValue(NODE, TopicTier.ANY)))));
        var snapshot = older.makeSnapshot().unwrap();

        var restored = new KVStore<AetherKey, Object>(MessageRouter.mutable(), CODEC, CODEC);

        assertThat(restored.restoreSnapshot(snapshot).isSuccess()).as("one old-shape row must not fail the restore").isTrue();
        assertThat(restored.get(key()).unwrap()).isEqualTo(TopicSubscriptionValue.topicSubscriptionValue(NODE, TopicTier.UNDECLARED));
        assertThat(restored.get(other).unwrap()).isEqualTo(TopicSubscriptionValue.topicSubscriptionValue(new NodeId("node-b"), TopicTier.DURABLE));
        assertThat(restored.get(third).unwrap()).isEqualTo(TopicSubscriptionValue.topicSubscriptionValue(NODE, TopicTier.ANY));
    }
}
