// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey.StorageStatusKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StorageStatusValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StorageStatusValue.TierStatus;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;

import static org.assertj.core.api.Assertions.assertThat;

/// #634-3 added `walBytes` to `StorageStatusValue`, and the BINARY path the storage-status record
/// FAMILY travels in production had no coverage at all (review catch): `KVStoreSerializerTest`
/// exercises only the TOML projection, which is ephemeral-excluded in production, so the generated
/// `AetherKey_StorageStatusKeyCodec` and `AetherValue_StorageStatusValueCodec` — the codecs consensus
/// replication and snapshot transfer actually use — were both unpinned. Key and value are pinned
/// together because a KV atom is only as replicable as its weaker half.
///
/// This is the #530 failure shape: a type that serialized but did not survive the return trip,
/// unnoticed because nothing read it back. The framing here is positional, so a field written but not
/// read (or read in the wrong order) desynchronises every field after it — which is why each pin is
/// whole-record equality rather than a spot-check on the one field that changed.
class StorageStatusValueCodecTest {
    /// The kvstore codecs LAYERED OVER the framework registry, mirroring production
    /// (`NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs())`): the `tiers` component is a bare
    /// `List`, resolved through the framework's list codec via the supertype fallback — a registry
    /// without that parent fails every list-bearing value with "No codec registered for
    /// ImmutableCollections$..." (measured: 4/4 value round-trips errored before this parent was
    /// added; the precedent test's type had no list fields, which is how the omission survived).
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(),
                                                                  KvstoreCodecsSlice.CODECS);

    private static final String INSTANCE = "streams";
    private static final NodeId NODE = new NodeId("node-1");
    private static final long WAL_BYTES = 987_654_321L;
    private static final long UPDATED_AT = 1_700_000_000_500L;

    @Nested
    class ValueRoundTrip {
        @Test
        void storageStatusValue_roundTrips_withEveryComponent() {
            var original = status(List.of(TierStatus.tierStatus("memory", 1_024L, 4_096L),
                                          TierStatus.tierStatus("disk", 8_192L, 65_536L)),
                                  WAL_BYTES);

            var decoded = roundTrip(original);

            assertThat(decoded).as("every component must survive the replication round-trip")
                               .isEqualTo(original);
            assertThat(decoded.walBytes()).as("the #634-3 field is the one this test was added for")
                                          .isEqualTo(WAL_BYTES);
            assertThat(decoded.tiers()).as("the nested TierStatus list needs its own registered codec")
                                       .containsExactlyElementsOf(original.tiers());
            assertThat(decoded.updatedAt()).isEqualTo(UPDATED_AT);
        }

        /// The arming counterpart. A codec that dropped `walBytes` from BOTH halves would still return
        /// an equal record above, since the field would simply never move — two records differing ONLY
        /// in that field must decode differently, or the equality pin is not watching it.
        @Test
        void storageStatusValue_roundTrip_distinguishesWalBytes() {
            var quiet = status(List.of(TierStatus.tierStatus("memory", 1_024L, 4_096L)), 0L);
            var busy = status(List.of(TierStatus.tierStatus("memory", 1_024L, 4_096L)), WAL_BYTES);

            assertThat(roundTrip(quiet).walBytes()).isZero();
            assertThat(roundTrip(busy).walBytes()).isEqualTo(WAL_BYTES);
            assertThat(roundTrip(quiet)).as("the two must not collapse into one another across the wire")
                                        .isNotEqualTo(roundTrip(busy));
        }

        /// An empty tier list is the shape a storage instance reports before any tier registers, and a
        /// length-prefixed list codec is exactly where an off-by-one strands the reader.
        @Test
        void storageStatusValue_roundTrips_withNoTiers() {
            var original = status(List.of(), WAL_BYTES);

            var decoded = roundTrip(original);

            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.tiers()).isEmpty();
        }

        @Test
        void storageStatusValue_roundTrips_preservingReadinessFlagsIndependently() {
            var readOnly = new StorageStatusValue(INSTANCE, List.of(), "READ_ONLY", true, false, 7L, 1L, WAL_BYTES,
                                                  UPDATED_AT);
            var writeOnly = new StorageStatusValue(INSTANCE, List.of(), "WRITE_ONLY", false, true, 7L, 1L, WAL_BYTES,
                                                   UPDATED_AT);

            assertThat(roundTrip(readOnly).isReadReady()).isTrue();
            assertThat(roundTrip(readOnly).isWriteReady()).isFalse();
            assertThat(roundTrip(writeOnly).isReadReady()).as("the two boolean tags must not be transposed")
                                                          .isFalse();
            assertThat(roundTrip(writeOnly).isWriteReady()).isTrue();
        }
    }

    /// The key half of the same record family. `StorageStatusKey` is the per-node fan-in address —
    /// `isForNode` filters on the `nodeId` component, so it is a field that IS read back, the same
    /// property that made `owningBlueprint` worth pinning in [SchemaVersionCodecTest]. Its codec
    /// inlines `NodeIdCodec` rather than resolving `NodeId` through the registry, so this also covers
    /// the one nested type the value half never exercises.
    @Nested
    class KeyRoundTrip {
        @Test
        void storageStatusKey_roundTrips_withNodeIdAndInstanceName() {
            var original = StorageStatusKey.storageStatusKey(NODE, INSTANCE);

            var decoded = roundTripKey(original);

            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.nodeId()).as("the node component addresses the record — isForNode filters on it")
                                        .isEqualTo(NODE);
            assertThat(decoded.instanceName()).isEqualTo(INSTANCE);
        }

        /// The arming counterpart. A codec that dropped the node component from both halves would
        /// still return an equal key above, and every node's storage status would silently alias onto
        /// one address — keys differing ONLY in the node must stay distinct across the wire.
        @Test
        void storageStatusKey_roundTrip_distinguishesTheNode() {
            var mine = StorageStatusKey.storageStatusKey(NODE, INSTANCE);
            var theirs = StorageStatusKey.storageStatusKey(new NodeId("node-2"), INSTANCE);

            assertThat(roundTripKey(mine)).isNotEqualTo(roundTripKey(theirs));
            assertThat(roundTripKey(theirs).isForNode(NODE))
                .as("a decoded key must not answer to another node")
                .isFalse();
        }
    }

    // === helpers ===

    /// The CANONICAL constructor, not the `storageStatusValue` factory: the factory stamps `updatedAt`
    /// from the wall clock, which would make round-trip equality depend on the time between the two
    /// constructions and leave the field itself untested.
    private static StorageStatusValue status(List<TierStatus> tiers, long walBytes) {
        return new StorageStatusValue(INSTANCE, tiers, "READY", true, true, 7L, 1_700_000_000_000L, walBytes,
                                      UPDATED_AT);
    }

    private static StorageStatusValue roundTrip(StorageStatusValue original) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, original);

        return CODEC.read(buffer);
    }

    private static StorageStatusKey roundTripKey(StorageStatusKey original) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, original);

        return CODEC.read(buffer);
    }
}
