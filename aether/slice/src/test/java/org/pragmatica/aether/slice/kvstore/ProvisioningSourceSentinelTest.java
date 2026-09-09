// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// #964 S2: `ProvisioningSource.UNKNOWN` is the WIRE sentinel and nothing else.
///
/// It already existed as a domain value before this change — a config typo and a null field both
/// produced it — so once `validateEnumSentinel` adopted the enum into the sentinel contract, "the
/// operator typed a bad value" and "this record came from a newer node" became the same value at the
/// point of use. A diagnostic that cannot tell its two causes apart has stopped discriminating, and
/// the two call for opposite actions: edit the config, versus finish the rolling upgrade.
///
/// The local causes now produce `UNRECOGNISED`. These tests pin that the two stay distinct, since
/// nothing else would notice them merging again — no branch reads the field.
class ProvisioningSourceSentinelTest {
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(),
                                                                  KvstoreCodecsSlice.CODECS);
    private static final NodeId NODE = NodeId.nodeId("node-a").unwrap();

    private static ByteBuf frameWithOrdinal(int ordinal) {
        var buf = Unpooled.buffer();

        SliceCodec.writeCompact(buf, AetherValue_ProvisioningSourceCodec.TAG);
        SliceCodec.writeCompact(buf, ordinal);

        return buf;
    }

    /// The wire cause. An ordinal past this node's `values()` decodes to the sentinel.
    @Test
    void anOrdinalThisNodeCannotName_decodesToUnknown() {
        ProvisioningSource decoded = CODEC.read(frameWithOrdinal(ProvisioningSource.values().length));

        assertThat(decoded).isEqualTo(ProvisioningSource.UNKNOWN);
    }

    /// The control: a known ordinal still round-trips, so the test above varies only the ordinal.
    @Test
    void aKnownOrdinal_decodesToThatConstant() {
        ProvisioningSource decoded = CODEC.read(frameWithOrdinal(ProvisioningSource.CTM.ordinal()));

        assertThat(decoded).isEqualTo(ProvisioningSource.CTM);
    }

    /// The local cause. A missing value is this node's own gap, not evidence about a peer, so it must
    /// NOT surface as the wire sentinel.
    @Test
    void anAbsentProvisioningSource_becomesUnrecognisedNotUnknown() {
        var member = new CoreMember(NODE, "127.0.0.1", 9000, HealthHint.HEALTHY, Epoch.ZERO, Epoch.ZERO, null);

        assertThat(member.provisioningSource()).isEqualTo(ProvisioningSource.UNRECOGNISED);
        assertThat(member.provisioningSource()).isNotEqualTo(ProvisioningSource.UNKNOWN);
    }

    /// The discriminating assertion, stated as the contrast that matters: the two causes are different
    /// values. If they are ever merged again, this is what goes red.
    @Test
    void theLocalCauseAndTheWireCauseAreDistinctValues() {
        ProvisioningSource fromWire = CODEC.read(frameWithOrdinal(ProvisioningSource.values().length));
        var fromMissingConfig = new CoreMember(NODE, "127.0.0.1", 9000, HealthHint.HEALTHY, Epoch.ZERO, Epoch.ZERO, null)
            .provisioningSource();

        assertThat(fromWire).isNotEqualTo(fromMissingConfig);
    }

    /// The sentinel must stay LAST — the property the whole scheme rests on, and one that a later
    /// author appending a constant after it would break silently.
    @Test
    void unknownIsStillTheLastConstant() {
        var constants = ProvisioningSource.values();

        assertThat(constants[constants.length - 1]).isEqualTo(ProvisioningSource.UNKNOWN);
    }
}
