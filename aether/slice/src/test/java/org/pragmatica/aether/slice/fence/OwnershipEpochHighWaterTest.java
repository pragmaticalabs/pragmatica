// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.fence;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class OwnershipEpochHighWaterTest {
    private static final NodeId NODE = NodeId.nodeId("core-1").unwrap();

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private static OwnershipEpochHighWater emptyHighWater() {
        return OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore());
    }

    @Nested
    class Advance {
        @Test
        void advance_newerEpoch_advancesHighWater() {
            var table = emptyHighWater();
            var domain = OwnershipDomain.community("c");

            table.advance(domain, Epoch.epoch(1, 0));
            table.advance(domain, Epoch.epoch(2, 0));

            assertThat(table.highWater(domain)).isEqualTo(Option.some(Epoch.epoch(2, 0)));
        }

        @Test
        void advance_olderEpoch_isIgnored() {
            var table = emptyHighWater();
            var domain = OwnershipDomain.community("c");

            table.advance(domain, Epoch.epoch(5, 0));
            table.advance(domain, Epoch.epoch(2, 0));

            assertThat(table.highWater(domain)).isEqualTo(Option.some(Epoch.epoch(5, 0)));
        }

        @Test
        void advance_equalEpoch_isNoOp() {
            var table = emptyHighWater();
            var domain = OwnershipDomain.community("c");

            table.advance(domain, Epoch.epoch(3, 7));
            table.advance(domain, Epoch.epoch(3, 7));

            assertThat(table.highWater(domain)).isEqualTo(Option.some(Epoch.epoch(3, 7)));
        }

        @Test
        void advance_differentDomains_areIsolated() {
            var table = emptyHighWater();

            table.advance(OwnershipDomain.community("a"), Epoch.epoch(4, 0));

            assertThat(table.highWater(OwnershipDomain.dhtPartition("a"))).isEqualTo(Option.none());
            assertThat(table.highWater(OwnershipDomain.community("b"))).isEqualTo(Option.none());
            assertThat(table.highWater(OwnershipDomain.community("a"))).isEqualTo(Option.some(Epoch.epoch(4, 0)));
        }

        @Test
        void advance_concurrentAdvances_convergeToMax() {
            var table = emptyHighWater();
            var domain = OwnershipDomain.community("c");
            var count = 256;

            IntStream.range(0, count)
                     .parallel()
                     .forEach(i -> table.advance(domain, Epoch.epoch(0, i)));

            assertThat(table.highWater(domain))
                .as("concurrent advances converge to the max regardless of interleaving")
                .isEqualTo(Option.some(Epoch.epoch(0, count - 1)));
        }
    }

    @Nested
    class HighWaterQuery {
        @Test
        void highWater_unknownDomain_returnsNone() {
            var table = emptyHighWater();

            assertThat(table.highWater(OwnershipDomain.community("never"))).isEqualTo(Option.none());
        }
    }

    @Nested
    class IsStale {
        @Test
        void isStale_strictlyOlderEpoch_returnsTrue() {
            var table = emptyHighWater();
            var domain = OwnershipDomain.community("c");

            table.advance(domain, Epoch.epoch(2, 5));

            assertThat(table.isStale(domain, Epoch.epoch(1, 0)))
                .as("older rabiaTerm is stale")
                .isTrue();
            assertThat(table.isStale(domain, Epoch.epoch(1, 99)))
                .as("older rabiaTerm is stale even with a higher localCounter")
                .isTrue();
            assertThat(table.isStale(domain, Epoch.epoch(2, 3)))
                .as("same rabiaTerm but lower localCounter is stale")
                .isTrue();
        }

        @Test
        void isStale_equalEpoch_returnsFalse() {
            var table = emptyHighWater();
            var domain = OwnershipDomain.community("c");

            table.advance(domain, Epoch.epoch(2, 0));

            assertThat(table.isStale(domain, Epoch.epoch(2, 0))).isFalse();
        }

        @Test
        void isStale_newerEpoch_returnsFalse() {
            var table = emptyHighWater();
            var domain = OwnershipDomain.community("c");

            table.advance(domain, Epoch.epoch(2, 0));

            assertThat(table.isStale(domain, Epoch.epoch(3, 0))).isFalse();
        }

        @Test
        void isStale_unknownDomain_returnsFalse() {
            var table = emptyHighWater();

            assertThat(table.isStale(OwnershipDomain.dhtPartition("never"), Epoch.epoch(1, 0)))
                .as("the floor — an unknown domain is never stale")
                .isFalse();
        }
    }

    @Nested
    class Seed {
        private KVStore<AetherKey, AetherValue> store;

        @BeforeEach
        void setUp() {
            store = emptyStore();
        }

        private void apply(AetherKey key, AetherValue value) {
            store.process(store.createBatch(List.of(new Put<>(key, value))));
        }

        @Test
        void seedFromKvStore_committedValues_rebuildsHighWater() {
            var governor = GovernorAnnouncementValue.governorAnnouncementValue(NODE,
                                                                              List.of(NODE),
                                                                              "10.0.0.1:7201",
                                                                              1000L,
                                                                              5L,
                                                                              Epoch.epoch(5, 0),
                                                                              Epoch.ZERO,
                                                                              HlcTimestamp.ZERO,
                                                                              false);
            var ownership = DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(NODE,
                                                                                  "core",
                                                                                  Epoch.epoch(7, 2),
                                                                                  1L,
                                                                                  HlcTimestamp.ZERO);

            apply(GovernorAnnouncementKey.forCommunity("c1"), governor);
            apply(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey("p1"), ownership);

            var table = OwnershipEpochHighWater.ownershipEpochHighWater(store);

            assertThat(table.highWater(OwnershipDomain.community("c1"))).isEqualTo(Option.some(Epoch.epoch(5, 0)));
            assertThat(table.highWater(OwnershipDomain.dhtPartition("p1"))).isEqualTo(Option.some(Epoch.epoch(7, 2)));
        }
    }
}
