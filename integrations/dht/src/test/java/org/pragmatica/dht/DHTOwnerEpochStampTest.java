package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Cause;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DurableDeleteCluster.bytes;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3720: how a put is stamped with the owner epoch, and what the writer is told when a replica refuses the stamp.
///
/// 1. One put is stamped from ONE read of the ownership record: a source whose record changes on every read never yields a mixed
///    epoch (the old form read incarnation, term and counter in three calls).
/// 2. A put the writer stamped at the floor (the ownership record not applied yet) and a replica refused for its fence fails
///    [DHTError.OwnershipNotApplied], never [DHTError.WriteIndeterminate]; the rollback of the local accept is the same as for a
///    deposed writer (#1818). A put stamped with a real, older epoch keeps [DHTError.WriteIndeterminate].
class DHTOwnerEpochStampTest {
    private static final DHTConfig RF3 = new DHTConfig(3, 2, 2, timeSpan(2).seconds());
    private static final long[] APPLIED = {0L, 2L, 2L};
    private static final long[] OLDER = {0L, 1L, 1L};
    private static final NodeId WRITER = new NodeId("node-0");

    @Test
    void put_recordChangesOnEveryRead_everyReplicaHoldsOneWholeEpoch() {
        var cluster = new DurableDeleteCluster(3, RF3, _ -> OwnerEpochGate.noOp(), _ -> new ChangingOnEveryRead());
        var key = bytes("one-read");

        cluster.member(WRITER).client().put(key, bytes("v")).await().unwrap();

        assertThat(cluster.replicasOf(key)).isNotEmpty().allSatisfy(id -> {
            var entry = cluster.entryAt(id, key).unwrap();

            assertThat(entry.epochIncarnation()).as("a stamp read once has equal components at %s", id.id()).isPositive();
            assertThat(entry.epochTerm()).as("term of the same read at %s", id.id()).isEqualTo(entry.epochIncarnation());
            assertThat(entry.epochCounter()).as("counter of the same read at %s", id.id()).isEqualTo(entry.epochIncarnation());
        });
    }

    @Test
    void put_floorStampRefusedByAppliedHighWater_failsOwnershipNotApplied_andIsRolledBack() {
        var cluster = new DurableDeleteCluster(3, RF3, DHTOwnerEpochStampTest::writerLagsOthersApplied, _ -> OwnerEpochSource.zero());
        var key = bytes("early-writer");

        var put = cluster.member(WRITER).client().put(key, bytes("v")).await();

        assertThat(put.isFailure()).as("control: the replicas that applied the record refuse the zero epoch").isTrue();
        put.onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.OwnershipNotApplied.class)
                                                .isInstanceOf(Cause.Transient.class)
                                                .isNotInstanceOf(DHTError.WriteIndeterminate.class));
        assertThat(cluster.members()).as("the rollback rule is unchanged: the writer's own accept is taken back, nothing is left anywhere")
                                     .allMatch(member -> !cluster.holdsLive(member.id(), key));
    }

    /// The remove path stamps with the same `freshStamp()` and hands its failure through the same `afterFailedWrite`.
    @Test
    void remove_floorStampRefusedByAppliedHighWater_failsOwnershipNotApplied() {
        var cluster = new DurableDeleteCluster(3, RF3, DHTOwnerEpochStampTest::writerLagsOthersApplied, _ -> OwnerEpochSource.zero());

        var removed = cluster.member(WRITER).client().remove(bytes("early-remove")).await();

        assertThat(removed.isFailure()).as("control: the replicas that applied the record refuse the zero epoch").isTrue();
        removed.onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.OwnershipNotApplied.class)
                                                    .isInstanceOf(Cause.Transient.class));
    }

    @Test
    void put_olderRealEpochRefusedByAppliedHighWater_staysWriteIndeterminate() {
        var cluster = new DurableDeleteCluster(3, RF3, DHTOwnerEpochStampTest::writerLagsOthersApplied, _ -> () -> OwnerEpochSource.OwnerEpoch.ownerEpoch(OLDER[0], OLDER[1], OLDER[2]));
        var key = bytes("deposed-writer");

        var put = cluster.member(WRITER).client().put(key, bytes("v")).await();

        assertThat(put.isFailure()).isTrue();
        put.onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.WriteIndeterminate.class)
                                                .isNotInstanceOf(DHTError.OwnershipNotApplied.class));
        assertThat(cluster.members()).allMatch(member -> !cluster.holdsLive(member.id(), key));
    }

    @Test
    void put_floorStampOnAFreshHighWater_isAccepted() {
        var cluster = new DurableDeleteCluster(3, RF3, _ -> new Gate(new long[]{0L, 0L, 0L}), _ -> OwnerEpochSource.zero());
        var key = bytes("nothing-applied-anywhere");

        assertThat(cluster.member(WRITER).client().put(key, bytes("v")).await().isSuccess()).isTrue();
    }

    /// The writer's own high-water lags (the ownership record is not applied on it), so its OWN store accepts the put; the other
    /// replicas have applied it and refuse. That accept is what the rollback (#1818) must take back.
    private static OwnerEpochGate writerLagsOthersApplied(NodeId id) {
        return WRITER.equals(id)
               ? OwnerEpochGate.noOp()
               : new Gate(APPLIED);
    }

    /// Epoch `(k, k, k)` for the k-th read: a stamp assembled from several reads cannot have equal components.
    private static final class ChangingOnEveryRead implements OwnerEpochSource {
        private final AtomicLong reads = new AtomicLong();

        @Override
        public OwnerEpoch currentEpoch() {
            var read = reads.incrementAndGet();

            return OwnerEpoch.ownerEpoch(read, read, read);
        }
    }

    private static final class Gate implements OwnerEpochGate {
        private final AtomicReference<long[]> highWater;

        Gate(long[] seeded) {
            highWater = new AtomicReference<>(seeded);
        }

        @Override
        public boolean isStale(byte[] key, long incarnation, long term, long counter) {
            return Arrays.compare(highWater.get(), new long[]{incarnation, term, counter}) > 0;
        }

        @Override
        public void advance(byte[] key, long incarnation, long term, long counter) {
            highWater.accumulateAndGet(new long[]{incarnation, term, counter},
                                       (current, presented) -> Arrays.compare(presented, current) > 0 ? presented : current);
        }
    }
}
