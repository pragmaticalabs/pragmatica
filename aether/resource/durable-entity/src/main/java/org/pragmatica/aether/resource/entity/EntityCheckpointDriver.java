// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Periodically folds each live entity partition to a durable checkpoint (#345 I3).
///
/// ## Why this is not optional
/// Three things depend on a checkpoint existing and advancing:
///
///   1. **Recovery cost.** Without one, rebuilding a partition means replaying its log from offset zero,
///      so recovery time grows without bound as the keyspace ages.
///   2. **Log size.** The retention floor refuses to reclaim anything at or above the checkpoint, so a
///      keyspace with no checkpoint retains its entire history forever. This is deliberate — reclaiming
///      un-folded records would lose committed state — but it makes the checkpoint the ONLY thing that
///      ever bounds an entity log.
///   3. **Failover completeness.** A new owner reaches only the checkpoint plus its own replicated tail.
///      The further behind the checkpoint falls, the more of that tail has to still be in the ring, and
///      the closer the keyspace sits to the gap that makes a fold refuse outright.
///
/// So a driver that silently stops is a slow outage: writes keep working, the log keeps growing, and the
/// first symptom is a failover that refuses. Every failure path below therefore LOGS rather than dying
/// quietly. A REPORTED failure is confined to its own partition — a substrate refusal resolves the
/// promise, [#recordFailure] counts and logs it, and the loop moves on to the next partition. So is a
/// THROWN one while a partition's checkpoint is starting: [#checkpointPartition] lifts it, logs it and
/// counts it, because it must also clear that partition's in-flight mark. A throw anywhere else escapes
/// to the catch around the whole iteration in [#tick], which abandons every partition and keyspace
/// ordered after it until the next tick.
public final class EntityCheckpointDriver {
    private static final Logger LOG = LoggerFactory.getLogger(EntityCheckpointDriver.class);
    /// Node metric carrying this node's LARGEST per-partition checkpoint lag (#1302), evaluated by the
    /// alert threshold path. The per-(keyspace, partition) values are on [#snapshot].
    public static final String CHECKPOINT_LAG_METRIC = "entity.checkpoint.lag.max";
    /// How many ticks an in-flight checkpoint holds its partition before the next tick starts another.
    /// Three: with the node's 30 s interval that is 90 s, two whole intervals of grace for a slow but live
    /// save (a consensus put under load) before a save that never settles is written off.
    static final long IN_FLIGHT_BOUND_TICKS = 3L;

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final AtomicLong ticks = new AtomicLong();
    private final CheckpointLagSink lagSink;
    private final CommittedCheckpoints committed;

    private EntityCheckpointDriver(CheckpointLagSink lagSink, CommittedCheckpoints committed) {
        this.lagSink = lagSink;
        this.committed = committed;
    }

    /// A driver with no metrics binding and no committed-checkpoint source (tests, tooling): it reports
    /// nowhere and measures every owned partition from offset -1.
    public static EntityCheckpointDriver entityCheckpointDriver() {
        return entityCheckpointDriver(EntityCheckpointDriver::discardLag, EntityCheckpointDriver::noneCommitted);
    }

    /// The unbound sink: a driver built without a metrics binding reports nowhere.
    @Contract
    private static void discardLag(long maxCheckpointLag) {}

    private static Option<Long> noneCommitted(String keyspace, int partition) {
        return Option.none();
    }

    /// `lagSink` receives [#maxCheckpointLag] after every tick, whatever the tick's outcome; the node binds
    /// it to its metrics collector under [#CHECKPOINT_LAG_METRIC], where the alert threshold path reads it.
    /// `committed` answers each partition's COMMITTED checkpoint — the pointer in consensus KV that the
    /// retention floor and every recovery use — which is the lag's baseline.
    public static EntityCheckpointDriver entityCheckpointDriver(CheckpointLagSink lagSink,
                                                                CommittedCheckpoints committed) {
        return new EntityCheckpointDriver(lagSink, committed);
    }

    /// The committed checkpoint of `(keyspace, partition)`: its `throughOffset`, or empty when none was
    /// ever committed. Supplied by the node, which reads it from consensus KV; the driver stays independent
    /// of KV the same way it stays independent of the metrics module.
    @FunctionalInterface
    public interface CommittedCheckpoints {
        Option<Long> committedThrough(String keyspace, int partition);
    }

    /// Narrow report seam so the driver stays independent of the metrics module. `void` + `@Contract`
    /// deliberately: a notification sink with no outcome the tick could fold.
    public interface CheckpointLagSink {
        @Contract
        void report(long maxCheckpointLag);
    }

