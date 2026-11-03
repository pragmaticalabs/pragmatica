// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.OTHER;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.SELF;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.activeLeader;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.commitLeader;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.seed;

/// #2584 P0, D01: the guarded reset must carry the leader record [TermWriter] checked, never one read afterwards. A newer
/// leader's record that commits between the check and the build would otherwise be embedded, and the applier, which
/// compares the transaction's record with the committed one, would accept the reset from a node that is no longer leader.
class SchemaStalledResetLeaderRaceTest {
    private static final String DATASOURCE = "orders";
    private static final SchemaVersionKey KEY = SchemaVersionKey.schemaVersionKey(DATASOURCE);
    private static final BlueprintId OWNER = BlueprintId.blueprintId("com.example:app:1.0.0").unwrap();
    private static final SchemaVersionValue MIGRATING = SchemaVersionValue.schemaVersionValue(DATASOURCE, 1, "V1", SchemaStatus.MIGRATING, "com.example:app:1.0.0", OWNER, 0);

    @Test
    void reset_newerLeaderCommitsAfterTheCheck_notAcceptedUnderTheNewerRecord() {
        var store = new LeaderFlippingStore();
        var cluster = new WriteFixture.RecordingClusterNode(store);

        commitLeader(store, SELF, 1);
        seed(store, new KVCommand.Put<>(KEY, MIGRATING));

        activeLeader(cluster, store, Set.of(SELF, OTHER));

        assertThat(store.flipped).as("precondition: the newer leader record committed after the check").isTrue();
        assertThat(cluster.batches()).as("no reset carries a leader record other than the one checked")
                                     .noneMatch(SchemaStalledResetLeaderRaceTest::resetUnderAnotherLeader);
        assertThat(store.get(KEY)).as("the deposed leader's reset did not land").isEqualTo(Option.some(MIGRATING));
    }

    private static boolean resetUnderAnotherLeader(List<KVCommand<AetherKey>> batch) {
        return batch.stream()
                    .anyMatch(command -> command instanceof KVCommand.LeaderTransaction<?, ?> transaction
                                         && transaction.key().equals(KEY)
                                         && !transaction.leader().leader().equals(SELF));
    }

    /// The first leader-record read made on behalf of the D01 reset (the [TermWriter] precondition) returns the record as
    /// it was, then `LeaderValue(OTHER, 2)` commits: a newer leader elected between the check and the build.
    private static final class LeaderFlippingStore extends KVStore<AetherKey, AetherValue> {
        volatile boolean flipped;

        LeaderFlippingStore() {
            super(MessageRouter.mutable(), new Serializer() {
                @Override public <T> void write(ByteBuf byteBuf, T object) {}
            }, new Deserializer() {
                @Override public <T> T read(ByteBuf byteBuf) {return null;}
            });
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public synchronized <VV> Option<VV> getTyped(StructuredKey key, Class<VV> valueClass) {
            var read = super.getTyped(key, valueClass);

            if (!flipped && key instanceof LeaderKey && insideTheResetBuilder()) {
                flipped = true;
                process(createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(OTHER, 2)))));
            }

            return read;
        }

        private static boolean insideTheResetBuilder() {
            return StackWalker.getInstance()
                              .walk(frames -> frames.anyMatch(frame -> frame.getMethodName().equals("submitStalledMigrationReset")));
        }
    }
}
