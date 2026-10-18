package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReason;
import org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReasonKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.provenance.PartitionFlags;
import org.pragmatica.aether.stream.provenance.PartitionFlags.PartitionFlag;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2299: a catch-up page installs the source's owner-epoch slice onto the log the page is written into. A fresh
/// REPLICA whose ring is not materialized yet (paced behind busy `reshuffle_concurrency` slots, or not reconciled)
/// used to install onto no log at all: the page's first append then materialized the log, the records landed
/// unattributed, and the next live attributed append raised `HISTORY_MISSING` for a copy that holds the source's
/// whole history.
class CatchUpInstallsOntoMaterializedLogTest {
    private static final Epoch E1 = Epoch.epoch(0, 1, 0);
    private static final byte[] PAYLOAD = "e".getBytes(UTF_8);
    private static final String BUSY = "busy";
    private static final String FRESH = "fresh";

    @TempDir
    Path walDir;

    private final List<PartitionRecoveryReason> raised = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Role> roles = new ConcurrentHashMap<>();

    @Nested
    class FreshReplica {
        @Test
        void applyAttributed_keepsSourceHistory_whenReshuffleSlotsAreBusy() {
            var manager = managerWithBusySlots();

            catchUpFromZero(manager);

            assertThat(manager.epochHistory(FRESH, 0).map(CatchUpInstallsOntoMaterializedLogTest::epochs)
                              .or(List.of())).containsExactly(E1);
        }

        @Test
        void liveAppendAfterCatchUp_raisesNothing_whenReshuffleSlotsAreBusy() {
            var manager = managerWithBusySlots();

            catchUpFromZero(manager);
            liveAppend(manager);

            assertThat(kinds()).isEmpty();
        }

        @Test
        void liveAppendAfterCatchUp_raisesNothing_whenNothingIsPaced() {
            var manager = managerWithoutSlotPressure();

            catchUpFromZero(manager);
            liveAppend(manager);

            assertThat(kinds()).isEmpty();
        }

        @Test
        void applyAttributed_succeedsWithoutInstalling_whenThisNodeHoldsNoCopy() {
            var manager = managerWithoutSlotPressure();

            roles.put(FRESH, Role.NONE);

            var applied = manager.applyAttributed(FRESH, 0, 0, 0, sourceSlice(), () -> Result.success(1L));

            assertThat(applied.isSuccess()).isTrue();
            assertThat(manager.partitionBuffer(FRESH, 0).isPresent()).isFalse();
        }
    }

    @Nested
    class Control {
        @Test
        void liveAppendAfterCatchUp_raisesNothing_whenLogWasMaterializedFirst() {
            var manager = managerWithoutSlotPressure();

            // control: the log exists before the install, as it does on the slow (SWIM-confirmed) path
            manager.materializePartition(FRESH, 0).onFailure(c -> fail("materialize: " + c.message()));
            catchUpFromZero(manager);
            liveAppend(manager);

            assertThat(kinds()).isEmpty();
        }
    }

    private StreamPartitionManager managerWithoutSlotPressure() {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), LastSealedOffsetSource.none());

        wire(manager);
        roles.put(FRESH, Role.REPLICA);

        return manager;
    }

    private StreamPartitionManager managerWithBusySlots() {
        var manager = managerWithoutSlotPressure();

        roles.put(BUSY, Role.REPLICA);
        manager.materializePartition(BUSY, 0).onFailure(_ -> fail("slot 1"));
        manager.materializePartition(BUSY, 1).onFailure(_ -> fail("slot 2"));
        manager.materializePartition(FRESH, 0).onSuccess(_ -> fail("fresh[0] must be paced"));

        return manager;
    }

    private void wire(StreamPartitionManager manager) {
        manager.partitionFlags(recordingFlags());
        manager.placementRoleSupplier((stream, _) -> roles.getOrDefault(stream, Role.NONE));
        List.of(config(BUSY, 2), config(FRESH, 1)).forEach(c -> manager.onStreamConfigPut(configPut(c)));
    }

    /// The backfill's first catch-up page 0..2 from a source whose history is `[E1 @ 0]`.
    private static void catchUpFromZero(StreamPartitionManager manager) {
        var applied = manager.applyAttributed(FRESH, 0, 0, 2, sourceSlice(), () -> applyThree(manager));

        assertThat(applied.isSuccess()).as("apply %s", applied).isTrue();
    }

    /// The next LIVE replicated record, attributed to the batch epoch.
    private static void liveAppend(StreamPartitionManager manager) {
        manager.appendRecovered(FRESH, 0, 3, PAYLOAD, 1L, E1).onFailure(c -> fail("live: " + c.message()));
    }

    private static List<ProvenanceEntry> sourceSlice() {
        return List.of(ProvenanceEntry.provenanceEntry(E1, Option.none(), 0));
    }

    private static List<Epoch> epochs(List<ProvenanceEntry> history) {
        return history.stream().map(ProvenanceEntry::rank).toList();
    }

    private static Result<Long> applyThree(StreamPartitionManager manager) {
        for (var offset = 0; offset < 3; offset++) {
            var applied = manager.appendCaughtUp(FRESH, 0, offset, PAYLOAD, 1L, E1);

            if (applied.isFailure()) {
                return applied;
            }
        }

        return Result.success(3L);
    }

    private List<PartitionRecoveryReasonKind> kinds() {
        return raised.stream().map(PartitionRecoveryReason::kind).toList();
    }

    private static StreamConfig config(String name, int partitions) {
        return StreamConfig.streamConfig(name, partitions, RetentionPolicy.retentionPolicy(100, 64 * 1024L, 3_600_000L),
                                         "latest", 1_048_576L, ConsistencyMode.EVENTUAL, 1, 1, StreamCompression.NONE, Option.none());
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> configPut(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                  StreamConfigValue.streamConfigValue(config)), Option.none());
    }

    private PartitionFlags recordingFlags() {
        return new PartitionFlags() {
            @Override
            public Promise<PartitionFlag> raise(String stream, int partition, PartitionRecoveryReason reason) {
                raised.add(reason);
                return Promise.success(new PartitionFlag(AetherValue.StreamPartitionRecoveryValue.raised(Option.none(), reason), "d"));
            }

            @Override
            public Option<PartitionFlag> status(String stream, int partition) {
                return Option.none();
            }

            @Override
            public PartitionRecoveryReason local(PartitionRecoveryReasonKind kind, String evidence) {
                return PartitionRecoveryReason.partitionRecoveryReason(kind, Option.some("self"), evidence);
            }
        };
    }
}