    /// `inFlight` maps each partition whose checkpoint has started and not yet settled to the tick it
    /// started on (#1269). The tick is fixed-rate and a save is asynchronous, so without it a save slower
    /// than the tick interval was started again — a second full encode and a second consensus put for the
    /// same claim. See [#claim] for the bound on how long a mark can hold.
    private record Registration(String keyspace,
                                int partitionCount,
                                EntityFold fold,
                                EntityLogSubstrate substrate,
                                IntPredicate ownsPartition,
                                Map<Integer, Long> checkpointedThrough,
                                Map<Integer, Long> inFlight,
                                AtomicLong writes,
                                AtomicLong failures) {
        static Registration registration(String keyspace,
                                         int partitionCount,
                                         EntityFold fold,
                                         EntityLogSubstrate substrate,
                                         IntPredicate ownsPartition) {
            return new Registration(keyspace,
                                    partitionCount,
                                    fold,
                                    substrate,
                                    ownsPartition,
                                    new ConcurrentHashMap<>(),
                                    new ConcurrentHashMap<>(),
                                    new AtomicLong(),
                                    new AtomicLong());
        }
    }

    /// What this node has actually checkpointed, per keyspace (#345 I3 observability).
    ///
    /// The point of this surface is that a driver which silently STOPPED is otherwise indistinguishable
    /// from one that is working: writes keep succeeding, reads keep succeeding, and the only symptom —
    /// an entity log that is never reclaimed — appears hours later as disk growth with nothing pointing
    /// here. `writes` climbing is the positive signal that was missing; `failures` and
    /// `checkpointedThrough` say which partitions are stuck and how far behind.
    ///
    /// Assembled ON REQUEST from counters the tick already maintains — no hot-path cost.
    public record CheckpointSnapshot(List<KeyspaceCheckpoints> keyspaces) {}

    /// @param checkpointedThrough last offset durably checkpointed per partition; a partition this node
    ///                            has never folded is ABSENT rather than reported as 0, because "nothing
    ///                            to say about it" and "checkpointed through offset 0" are different
    ///                            claims and an operator must be able to tell them apart
    /// @param checkpointLag       per partition this node OWNS, the log head minus the COMMITTED
    ///                            checkpoint in consensus KV (#1302, #1330) — how far a recovery would have to
    ///                            replay. Ownership, not folding, decides membership: a replica's fold is a
    ///                            read-side cache whose checkpoints the cluster refuses, so it has no lag to
    ///                            report, and a released partition leaves the map. The baseline is the
    ///                            committed pointer, never this node's locally recorded save — a fenced save
    ///                            still resolves success (#700), so a local record can claim coverage the
    ///                            cluster never committed. A partition not owned here is ABSENT, not 0
    public record KeyspaceCheckpoints(String keyspace,
                                      int partitionCount,
                                      long writes,
                                      long failures,
                                      Map<Integer, Long> checkpointedThrough,
                                      Map<Integer, Long> checkpointLag) {}

    /// Point-in-time view for the management API.
    public CheckpointSnapshot snapshot() {
        return new CheckpointSnapshot(registrations.values().stream().map(this::keyspaceSnapshot).toList());
    }

    private KeyspaceCheckpoints keyspaceSnapshot(Registration registration) {
        return new KeyspaceCheckpoints(registration.keyspace(),
                                       registration.partitionCount(),
                                       registration.writes().get(),
                                       registration.failures().get(),
                                       Map.copyOf(registration.checkpointedThrough()),
                                       checkpointLag(registration));
    }

    /// This node's largest checkpoint lag over every partition it owns, `0` when it owns none — the
    /// value [#CHECKPOINT_LAG_METRIC] carries.
    public long maxCheckpointLag() {
        return registrations.values()
                            .stream()
                            .flatMap(registration -> checkpointLag(registration).values()
                                                                  .stream())
                            .mapToLong(Long::longValue)
                            .max()
                            .orElse(0L);
    }

    private Map<Integer, Long> checkpointLag(Registration registration) {
        var lags = new HashMap<Integer, Long>();

        for (var partition = 0; partition < registration.partitionCount(); partition++) {
            ownedPartitionLag(registration, partition).onPresent(lagAt(lags, partition));
        }

        return Map.copyOf(lags);
    }

    private static Consumer<Long> lagAt(Map<Integer, Long> lags, int partition) {
        return lag -> lags.put(partition, lag);
    }

