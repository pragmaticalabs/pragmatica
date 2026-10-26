// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.LogLevelKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.ReplicaFilter;
import org.pragmatica.cluster.state.kvstore.CommitRing;
import org.pragmatica.cluster.state.kvstore.CommitRing.CloseReason;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// The production incarnation reader of the commit ring (W-OBS W2, section 2.1 R-02): the tag is the committed
/// `(incarnation, incarnationId)`, `NONE` while nothing is committed, and a restore or re-genesis in one batch clears the ring under the new tag.
class ReplicaIncarnationSourceTest {
    private static final Serializer NO_BYTES = new Serializer() {
        @Override
        public <T> void write(ByteBuf buffer, T value) {}
    };

    private static final Deserializer NOTHING = new Deserializer() {
        @Override
        public <T> T read(ByteBuf buffer) {
            return null;
        }
    };

    private static final LogLevelKey LOG_KEY = new LogLevelKey("org.example");

    private static KVStore<AetherKey, AetherValue> store() {
        return new KVStore<>(MessageRouter.mutable(),
                             NO_BYTES,
                             NOTHING,
                             ReplicaFilter.replicaFilter(),
                             ReplicaIncarnationSource.replicaIncarnationSource(),
                             CommitRing.DEFAULT_BUDGET_BYTES,
                             org.pragmatica.cluster.state.kvstore.ValueSizeTable.valueSizeTable(),
                             Runnable::run);
    }

    @SafeVarargs
    private static void commit(KVStore<AetherKey, AetherValue> store, long slot, KVCommand<AetherKey>... commands) {
        store.processCommitted(store.createBatch(List.of(commands)), slot + 1);
        store.onPhaseCompleted(slot + 1);
    }

    private static KVCommand<AetherKey> genesis(String lineage, String id) {
        return new KVCommand.Put<>(ClusterIncarnationKey.SINGLETON, ClusterIncarnationValue.genesis(lineage, id));
    }

    private static KVCommand<AetherKey> log(String level) {
        return new KVCommand.Put<>(LOG_KEY, AetherValue.LogLevelValue.logLevelValue("org.example", level));
    }

    /// Mutation: answer a constant tag, or read a different key.
    @Test
    void read_isNone_whileNothingIsCommitted_andTheCommittedPairOtherwise() {
        var source = ReplicaIncarnationSource.replicaIncarnationSource();

        assertThat(source.read(_ -> null)).as("absent").isEqualTo(IncarnationTag.NONE);
        assertThat(source.read(_ -> "not an incarnation")).as("a value of another type under the key is no incarnation").isEqualTo(IncarnationTag.NONE);
        assertThat(source.read(_ -> ClusterIncarnationValue.clusterIncarnationValue("lineage", 4L, "id-4"))).isEqualTo(IncarnationTag.incarnationTag(4L, "id-4"));
    }

    /// Mutation: `isIncarnationKey` answers true for every key (every write clears the ring) or false for all (a restore never bumps).
    @Test
    void isIncarnationKey_isTrueOnlyForTheClusterIncarnationKey() {
        var source = ReplicaIncarnationSource.replicaIncarnationSource();

        assertThat(source.isIncarnationKey(ClusterIncarnationKey.SINGLETON)).isTrue();
        assertThat(source.isIncarnationKey(LOG_KEY)).isFalse();
        assertThat(source.isIncarnationKey(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE)).isFalse();
    }

    /// A ring before genesis records nothing and serves nothing (NONE matches nothing). Mutation: record under a NONE tag.
    @Test
    void beforeGenesis_theRingRecordsNothing() {
        var store = store();

        commit(store, 0, log("INFO"));

        assertThat(store.replicaRing().tag().isNone()).isTrue();
        assertThat(store.replicaRing().entries()).isEmpty();
    }

    /// R-02. A restore is `Remove` then `Put` of the incarnation key in ONE batch: the ring is cleared, the bump batch is not delivered and
    /// the live subscribers are closed `INCARNATION_MISMATCH`. A re-minted incarnation 1 with another id is a different life: tags compare by
    /// equality of both parts. Mutation: compare the tag by number only (the re-genesis below is not a bump: red).
    @Test
    void aRegenesisInOneBatch_clearsTheRing_closesSubscribers_andRetagsEvenAtTheSameNumber() {
        var store = store();
        var closed = new AtomicReference<CloseReason>();

        commit(store, 0, genesis("lineage", "id-A"));
        commit(store, 1, log("INFO"));
        store.replicaRing().subscribeLive(IncarnationTag.NONE, CommitRing.subscription(closed::set));
        commit(store, 2,
               new KVCommand.Remove<>(ClusterIncarnationKey.SINGLETON),
               genesis("lineage", "id-B"));

        assertThat(store.replicaRing().tag()).as("incarnation 1 again, under another id").isEqualTo(IncarnationTag.incarnationTag(1L, "id-B"));
        assertThat(store.replicaRing().entries()).as("the bump batch is not delivered and the old entries are gone").isEmpty();
        assertThat(closed.get()).isEqualTo(CloseReason.INCARNATION_MISMATCH);

        commit(store, 3, log("DEBUG"));

        assertThat(store.replicaRing().entries()).extracting(entry -> entry.slot()).as("recording resumes under the new tag").containsExactly(3L);
    }

    /// A higher incarnation number is a bump too. Mutation: ignore a Put of the incarnation key that is not preceded by a Remove.
    @Test
    void aHigherIncarnation_isABump() {
        var store = store();
        var tags = new ArrayList<IncarnationTag>();

        commit(store, 0, genesis("lineage", "id-A"));
        tags.add(store.replicaRing().tag());
        commit(store, 1, new KVCommand.Put<>(ClusterIncarnationKey.SINGLETON, ClusterIncarnationValue.genesis("lineage", "id-A").next("id-B")));
        tags.add(store.replicaRing().tag());

        assertThat(tags).containsExactly(IncarnationTag.incarnationTag(1L, "id-A"), IncarnationTag.incarnationTag(2L, "id-B"));
    }
}
