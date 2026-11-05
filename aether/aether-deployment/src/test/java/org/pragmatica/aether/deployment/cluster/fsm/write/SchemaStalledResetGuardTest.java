// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.OTHER;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.SELF;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.activeLeader;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.commitLeader;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.freshStore;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.seed;

/// #2584 P0, D01: the new leader's reset of a stalled MIGRATING record to PENDING is a mutation guarded on the record it
/// read, so a migration that completed between the read and the apply keeps its status.
class SchemaStalledResetGuardTest {
    private static final String DATASOURCE = "orders";
    private static final SchemaVersionKey KEY = SchemaVersionKey.schemaVersionKey(DATASOURCE);
    private static final BlueprintId OWNER = BlueprintId.blueprintId("com.example:app:1.0.0").unwrap();
    private static final SchemaVersionValue MIGRATING = SchemaVersionValue.schemaVersionValue(DATASOURCE, 1, "V1", SchemaStatus.MIGRATING, "com.example:app:1.0.0", OWNER, 0);
    private static final SchemaVersionValue COMPLETED = SchemaVersionValue.schemaVersionValue(DATASOURCE, 2, "V2", SchemaStatus.COMPLETED, "com.example:app:1.0.0", OWNER, 0);

    private final KVStore<AetherKey, AetherValue> store = freshStore();
    private final WriteFixture.RecordingClusterNode cluster = new WriteFixture.RecordingClusterNode(store);

    @Test
    void reset_recordMovedSinceRead_refused() {
        stalledMigration();
        cluster.beforeEachBatch(this::completeTheMigrationFirst);

        activeLeader(cluster, store, Set.of(SELF, OTHER));

        assertThat(cluster.batches()).as("precondition: the reset was issued").anyMatch(SchemaStalledResetGuardTest::writesTheRecord);
        assertThat(cluster.results()).as("the applier refused the reset")
                                     .anyMatch(result -> result instanceof KVCommand.TransactionResult transaction && !transaction.accepted());
        assertThat(store.get(KEY)).as("the completed migration keeps its record").isEqualTo(Option.some(COMPLETED));
    }

    /// The positive control: with nothing racing it, the reset still fires and lands, as before.
    @Test
    void reset_recordUnchanged_resetToPending() {
        stalledMigration();

        activeLeader(cluster, store, Set.of(SELF, OTHER));

        assertThat(store.get(KEY).map(value -> ((SchemaVersionValue) value).status())).isEqualTo(Option.some(SchemaStatus.PENDING));
    }

    /// MIGRATING with no lock reads as an expired lock: the migrating node died mid-flight.
    private void stalledMigration() {
        commitLeader(store, SELF, 1);
        seed(store, new KVCommand.Put<>(KEY, MIGRATING));
    }

    private void completeTheMigrationFirst(List<KVCommand<AetherKey>> batch) {
        if (writesTheRecord(batch) && store.get(KEY).equals(Option.some(MIGRATING))) {
            seed(store, new KVCommand.Put<>(KEY, COMPLETED));
        }
    }

    private static boolean writesTheRecord(List<KVCommand<AetherKey>> batch) {
        return batch.stream().anyMatch(command -> command.key().equals(KEY));
    }
}
