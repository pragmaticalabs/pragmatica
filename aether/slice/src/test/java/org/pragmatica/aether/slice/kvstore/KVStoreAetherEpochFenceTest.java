// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end exercise of the generalized epoch fence (#345 piece 1a) through the Rabia applier with
/// the REAL epoch-bearing `AetherValue` variants — `GovernorAnnouncementValue` (`communityEpoch`) and
/// `DhtPartitionOwnershipValue` (`ownerEpoch`). Confirms a deposed governor/owner's strictly-older
/// epoch is rejected, equal-or-newer is accepted (governor reannounce/dissolve and stale-owner
/// takeover legitimately rewrite at the same epoch), and a non-epoch-bearing value is unaffected.
class KVStoreAetherEpochFenceTest {
    private static final NodeId GOV_A = NodeId.nodeId("gov-a").unwrap();
    private static final NodeId GOV_B = NodeId.nodeId("gov-b").unwrap();
    private static final NodeId OWNER_A = NodeId.nodeId("owner-a").unwrap();
    private static final NodeId OWNER_B = NodeId.nodeId("owner-b").unwrap();
    private static final GovernorAnnouncementKey GOV_KEY = GovernorAnnouncementKey.forCommunity("prod:us-east-1");
    private static final DhtPartitionOwnershipKey OWN_KEY = DhtPartitionOwnershipKey.dhtPartitionOwnershipKey("core");

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    private static GovernorAnnouncementValue governor(NodeId id, Epoch epoch) {
        return new GovernorAnnouncementValue(id, 1, List.of(id), "10.0.0.1:7201", 1000L, epoch.rabiaTerm(),
                                             epoch, Epoch.ZERO, HlcTimestamp.ZERO, false);
    }

    private static DhtPartitionOwnershipValue ownership(NodeId id, Epoch epoch, long ownershipTerm) {
        return DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(id, "core", epoch, ownershipTerm, HlcTimestamp.ZERO);
    }

    private KVStore<AetherKey, AetherValue> store;

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private void apply(AetherKey key, AetherValue value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
    }

    private AetherValue stored(AetherKey key) {
        return store.get(key).or((AetherValue) null);
    }

    @Nested
    class GovernorAnnouncementFence {
        @Test
        void firstAnnouncement_applied() {
            apply(GOV_KEY, governor(GOV_A, Epoch.epoch(1, 0)));

            assertThat(stored(GOV_KEY)).isEqualTo(governor(GOV_A, Epoch.epoch(1, 0)));
        }

        @Test
        void newerEpochHandover_applied() {
            apply(GOV_KEY, governor(GOV_A, Epoch.epoch(1, 0)));
            apply(GOV_KEY, governor(GOV_B, Epoch.epoch(2, 0)));

            assertThat(stored(GOV_KEY))
                .as("a real governor handover bumps communityEpoch and must commit")
                .isEqualTo(governor(GOV_B, Epoch.epoch(2, 0)));
        }

        @Test
        void sameEpochReannounce_applied() {
            var first = governor(GOV_A, Epoch.epoch(3, 0));
            apply(GOV_KEY, first);

            // withMembers keeps communityEpoch unchanged — a legitimate periodic reannouncement.
            var reannounced = first.withMembers(List.of(GOV_A, GOV_B), "10.0.0.9:7201");
            apply(GOV_KEY, reannounced);

            assertThat(stored(GOV_KEY))
                .as("periodic reannouncement at the same epoch must NOT be fenced")
                .isEqualTo(reannounced);
        }

        @Test
        void sameEpochDissolve_applied() {
            var first = governor(GOV_A, Epoch.epoch(3, 0));
            apply(GOV_KEY, first);

            // withDissolved keeps communityEpoch unchanged — a legitimate dissolution write.
            var dissolved = first.withDissolved();
            apply(GOV_KEY, dissolved);

            assertThat(stored(GOV_KEY))
                .as("dissolution at the same epoch must NOT be fenced")
                .isEqualTo(dissolved);
        }

        @Test
        void staleGovernorOlderEpoch_rejected() {
            apply(GOV_KEY, governor(GOV_B, Epoch.epoch(5, 0)));
            apply(GOV_KEY, governor(GOV_A, Epoch.epoch(2, 0)));

            assertThat(stored(GOV_KEY))
                .as("a deposed governor's older-epoch announcement must be rejected on every replica")
                .isEqualTo(governor(GOV_B, Epoch.epoch(5, 0)));
        }
    }

    @Nested
    class DhtPartitionOwnershipFence {
        @Test
        void firstOwnership_applied() {
            apply(OWN_KEY, ownership(OWNER_A, Epoch.epoch(1, 0), 1L));

            assertThat(stored(OWN_KEY)).isEqualTo(ownership(OWNER_A, Epoch.epoch(1, 0), 1L));
        }

        @Test
        void newerEpochTransfer_applied() {
            apply(OWN_KEY, ownership(OWNER_A, Epoch.epoch(1, 0), 1L));
            apply(OWN_KEY, ownership(OWNER_B, Epoch.epoch(2, 0), 1L));

            assertThat(stored(OWN_KEY)).isEqualTo(ownership(OWNER_B, Epoch.epoch(2, 0), 1L));
        }

        @Test
        void sameEpochStaleOwnerRewrite_applied() {
            // BootstrapModule.rewriteIfOwnerStale rewrites at the SAME epoch, bumping only ownershipTerm.
            apply(OWN_KEY, ownership(OWNER_A, Epoch.epoch(4, 0), 1L));
            apply(OWN_KEY, ownership(OWNER_B, Epoch.epoch(4, 0), 2L));

            assertThat(stored(OWN_KEY))
                .as("a stale-owner takeover at the same epoch (ownershipTerm+1) must NOT be fenced")
                .isEqualTo(ownership(OWNER_B, Epoch.epoch(4, 0), 2L));
        }

        @Test
        void staleOwnerOlderEpoch_rejected() {
            apply(OWN_KEY, ownership(OWNER_B, Epoch.epoch(5, 0), 3L));
            apply(OWN_KEY, ownership(OWNER_A, Epoch.epoch(2, 0), 99L));

            assertThat(stored(OWN_KEY))
                .as("a deposed owner's older-epoch ownership write must be rejected — even with a higher ownershipTerm")
                .isEqualTo(ownership(OWNER_B, Epoch.epoch(5, 0), 3L));
        }
    }

    @Nested
    class NonEpochBearingUnaffected {
        @Test
        void sliceTargetValue_overwritesFreely_fenceInert() {
            var ab = ArtifactBase.artifactBase("com.example:svc").unwrap();
            var ver = Version.version("1.0.0").unwrap();
            var key = AetherKey.SliceTargetKey.sliceTargetKey(ab);

            apply(key, new SliceTargetValue(ver, 2, 1, Option.none(), "CORE_ONLY", 1000L));
            apply(key, new SliceTargetValue(ver, 5, 3, Option.none(), "CORE_ONLY", 2000L));

            var result = (SliceTargetValue) stored(key);
            assertThat(result.targetInstances())
                .as("a non-EpochBearing AetherValue is not fenced — last write wins")
                .isEqualTo(5);
        }
    }
}
