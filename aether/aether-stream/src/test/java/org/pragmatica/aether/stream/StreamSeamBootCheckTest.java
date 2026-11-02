// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.utility.warning.OperatorWarningSink;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2201 A-14, the author pre-check of PR-2a: what [StreamSeamRegistryTest] leaves open.
///
/// - The safety-relevant classification is pinned seam by seam, so flipping any one flag is red (the registry test only asks
///   for a positive count).
/// - The count on a WAL manager is exactly the safety-relevant defaults still unbound, not every unbound seam.
/// - The owner-epoch source handed to the fence-enabled factory (the one production uses) counts as bound; the defaulted one
///   does not.
/// - [StreamPartitionManager#reportUnboundSafetySeams] names each safety-relevant default at ERROR on a WAL manager, and is
///   silent on a fully bound one and on a manager that keeps no WAL.
class StreamSeamBootCheckTest {
    private static final Set<StreamSeam> SAFETY_RELEVANT = Set.of(StreamSeam.OWNER_EPOCH_SOURCE,
                                                                  StreamSeam.PARTITION_FLAGS,
                                                                  StreamSeam.OWNERSHIP_RECORDS,
                                                                  StreamSeam.PLACEMENT_ROLE_SUPPLIER,
                                                                  StreamSeam.CATCHUP_SOURCE,
                                                                  StreamSeam.OWNER_RELEASE_GUARD,
                                                                  StreamSeam.OWNER_WRITE_ADMISSION,
                                                                  StreamSeam.OWNER_SERVE_GATE,
                                                                  StreamSeam.STREAM_FOOTPRINT);
    private static final String SENTINEL = "positive control: StreamSeamBootCheckTest capture is attached";

    @TempDir
    Path walDir;

    private LogCapture capture;
    private StreamPartitionManager walManager;

    @BeforeEach
    void setUp() {
        capture = LogCapture.capture(StreamPartitionManager.class, Level.ERROR, false);
        walManager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
    }

    @AfterEach
    void tearDown() {
        walManager.close();
        capture.close();
    }

    @Test
    void safetyRelevant_isExactlyTheNineSeamsWhosePermissiveDefaultIsAlarmed() {
        var classed = Arrays.stream(StreamSeam.values())
                            .filter(StreamSeam::safetyRelevant)
                            .collect(Collectors.toSet());

        assertThat(classed).as("EPOCH_STARTS is conservative by default and is not classed").isEqualTo(SAFETY_RELEVANT);
    }

    @Test
    void unboundSafetySeamCount_countsOnlyTheSafetyRelevantDefaults() {
        assertThat(walManager.unboundSafetySeamCount()).as("nothing bound: the nine safety-relevant seams").isEqualTo(9);

        walManager.operatorWarnings(OperatorWarningSink.logOnly());
        assertThat(walManager.unboundSafetySeamCount()).as("binding a seam that is not safety-relevant changes nothing").isEqualTo(9);

        walManager.ownerServeGate((_, _) -> Result.unitResult());
        assertThat(walManager.unboundSafetySeamCount()).as("binding a safety-relevant seam takes it off the count").isEqualTo(8);
    }

    @Test
    void ownerEpochSource_handedToTheFenceEnabledFactory_countsAsBound_andTheDefaultedOneDoesNot() {
        var production = fenceEnabled((_, _) -> Epoch.ZERO);

        try {
            assertThat(production.unboundSeams()).as("a source the caller passed").doesNotContain(StreamSeam.OWNER_EPOCH_SOURCE);
        } finally {
            production.close();
        }

        assertThat(walManager.unboundSeams()).as("a factory that defaults it").contains(StreamSeam.OWNER_EPOCH_SOURCE);
    }

    @Test
    void reportUnboundSafetySeams_namesEachSafetyRelevantDefault_atError_onAWalManager() {
        walManager.reportUnboundSafetySeams();

        var errors = capture.messages(Level.ERROR);

        assertThat(errors).as("one ERROR line").hasSize(1);
        assertThat(errors.getFirst()).contains("9 safety-relevant seam(s)");
        SAFETY_RELEVANT.forEach(seam -> assertThat(errors.getFirst()).as("names %s", seam).contains(seam.name()));
        assertThat(errors.getFirst()).as("EPOCH_STARTS is not alarmed").doesNotContain(StreamSeam.EPOCH_STARTS.name());
    }

    @Test
    void reportUnboundSafetySeams_isSilent_onAFullyBoundManager_andOnAManagerWithoutAWal() {
        var walLess = streamPartitionManager(Long.MAX_VALUE);

        try {
            bindSafetyRelevant(walManager);
            walManager.reportUnboundSafetySeams();
            walLess.reportUnboundSafetySeams();
            org.slf4j.LoggerFactory.getLogger(StreamPartitionManager.class).error(SENTINEL);

            assertThat(capture.messages(Level.ERROR)).as("only the positive control was logged").containsExactly(SENTINEL);
        } finally {
            walLess.close();
        }
    }

    private static void bindSafetyRelevant(StreamPartitionManager manager) {
        manager.ownerEpochSource((_, _) -> Epoch.ZERO);
        manager.partitionFlags(new SilentFlags());
        manager.ownershipRecords((_, _) -> Option.none());
        manager.placementRoleSupplier((_, _) -> org.pragmatica.aether.stream.replication.ReplicaSetController.Role.OWNER);
        manager.replicaCatchupSource((_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(Integer.MAX_VALUE, true));
        manager.ownerReleaseGuard((_, _) -> true);
        manager.ownerWriteAdmission((_, _) -> Option.none());
        manager.ownerServeGate((_, _) -> Result.unitResult());
        manager.streamFootprint(org.pragmatica.aether.stream.segment.StreamFootprint.NONE);
    }

    private StreamPartitionManager fenceEnabled(StreamOwnerEpochSource source) {
        return streamPartitionManager(Long.MAX_VALUE,
                                      EvictionListener.NOOP,
                                      ReplicationManager.NONE,
                                      new InertClusterNode(),
                                      OwnershipEpochHighWater.ownershipEpochHighWater(new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(),
                                                                                                                          TO_STRING_BYTES,
                                                                                                                          UNUSED_DESERIALIZER)),
                                      source,
                                      Option.some(AppendLog.Opener.directory(walDir)),
                                      LastSealedOffsetSource.none(),
                                      DurableSealedOffsetSource.none());
    }

    private static final class SilentFlags implements org.pragmatica.aether.stream.provenance.PartitionFlags {
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
    }

    private static final Serializer TO_STRING_BYTES = new Serializer() {
        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            byteBuf.writeBytes(String.valueOf(object).getBytes(UTF_8));
        }
    };

    private static final Deserializer UNUSED_DESERIALIZER = new Deserializer() {
        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("unused");
        }
    };

    private static final class InertClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        @Override
        public NodeId self() {
            return NodeId.nodeId("seam-boot-check").unwrap();
        }

        @Override
        public TopologyManager topologyManager() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