    /// Head minus the COMMITTED checkpoint, for a partition this node owns; empty for one it does not, and
    /// for one whose head or committed pointer cannot be read this time — a partition that throws is left
    /// out of this tick's report rather than taking the whole report down with it. Never negative: a local
    /// head behind a checkpoint written from a fuller copy is "nothing to replay", not a negative distance.
    private Option<Long> ownedPartitionLag(Registration registration, int partition) {
        return registration.ownsPartition()
                           .test(partition)
               ? Result.lift(EntityCheckpointDriver::unreadable, () -> partitionLag(registration, partition)).option()
               : Option.none();
    }

    private long partitionLag(Registration registration, int partition) {
        var head = registration.substrate().headOffset(registration.keyspace(), partition);
        var baseline = committed.committedThrough(registration.keyspace(), partition).or(-1L);

        return Math.max(0L, head - baseline);
    }

    private static Cause unreadable(Throwable thrown) {
        return Causes.fromThrowable(thrown);
    }

    /// Register a provisioned keyspace's fold for periodic checkpointing. A second registration of the
    /// same keyspace is ignored, so re-provisioning does not double the checkpoint rate.
    ///
    /// That skip is earned by a single atomic `putIfAbsent` rather than by a scan followed by an add:
    /// two concurrent registrations of one keyspace would both pass a separate scan before either
    /// inserted, so the pair would hold only for SEQUENTIAL re-provisioning. [EntityTimerDriver#register]
    /// carries the same obligation and earns it the same way.
    @Contract
    public void register(String keyspace,
                         int partitionCount,
                         EntityFold fold,
                         EntityLogSubstrate substrate,
                         IntPredicate ownsPartition) {
        var existing = registrations.putIfAbsent(keyspace,
                                                 Registration.registration(keyspace,
                                                                           partitionCount,
                                                                           fold,
                                                                           substrate,
                                                                           ownsPartition));

        if (existing == null) {
            LOG.info("Entity checkpoint: keyspace '{}' registered over {} partition(s)", keyspace, partitionCount);
        }
    }

    /// Drop a keyspace's registration when its entity resource unloads. Idempotent: unregistering an
    /// unknown keyspace is a no-op. Without this, the tick keeps folding through an unloaded entity's
    /// `EntityFold` — an object whose slice classloader is gone — for the life of the node.
    @Contract
    public void unregister(String keyspace) {
        if (registrations.remove(keyspace) != null) {
            LOG.info("Entity checkpoint: keyspace '{}' unregistered", keyspace);
        }
    }

    /// One tick over every registered keyspace and partition.
    ///
    /// The scheduler does NOT need protecting from a throw: [org.pragmatica.lang.utils.SharedScheduler]
    /// drives a `VirtualThreadScheduler`, which runs every body through `runGuarded` and re-enqueues the
    /// task unconditionally — a periodic checkpoint tick is not cancelled by an escaping exception.
    ///
    /// What this catch buys is ATTRIBUTION: the scheduler's own guard logs a generic "scheduled task body
    /// threw" that names neither this driver nor the tick it broke on, and this tick is the only thing
    /// that ever bounds an entity log. What it does NOT buy is isolation — it sits outside the whole
    /// iteration, so one throw abandons every registration ordered after it for this tick. A throw while a
    /// partition's checkpoint is starting never reaches it: [#checkpointPartition] lifts that one.
    /// [EntityTimerDriver#tickOne] puts its catch per keyspace and does buy isolation.
    /// This is an adapter-boundary lift, not business logic swallowing an error.
    ///
    /// The lag is reported AFTER the checkpoint iteration and outside its catch (#1330 M3): a checkpointer
    /// that throws every tick is exactly the stalled checkpointer the lag alert exists for, and a throw that
    /// also skipped the report would freeze the metric at its last value, so the alert could neither fire
    /// nor clear.
    @Contract
    public void tick() {
        checkpointAll();
        reportLag();
    }

    @Contract
    private void checkpointAll() {
        try {
            var tick = ticks.incrementAndGet();

            registrations.values().forEach(registration -> checkpointKeyspace(registration, tick));
        } catch (RuntimeException e) {
            LOG.warn("Entity checkpoint tick failed: {} — retried next tick", e.toString(), e);
        }
    }

    /// Guarded on its own, and per partition inside [#ownedPartitionLag], so a failure to read one
    /// partition's head never silences the rest. A failure here is WARN-logged: the metric then holds its
    /// last value for one tick, which the log names.
    @Contract
    private void reportLag() {
        try {
            lagSink.report(maxCheckpointLag());
        } catch (RuntimeException e) {
            LOG.warn("Entity checkpoint lag report failed: {} — the lag metric keeps its last value until the next tick",
                     e.toString(),
                     e);
        }
    }

