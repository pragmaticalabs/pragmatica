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

/// #345 I1(e) — `replication_factor` is REFUSED rather than accepted and ignored.
///
/// Before this, `DurableEntityConfig` carried the field with a default of 3, `DurableEntityFactory`
/// ignored it entirely, and the I0 fixture declared 3 while getting exactly one un-replicated
/// process-local copy that died with its node. Nothing in the build could tell. These tests pin the
/// refusal to the CONFIG factory rather than to provisioning because the record binder
/// (`ProviderBasedConfigService.bindToClass`) prefers a static `durableEntityConfig(...)` returning
/// `Result` over the canonical constructor — so the rule runs at BIND time and a rejected blueprint
/// fails slice loading with a named cause instead of producing a config object nobody can honour.
class DurableEntityConfigTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITIONS = 8;

    @Nested
    class ReplicationFactor {
        @Test
        void durableEntityConfig_succeeds_forTheSupportedFactor() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, DurableEntityConfig.SUPPORTED_REPLICATION_FACTOR)
                               .onFailure(DurableEntityConfigTest::failCause)
                               .onSuccess(config -> assertThat(config.replicationFactor()).isEqualTo(1));
        }

        /// The exact value the I0 fixture declared, and the exact value that used to be silently ignored.
        @Test
        void durableEntityConfig_refusesReplicationNotSupported_forThreeReplicas() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, 3)
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(DurableEntityConfigTest::assertReplicationNotSupported);
        }

        /// Zero replicas is refused too — the refusal is "not the supported factor", not "too many".
        @Test
        void durableEntityConfig_refusesReplicationNotSupported_forZeroReplicas() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, 0)
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(DurableEntityConfigTest::assertReplicationNotSupported);
        }

        /// The message states both the rejected value and the supported one, so an operator reading a
        /// `DEPLOYMENT_FAILED` event learns what to write instead.
        @Test
        void durableEntityConfig_namesRequestedAndSupported_inTheRefusal() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, 3)
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(cause -> assertThat(cause.message()).contains("replication_factor = 3")
                                                                              .contains("keeps 1 replica"));
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

    private static void assertDefaults(DurableEntityConfig config) {
        assertThat(config.keyspace()).isEqualTo(KEYSPACE);
        assertThat(config.partitionCount()).isPositive();
        assertThat(config.replicationFactor()).isEqualTo(DurableEntityConfig.SUPPORTED_REPLICATION_FACTOR);
    }

    /// The factory validates with [Result#all], which composes every violation into one cause so a
    /// blueprint breaking several rules reports all of them at once. The refusal is therefore asserted
    /// over [Cause#stream] — uniform for a composite and for a single cause — rather than by matching the
    /// outer instance, which for a composite would be the wrapper, not the domain refusal.
    private static void assertReplicationNotSupported(Cause cause) {
        assertThat(cause.stream()).hasAtLeastOneElementOfType(DurableEntityProvisioningError.ReplicationNotSupported.class);
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
