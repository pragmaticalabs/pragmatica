// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #345 I3 — `replication_factor` is HONOURED, and the guarantee it buys is derived from it.
///
/// History, because the direction reversed and the reason matters. Originally the field was accepted and
/// silently ignored: the I0 fixture declared 3 and got exactly one un-replicated process-local copy that
/// died with its node, and nothing in the build could tell. I1 made that loud by REFUSING anything but
/// `1`, which was honest while entity state lived in a process-local `StorageEngine`. I3 moved entity
/// state onto a fenced, fsync-durable, replicated stream partition, so the field became honourable and
/// the refusal became the wrong answer.
///
/// These tests pin the rules to the CONFIG factory rather than to provisioning because the record binder
/// (`ProviderBasedConfigService.bindToClass`) prefers a static `durableEntityConfig(...)` returning
/// `Result` over the canonical constructor — so the rule runs at BIND time and a rejected blueprint
/// fails slice loading with a named cause instead of producing a config object nobody can honour.
class DurableEntityConfigTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITIONS = 8;

    @Nested
    class ReplicationFactor {
        /// The exact value the I0 fixture declared, and the exact value that was first ignored and then
        /// refused. It is now carried through to the backing stream's `replicas`.
        @Test
        void durableEntityConfig_honoursReplicationFactor_forThreeReplicas() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, 3)
                               .onFailure(DurableEntityConfigTest::failCause)
                               .onSuccess(config -> assertThat(config.replicationFactor()).isEqualTo(3));
        }

        @Test
        void durableEntityConfig_honoursReplicationFactor_forSingleReplica() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, 1)
                               .onFailure(DurableEntityConfigTest::failCause)
                               .onSuccess(config -> assertThat(config.replicationFactor()).isEqualTo(1));
        }

        /// Zero copies has no meaning — a partition with no replicas has no owner to write to.
        @Test
        void durableEntityConfig_refusesInvalidReplicationFactor_forZero() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, 0)
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(DurableEntityConfigTest::assertInvalidReplicationFactor);
        }

        @Test
        void durableEntityConfig_refusesInvalidReplicationFactor_forNegative() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, -1)
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(DurableEntityConfigTest::assertInvalidReplicationFactor);
        }

        @Test
        void durableEntityConfig_namesTheRejectedValue_inTheRefusal() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, 0)
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(cause -> assertThat(cause.message()).contains("replication_factor = 0"));
        }
    }

    /// The derivation is the whole difference between "survives owner restart" and "survives owner
    /// death", so it is pinned per value rather than assumed from the formula.
    @Nested
    class MinSyncReplicas {
        /// One copy waits for no peer: the write is acked once it is fsync-durable on the owner alone.
        /// State survives a restart of that node and does NOT survive its death.
        @Test
        void minSyncReplicas_isOne_forSingleReplica() {
            assertMinSyncReplicas(1, 1);
        }

        /// Two or more copies wait for the owner plus exactly one peer — `awaitReplication` blocks on
        /// `minSyncReplicas - 1` distinct non-self acks, so this is one peer ack, not two.
        @Test
        void minSyncReplicas_isTwo_forTwoReplicas() {
            assertMinSyncReplicas(2, 2);
        }

        /// Raising the replica count raises durability and availability, NOT the write barrier: a higher
        /// factor must not silently make every write wait on more peers.
        @Test
        void minSyncReplicas_staysTwo_forThreeReplicas() {
            assertMinSyncReplicas(3, 2);
        }

        @Test
        void minSyncReplicas_staysTwo_forFiveReplicas() {
            assertMinSyncReplicas(5, 2);
        }

        private static void assertMinSyncReplicas(int replicationFactor, int expected) {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, replicationFactor)
                               .onFailure(DurableEntityConfigTest::failCause)
                               .onSuccess(config -> assertThat(config.minSyncReplicas()).isEqualTo(expected));
        }
    }

    @Nested
    class PartitionCount {
        @Test
        void durableEntityConfig_refusesInvalidPartitionCount_forZero() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, 0, 1)
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(DurableEntityConfigTest::assertInvalidPartitionCount);
        }

        @Test
        void durableEntityConfig_refusesInvalidPartitionCount_forNegative() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, -1, 1)
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(DurableEntityConfigTest::assertInvalidPartitionCount);
        }

        @Test
        void durableEntityConfig_succeeds_forSinglePartition() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, 1, 1)
                               .onFailure(DurableEntityConfigTest::failCause)
                               .onSuccess(config -> assertThat(config.partitionCount()).isEqualTo(1));
        }
    }

    @Nested
    class Keyspace {
        @Test
        void durableEntityConfig_refuses_forBlankKeyspace() {
            DurableEntityConfig.durableEntityConfig("  ", PARTITIONS, 1)
                               .onSuccess(DurableEntityConfigTest::failAccepted);
        }

        @Test
        void durableEntityConfig_appliesDefaults_forKeyspaceOnly() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE)
                               .onFailure(DurableEntityConfigTest::failCause)
                               .onSuccess(DurableEntityConfigTest::assertDefaults);
        }
    }

    /// The default must be the SAFE reading of "durable entity": a declaration that names only a keyspace
    /// gets state that survives losing a node. A default of 1 would hand the weaker guarantee to everyone
    /// who did not think about the field — which is the failure mode this whole item exists to close.
    private static void assertDefaults(DurableEntityConfig config) {
        assertThat(config.keyspace()).isEqualTo(KEYSPACE);
        assertThat(config.partitionCount()).isPositive();
        assertThat(config.replicationFactor()).isEqualTo(DurableEntityConfig.DEFAULT_REPLICATION_FACTOR);
        assertThat(config.replicationFactor()).isGreaterThan(1);
        assertThat(config.minSyncReplicas()).isEqualTo(2);
    }

    /// The factory validates with [Result#all], which composes every violation into one cause so a
    /// blueprint breaking several rules reports all of them at once. The refusal is therefore asserted
    /// over [Cause#stream] — uniform for a composite and for a single cause — rather than by matching the
    /// outer instance, which for a composite would be the wrapper, not the domain refusal.
    private static void assertInvalidReplicationFactor(Cause cause) {
        assertThat(cause.stream()).hasAtLeastOneElementOfType(DurableEntityProvisioningError.InvalidReplicationFactor.class);
    }

    private static void assertInvalidPartitionCount(Cause cause) {
        assertThat(cause.stream()).hasAtLeastOneElementOfType(DurableEntityProvisioningError.InvalidPartitionCount.class);
    }

    private static void failAccepted(DurableEntityConfig config) {
        fail("declaration must be refused, got " + config);
    }

    private static void failCause(Cause cause) {
        fail(cause.message());
    }
}