    @Contract
    private static void checkpointKeyspace(Registration registration, long tick) {
        for (var partition = 0; partition < registration.partitionCount(); partition++) {
            checkpointPartition(registration, partition, tick);
        }
    }

    /// Checkpoint one partition, if it has anything new to record.
    ///
    /// Only partitions this node has actually FOLDED are checkpointed: [EntityFold#checkpointCandidate]
    /// answers ABSENT for a partition never rebuilt here, which correctly means "this node has nothing to
    /// say about it". A node that does not own a partition therefore writes no checkpoint for it, rather
    /// than publishing an empty fold over the real owner's work.
    ///
    /// The offset and the snapshot arrive TOGETHER, from one captured fold, and that is load-bearing rather
    /// than tidy: read as two calls, a rebuild publishing in between files one fold's contents under
    /// another fold's offset, and the direction that loses data — a high claim over contents folded lower —
    /// is reachable. See [EntityFold#checkpointCandidate].
    ///
    /// A partition whose previous checkpoint is still in flight is skipped, and the fold is asked for a
    /// candidate only ABOVE the last offset this node wrote, so an idle partition is never copied or encoded
    /// (#1269). Once a partition is marked in flight, EVERY exit clears the mark — no candidate, the save
    /// settling either way, or a throw from the fold or the substrate. A mark left behind would stop that
    /// partition's checkpoints for the life of the node, which is exactly the silent stop this driver
    /// exists to prevent.
    @Contract
    private static void checkpointPartition(Registration registration, int partition, long tick) {
        if (!claim(registration, partition, tick)) {
            return;
        }

        Result.lift(() -> startCheckpoint(registration, partition, tick)).onFailure(cause -> abandonCheckpoint(registration,
                                                                                                               partition,
                                                                                                               tick,
                                                                                                               cause));
    }

    /// Mark `partition` in flight as of `tick`, or report that an earlier checkpoint still holds it.
    ///
    /// The mark is BOUNDED: one older than [#IN_FLIGHT_BOUND_TICKS] ticks is taken over, with a WARN naming
    /// the partition. Without the bound a save whose promise never settles would hold the partition's
    /// checkpoints for the life of the node — the silent stop this driver exists to prevent. Taking over
    /// is safe even if the abandoned save lands later: checkpoint writes are `MonotonicFenced` (#700), so
    /// a late, lower claim is refused by the consensus applier, and [#recordWrite] keeps the local record
    /// at its maximum. Every clear is conditional on the tick the mark was taken on, so a late settle of
    /// the abandoned save cannot clear the mark of the checkpoint that replaced it.
    private static boolean claim(Registration registration, int partition, long tick) {
        var held = registration.inFlight().putIfAbsent(partition, tick);

        if (held == null) {
            return true;
        }

        if (tick - held < IN_FLIGHT_BOUND_TICKS) {
            return false;
        }

        var takenOver = registration.inFlight().replace(partition, held, tick);

        if (takenOver) {
            LOG.warn("Entity checkpoint for '{}' partition {} has not settled in {} ticks — starting another;"
                    + " the abandoned save may still land, which is harmless because checkpoint writes are"
                    + " monotonic-fenced",
                     registration.keyspace(),
                     partition,
                     tick - held);
        }

        return takenOver;
    }

    private static Unit startCheckpoint(Registration registration, int partition, long tick) {
        registration.fold()
                    .checkpointCandidate(partition,
                                         lastWritten(registration, partition))
                    .filter(candidate -> isAdvancing(registration, partition, candidate))
                    .onPresent(candidate -> saveCheckpoint(registration, partition, candidate, tick))
                    .onEmpty(() -> settle(registration, partition, tick));

        return unit();
    }

    private static long lastWritten(Registration registration, int partition) {
        return registration.checkpointedThrough()
                           .getOrDefault(partition, -1L);
    }

    @Contract
    private static void abandonCheckpoint(Registration registration, int partition, long tick, Cause cause) {
        settle(registration, partition, tick);
        registration.failures().incrementAndGet();
        LOG.warn("Entity checkpoint for '{}' partition {} threw before its save settled: {} — retried next tick",
                 registration.keyspace(),
                 partition,
                 cause.message());
    }

    @Contract
    private static void settle(Registration registration, int partition, long tick) {
        registration.inFlight().remove(partition, tick);
    }

