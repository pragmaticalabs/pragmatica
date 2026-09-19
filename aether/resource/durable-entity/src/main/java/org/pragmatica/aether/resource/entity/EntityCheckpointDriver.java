// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

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

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    private EntityCheckpointDriver() {}

    public static EntityCheckpointDriver entityCheckpointDriver() {
        return new EntityCheckpointDriver();
    }

    /// `inFlight` holds the partitions whose checkpoint has started and not yet settled (#1269). The tick is
    /// fixed-rate and a save is asynchronous, so without it a save slower than the tick interval was started
    /// again — a second full encode and a second consensus put for the same claim.
    private record Registration(String keyspace,
                                int partitionCount,
                                EntityFold fold,
                                EntityLogSubstrate substrate,
                                Map<Integer, Long> checkpointedThrough,
                                Set<Integer> inFlight,
                                AtomicLong writes,
                                AtomicLong failures) {
        static Registration registration(String keyspace,
                                         int partitionCount,
                                         EntityFold fold,
                                         EntityLogSubstrate substrate) {
            return new Registration(keyspace,
                                    partitionCount,
                                    fold,
                                    substrate,
                                    new ConcurrentHashMap<>(),
                                    ConcurrentHashMap.newKeySet(),
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
    public record KeyspaceCheckpoints(String keyspace,
                                      int partitionCount,
                                      long writes,
                                      long failures,
                                      Map<Integer, Long> checkpointedThrough) {}

    /// Point-in-time view for the management API.
    public CheckpointSnapshot snapshot() {
        return new CheckpointSnapshot(registrations.values()
                                                   .stream()
                                                   .map(EntityCheckpointDriver::keyspaceSnapshot)
                                                   .toList());
    }

    private static KeyspaceCheckpoints keyspaceSnapshot(Registration registration) {
        return new KeyspaceCheckpoints(registration.keyspace(),
                                       registration.partitionCount(),
                                       registration.writes().get(),
                                       registration.failures().get(),
                                       Map.copyOf(registration.checkpointedThrough()));
    }

    /// Register a provisioned keyspace's fold for periodic checkpointing. A second registration of the
    /// same keyspace is ignored, so re-provisioning does not double the checkpoint rate.
    ///
    /// That skip is earned by a single atomic `putIfAbsent` rather than by a scan followed by an add:
    /// two concurrent registrations of one keyspace would both pass a separate scan before either
    /// inserted, so the pair would hold only for SEQUENTIAL re-provisioning. [EntityTimerDriver#register]
    /// carries the same obligation and earns it the same way.
    @Contract
    public void register(String keyspace, int partitionCount, EntityFold fold, EntityLogSubstrate substrate) {
        var existing = registrations.putIfAbsent(keyspace,
                                                 Registration.registration(keyspace, partitionCount, fold, substrate));

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
    @Contract
    public void tick() {
        try {
            registrations.values().forEach(EntityCheckpointDriver::checkpointKeyspace);
        } catch (RuntimeException e) {
            LOG.warn("Entity checkpoint tick failed: {} — retried next tick", e.toString(), e);
        }
    }

    @Contract
    private static void checkpointKeyspace(Registration registration) {
        for (var partition = 0; partition < registration.partitionCount(); partition++) {
            checkpointPartition(registration, partition);
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
    private static void checkpointPartition(Registration registration, int partition) {
        if (!registration.inFlight().add(partition)) {
            return;
        }

        Result.lift(() -> startCheckpoint(registration, partition)).onFailure(cause -> abandonCheckpoint(registration,
                                                                                                         partition,
                                                                                                         cause));
    }

    private static Unit startCheckpoint(Registration registration, int partition) {
        registration.fold()
                    .checkpointCandidate(partition,
                                         lastWritten(registration, partition))
                    .filter(candidate -> isAdvancing(registration, partition, candidate))
                    .onPresent(candidate -> saveCheckpoint(registration, partition, candidate))
                    .onEmpty(() -> settle(registration, partition));

        return unit();
    }

    private static long lastWritten(Registration registration, int partition) {
        return registration.checkpointedThrough()
                           .getOrDefault(partition, -1L);
    }

    @Contract
    private static void abandonCheckpoint(Registration registration, int partition, Cause cause) {
        settle(registration, partition);
        registration.failures().incrementAndGet();
        LOG.warn("Entity checkpoint for '{}' partition {} threw before its save settled: {} — retried next tick",
                 registration.keyspace(),
                 partition,
                 cause.message());
    }

    @Contract
    private static void settle(Registration registration, int partition) {
        registration.inFlight().remove(partition);
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
                                       EntityFold.CheckpointCandidate candidate) {
        registration.substrate()
                    .saveCheckpoint(registration.keyspace(),
                                    partition,
                                    candidate.throughOffset(),
                                    candidate.snapshot())
                    .onResult(result -> recordOutcome(registration, partition, candidate, result));
    }

    /// Record first, THEN clear the in-flight mark: cleared first, a tick landing in between would find the
    /// partition free while `checkpointedThrough` still held the old offset, and save the same claim again.
    @Contract
    private static void recordOutcome(Registration registration,
                                      int partition,
                                      EntityFold.CheckpointCandidate candidate,
                                      Result<Unit> result) {
        result.onSuccess(_ -> recordWrite(registration,
                                          partition,
                                          candidate.throughOffset()))
              .onFailure(cause -> recordFailure(registration,
                                                partition,
                                                candidate.throughOffset(),
                                                cause));
        settle(registration, partition);
    }

    /// The positive signal. Without a success counter, a driver that silently stopped looks exactly like
    /// one that is working — writes and reads keep succeeding either way, and the only symptom is an
    /// entity log that is never reclaimed, appearing hours later as disk growth with nothing pointing
    /// here.
    @Contract
    private static void recordWrite(Registration registration, int partition, long through) {
        registration.checkpointedThrough().put(partition, through);
        registration.writes().incrementAndGet();
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
