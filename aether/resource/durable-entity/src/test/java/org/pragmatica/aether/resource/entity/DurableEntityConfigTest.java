// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReplicationDeclaration;
import org.pragmatica.aether.slice.ReplicationFactorsError;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

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

    /// #1564: `replication_factor` and `confirmation_factor` are OPTIONAL declarations, resolved against the committed
    /// cluster defaults at provisioning ([DurableEntityFactory]); only what the declaration alone decides is checked at
    /// bind. The derived `min(2, replication_factor)` is gone — the confirmation factor is configured.
    @Nested
    class ReplicationFactors {
        @Test
        void durableEntityConfig_carriesTheDeclaredFactors() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, some(5), some(3))
                               .onFailure(DurableEntityConfigTest::failCause)
                               .onSuccess(config -> assertThat(config.replication()).isEqualTo(ReplicationDeclaration.replicationDeclaration(some(5),
                                                                                                                                             some(3))));
        }

        /// #1564: an explicit factor below 3 is allowed (it was refused before); the LOUD warning is raised when
        /// the declaration resolves ([DurableEntityFactoryTest]).
        @Test
        void durableEntityConfig_acceptsDeclaredFactorBelowThree() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, some(1), none())
                               .onFailure(DurableEntityConfigTest::failCause);
        }

        @Test
        void durableEntityConfig_refusesFactorZero() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, some(0), none())
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(cause -> assertRefused(cause, new ReplicationFactorsError.FactorBelowOne(0)));
        }

        @Test
        void durableEntityConfig_refusesConfirmationAboveDeclaredFactor() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, some(2), some(3))
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(cause -> assertRefused(cause, new ReplicationFactorsError.ConfirmationOutOfRange(2, 3)));
        }

        @Test
        void durableEntityConfig_refusesConfirmationZero() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, none(), some(0))
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityProvisioningError.ReplicationRefused.class));
        }

        /// A confirmation factor alone depends on the defaulted factor, so it is checked at provisioning, not here.
        @Test
        void durableEntityConfig_acceptsConfirmationAlone() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, PARTITIONS, none(), some(4))
                               .onFailure(DurableEntityConfigTest::failCause);
        }
    }

    @Nested
    class PartitionCount {
        @Test
        void durableEntityConfig_refusesInvalidPartitionCount_forZero() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, 0, none(), none())
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(DurableEntityConfigTest::assertInvalidPartitionCount);
        }

        @Test
        void durableEntityConfig_refusesInvalidPartitionCount_forNegative() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, -1, none(), none())
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(DurableEntityConfigTest::assertInvalidPartitionCount);
        }

        @Test
        void durableEntityConfig_succeeds_forSinglePartition() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE, 1, none(), none())
                               .onFailure(DurableEntityConfigTest::failCause)
                               .onSuccess(config -> assertThat(config.partitionCount()).isEqualTo(1));
        }
    }

    @Nested
    class Keyspace {
        @Test
        void durableEntityConfig_refuses_forBlankKeyspace() {
            DurableEntityConfig.durableEntityConfig("  ", PARTITIONS, none(), none())
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(DurableEntityConfigTest::assertInvalidKeyspace);
        }

        /// `/` is reserved by two parsed coordinates the keyspace is embedded in: the entity DHT key
        /// (`entity:<keyspace>/<partition>/<key>`) and the per-node registration identity
        /// (`<keyspace>/<nodeId>`). A name containing it would not fail loudly — it would silently shift
        /// both parses: writes fenced against the FLOOR arc as if unowned, and a snapshot restore
        /// reassembling a different keyspace with a phantom host. Refusing at bind time is the only place
        /// the rule can hold.
        @Test
        void durableEntityConfig_refusesKeyspaceContainingSlash() {
            DurableEntityConfig.durableEntityConfig("orders/eu", PARTITIONS, none(), none())
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(DurableEntityConfigTest::assertInvalidKeyspace);
        }

        @Test
        void durableEntityConfig_namesTheRejectedKeyspace_inTheRefusal() {
            DurableEntityConfig.durableEntityConfig("orders/eu", PARTITIONS, none(), none())
                               .onSuccess(DurableEntityConfigTest::failAccepted)
                               .onFailure(cause -> assertThat(cause.message()).contains("orders/eu"));
        }

        @Test
        void durableEntityConfig_appliesDefaults_forKeyspaceOnly() {
            DurableEntityConfig.durableEntityConfig(KEYSPACE)
                               .onFailure(DurableEntityConfigTest::failCause)
                               .onSuccess(DurableEntityConfigTest::assertDefaults);
        }
    }

    /// #1564: a keyspace-only declaration declares no factors — it takes the committed cluster defaults (built-in RF 3,
    /// CF 2) at provisioning.
    private static void assertDefaults(DurableEntityConfig config) {
        assertThat(config.keyspace()).isEqualTo(KEYSPACE);
        assertThat(config.partitionCount()).isPositive();
        assertThat(config.replication()).isEqualTo(ReplicationDeclaration.NONE);
    }

    /// The factory validates with [Result#all], which composes every violation into one cause, so the refusal is
    /// asserted over [Cause#stream] — uniform for a composite and for a single cause.
    private static void assertRefused(Cause cause, ReplicationFactorsError expected) {
        assertThat(cause.stream()).contains(new EntityProvisioningError.ReplicationRefused(expected));
    }

    private static void assertInvalidPartitionCount(Cause cause) {
        assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityProvisioningError.InvalidPartitionCount.class);
    }

    private static void assertInvalidKeyspace(Cause cause) {
        assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityProvisioningError.InvalidKeyspace.class);
    }

    private static void failAccepted(DurableEntityConfig config) {
        fail("declaration must be refused, got " + config);
    }

    private static void failCause(Cause cause) {
        fail(cause.message());
    }
}
