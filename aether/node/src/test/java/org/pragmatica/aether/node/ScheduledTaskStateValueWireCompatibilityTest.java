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
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskClaimValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue_ScheduledTaskStateValueCodec;
import org.pragmatica.aether.slice.kvstore.LegacyScheduledTaskStateValue;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// #1155: `ScheduledTaskStateValue` gained `boundHolder`, and a record is a positional concatenation, so the new shape cannot read
/// the old bytes. A snapshot decodes as one unit, so one old-shape row under the tag that now meant the new shape would fail the
/// whole restore and leave the node syncing. The change is therefore ADDITIVE: the new shape has its own tag (2168), and tag 1634
/// keeps decoding the old shape, as the current type with no bound holder. Encoding always writes the new tag.
class ScheduledTaskStateValueWireCompatibilityTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:scheduled-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("cleanup").unwrap();
    private static final LegacyScheduledTaskStateValue OLD_SHAPE = new LegacyScheduledTaskStateValue(1000, 2000, 1, 5, "boom", 3000, 2, "SUCCESS", 7, 4000, 3, 1);
    private static final ScheduledTaskStateValue UNBOUND = new ScheduledTaskStateValue(1000, 2000, 1, 5, "boom", 3000, 2, "SUCCESS", 7, 4000, 3, 1);
    /// Tag 1634 (VLQ e20c), then the twelve components of the shape rc5 shipped: the encoding such a build wrote for [#UNBOUND].
    private static final String OLD_BYTES = "e20c0e00000000000003e80e00000000000007d00d000000010d000000051104626f6f6d0e0000000000000bb80d00000002"
                                            + "1107535543434553530d000000070e0000000000000fa00d000000030d00000001";
    /// The same twelve components under the new tag 2168 (VLQ f810), then `boundHolder` = none (tag 1).
    private static final String NEW_BYTES_UNBOUND = "f810" + OLD_BYTES.substring(4) + "01";

    private static ScheduledTaskStateKey key(String section) {
        return ScheduledTaskStateKey.scheduledTaskStateKey(section, ARTIFACT, METHOD);
    }

    @Test
    void theTagsAreAssigned_theOldTagKeepsTheOldShape_theCurrentShapeTakesANewOne() {
        assertThat(LegacyScheduledTaskStateValue.TAG).isEqualTo(1634);
        assertThat(AetherValue_ScheduledTaskStateValueCodec.TAG).isEqualTo(2168);
    }

    @Test
    void anOldTagRow_decodesAsTheCurrentTypeWithNoBoundHolder() {
        var decoded = (ScheduledTaskStateValue) CODEC.decode(HexFormat.of().parseHex(OLD_BYTES));

        assertThat(decoded).isEqualTo(UNBOUND);
        assertThat(decoded.boundHolder().isPresent()).isFalse();
    }

    @Test
    void theOldShapeEncodesToTheBytesAnOlderBuildWrote() {
        assertThat(HexFormat.of().formatHex(CODEC.encode(OLD_SHAPE))).isEqualTo(OLD_BYTES);
    }

    /// The old bytes are not only what this test's own writer emits: the generated codec of the current shape, an independent
    /// producer, writes the same twelve components and then one more.
    @Test
    void aCurrentRow_alwaysEncodesUnderTheNewTag_andTheOldBodyIsItsPrefix() {
        assertThat(HexFormat.of().formatHex(CODEC.encode(UNBOUND))).isEqualTo(NEW_BYTES_UNBOUND);

        var bound = UNBOUND.boundTo(Option.some("claim-token"));
        var bytes = CODEC.encode(bound);

        Object decoded = CODEC.decode(bytes);

        assertThat(HexFormat.of().formatHex(bytes)).startsWith("f810");
        assertThat(decoded).isEqualTo(bound);
    }

    @Test
    void aSnapshotMixingAnOldRowAndNewRows_restores() {
        var writer = SliceCodec.sliceCodec(CODEC, List.of(LegacyScheduledTaskStateValue.CODEC));
        var older = new KVStore<AetherKey, Object>(MessageRouter.mutable(), writer, writer);

        // A bound row is admitted only under the claim it names, as in a node.
        var claim = ScheduledTaskClaimValue.claimed(Option.none(), new NodeId("a"), 1, "claim-token", 1);

        older.process(older.createBatch(List.of(new Put<>(AetherKey.ScheduledTaskClaimKey.scheduledTaskClaimKey("bound", ARTIFACT, METHOD), claim),
                                                new Put<>(key("old"), OLD_SHAPE),
                                                new Put<>(key("unbound"), UNBOUND),
                                                new Put<>(key("bound"), UNBOUND.boundTo(Option.some("claim-token"))))));
        var snapshot = older.makeSnapshot().unwrap();

        var restored = new KVStore<AetherKey, Object>(MessageRouter.mutable(), CODEC, CODEC);

        assertThat(restored.restoreSnapshot(snapshot).isSuccess()).as("one old-shape row must not fail the restore").isTrue();
        assertThat(restored.get(key("old")).unwrap()).isEqualTo(UNBOUND);
        assertThat(restored.get(key("unbound")).unwrap()).isEqualTo(UNBOUND);
        assertThat(restored.get(key("bound")).unwrap()).isEqualTo(UNBOUND.boundTo(Option.some("claim-token")));
    }
}
