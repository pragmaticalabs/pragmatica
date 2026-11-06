// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.StreamPartitionManager.ReplicaCatchupSource.CatchupView;
import org.pragmatica.aether.stream.provenance.PartitionFlags;
import org.pragmatica.aether.stream.provenance.PartitionFlags.PartitionFlag;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.segment.StreamFootprint;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2201 NT-25 (spec 5.1 target, bullet 4): every late-bound seam of the manager registers itself when it is bound, and
/// [StreamPartitionManager#unboundSeams] names the ones a wiring left at their default. On a manager that keeps a WAL, a
/// safety-relevant seam left at its default is counted ([StreamPartitionManager#unboundSafetySeamCount], the boot gauge). RED at
/// `15ac4ef4da`: no seam registers.
class StreamSeamRegistryTest {
    @TempDir
    Path walDir;

    private StreamPartitionManager walManager;
    private StreamPartitionManager walLess;

    @BeforeEach
    void setUp() {
        walManager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        walLess = streamPartitionManager(Long.MAX_VALUE);
    }

    @AfterEach
    void tearDown() {
        walManager.close();
        walLess.close();
    }

    @Test
    void unboundSeams_listsASeamTheWiringLeftUnbound_andNotOneItBound() {
        walManager.ownerWriteAdmission((_, _) -> Option.none());

        assertThat(walManager.unboundSeams()).as("left at its default").contains(StreamSeam.OWNER_SERVE_GATE)
                                             .as("bound").doesNotContain(StreamSeam.OWNER_WRITE_ADMISSION);
    }

    /// A PIN: green at `15ac4ef4da` only vacuously (the stub lists nothing); from the seam-registry commit on it pins that every
    /// setter registers its seam.
    @Test
    void unboundSeams_isEmpty_onceEverySeamIsBound() {
        bindAll(walManager);

        assertThat(walManager.unboundSeams()).isEmpty();
        assertThat(walManager.unboundSafetySeamCount()).isZero();
    }

    @Test
    void unboundSafetySeamCount_countsTheSafetyRelevantDefaults_onAManagerWithAWal_only() {
        assertThat(walManager.unboundSafetySeamCount()).as("a WAL manager with nothing bound").isPositive();
        assertThat(walLess.unboundSafetySeamCount()).as("a manager that keeps no WAL is not alarmed").isZero();
    }

    private static final PartitionFlags NO_FLAGS = new PartitionFlags() {
        @Override
        public Promise<PartitionFlag> raise(String stream, int partition, AetherValue.PartitionRecoveryReason reason) {
            return Promise.success(new PartitionFlag(AetherValue.StreamPartitionRecoveryValue.raised(Option.none(), reason), "digest"));
        }

        @Override
        public Option<PartitionFlag> status(String stream, int partition) {
            return Option.none();
        }

        @Override
        public AetherValue.PartitionRecoveryReason local(AetherValue.PartitionRecoveryReasonKind kind, String evidence) {
            return AetherValue.PartitionRecoveryReason.partitionRecoveryReason(kind, Option.some("self"), evidence);
        }
    };

    private static void bindAll(StreamPartitionManager manager) {
        manager.ownerEpochSource((_, _) -> Epoch.ZERO);
        manager.partitionFlags(NO_FLAGS);
        manager.operatorWarnings(OperatorWarningSink.logOnly());
        manager.ownershipRecords((_, _) -> Option.none());
        manager.tieredReader(new SealedTierFixture().reader());
        manager.epochStarts((_, _, _) -> Option.none());
        manager.repairReportBound(TimeSpan.timeSpan(1).seconds());
        manager.exhaustionSink(_ -> {});
        manager.placementRoleSupplier((_, _) -> Role.OWNER);
        manager.clusterSizeSupplier(() -> 1);
        manager.committedConfigSource(_ -> Option.none());
        manager.replicaCatchupSource((_, _) -> new CatchupView(Integer.MAX_VALUE, true));
        manager.ownerReleaseGuard((_, _) -> true);
        manager.ownerWriteAdmission((_, _) -> Option.none());
        manager.ownerServeGate((_, _) -> Result.unitResult());
        manager.ownerBlockSource((_, _) -> Option.none());
        manager.streamFootprint(StreamFootprint.NONE);
        manager.segmentTierPressure(SegmentTierPressure.NONE);
        manager.reshuffleConcurrency(2);
    }
}