    /// A checkpoint is written only when it ADVANCES the last one this node wrote, and that is a safety
    /// guard rather than an optimisation.
    ///
    /// `saveCheckpoint` publishes its pointer with a blind put — no compare-and-set, no running max — so a
    /// LOWER offset written second simply replaces a higher one. A fold's published coverage can go
    /// backwards (see [EntityFold#publish]: a rebuild replays only to the head it read when it started,
    /// while the outgoing fold keeps advancing on the append path), and without this guard the resulting
    /// honest-but-lower checkpoint would overwrite a higher one whose retention floor had ALREADY let the
    /// log below it be reclaimed. The records between the two offsets would then exist nowhere, and every
    /// later rebuild would refuse with the gap failure, permanently.
    ///
    /// On its own it stopped an idle partition re-WRITING its fold on every tick, but not re-ENCODING it:
    /// the candidate was built before this ran. The floor passed to [EntityFold#checkpointCandidate] now
    /// stops the encode (#1269); this check stays as the guard on the write.
    ///
    /// Scoped to what THIS node wrote, because that is what it can know locally. The cross-node half is
    /// closed in the SUBSTRATE (#700): the checkpoint value is `MonotonicFenced`, so the consensus
    /// applier refuses a Put that would lower the committed offset — two nodes either side of a
    /// handover cannot regress each other's claims. One consequence worth stating: a refused write
    /// still resolves the apply successfully (fenced rejections are silent), so [#recordWrite] may
    /// record a locally-lower value than what is committed — harmless, since this local map only
    /// widens the next [#isAdvancing] admission and the applier holds the real line.
    private static boolean isAdvancing(Registration registration,
                                       int partition,
                                       EntityFold.CheckpointCandidate candidate) {
        return candidate.throughOffset() > lastWritten(registration, partition);
    }

    @Contract
    private static void saveCheckpoint(Registration registration,
                                       int partition,
                                       EntityFold.CheckpointCandidate candidate,
                                       long tick) {
        registration.substrate()
                    .saveCheckpoint(registration.keyspace(),
                                    partition,
                                    candidate.throughOffset(),
                                    candidate.snapshot())
                    .withResult(result -> recordOutcome(registration, partition, candidate, tick, result));
    }

    /// Attached with `withResult`, so it runs inline on the thread that settles the save, as a step of the
    /// save's chain rather than an observer dispatched to another thread (#1269 review): by the time the
    /// save's promise has settled, its outcome is recorded and its mark cleared.
    ///
    /// Record first, THEN clear the in-flight mark: cleared first, a tick landing in between would find the
    /// partition free while `checkpointedThrough` still held the old offset, and save the same claim again.
    /// No test pins that order; getting it wrong costs one duplicate save at an equal offset, which the
    /// checkpoint fence (#700) accepts harmlessly.
    @Contract
    private static void recordOutcome(Registration registration,
                                      int partition,
                                      EntityFold.CheckpointCandidate candidate,
                                      long tick,
                                      Result<Unit> result) {
        result.onSuccess(_ -> recordWrite(registration,
                                          partition,
                                          candidate.throughOffset()))
              .onFailure(cause -> recordFailure(registration,
                                                partition,
                                                candidate.throughOffset(),
                                                cause));
        settle(registration, partition, tick);
    }

    /// The positive signal. Without a success counter, a driver that silently stopped looks exactly like
    /// one that is working — writes and reads keep succeeding either way, and the only symptom is an
    /// entity log that is never reclaimed, appearing hours later as disk growth with nothing pointing
    /// here.
    ///
    /// Kept at its MAXIMUM rather than overwritten: once [#claim] can take over a stalled checkpoint, the
    /// abandoned save may settle after its replacement, and its lower offset must not pull the record back.
    ///
    /// For the same reason `writes` counts only a save that reached or raised the record. A save that
    /// lands BELOW what this node already recorded is one the checkpoint fence refuses (#700), and a
    /// refused Put still resolves successfully, so counting it would report a write that did not happen.
    /// What stays uncountable here is a claim refused because ANOTHER node committed higher — this node
    /// cannot see that, the same detection limit #700 records.
    @Contract
    private static void recordWrite(Registration registration, int partition, long through) {
        if (registration.checkpointedThrough().merge(partition, through, Math::max) == through) {
            registration.writes().incrementAndGet();
        }
    }

    @Contract
    private static void recordFailure(Registration registration, int partition, long through, Cause cause) {
        registration.failures().incrementAndGet();
        LOG.warn("Entity checkpoint for '{}' partition {} through offset {} failed: {}"
                + " — retried next tick; the log cannot be reclaimed below the last successful checkpoint"
                + " until it succeeds",
                 registration.keyspace(),
                 partition,
                 through,
                 cause.message());
    }
}
