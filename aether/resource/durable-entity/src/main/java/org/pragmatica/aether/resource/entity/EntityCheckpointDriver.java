// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
/// promise, [#recordFailure] counts and logs it, and the loop moves on to the next partition. A THROWN
/// one is not: the only catch sits around the whole iteration in [#tick], so an escaping exception
/// abandons every partition and keyspace ordered after it until the next tick.
public final class EntityCheckpointDriver {
    private static final Logger LOG = LoggerFactory.getLogger(EntityCheckpointDriver.class);

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    private EntityCheckpointDriver() {}

    public static EntityCheckpointDriver entityCheckpointDriver() {
        return new EntityCheckpointDriver();
    }

    private record Registration(String keyspace,
                                int partitionCount,
                                EntityFold fold,
                                EntityLogSubstrate substrate,
                                Map<Integer, Long> checkpointedThrough,
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
    /// iteration, so one throw abandons every registration ordered after it for this tick.
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
    @Contract
    private static void checkpointPartition(Registration registration, int partition) {
        registration.fold()
                    .checkpointCandidate(partition)
                    .filter(candidate -> isAdvancing(registration, partition, candidate))
                    .onPresent(candidate -> saveCheckpoint(registration, partition, candidate));
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
    /// It also makes the "if it has anything new to record" above true: an idle partition re-encoded and
    /// re-wrote its entire fold on every tick.
    ///
    /// Scoped to what THIS node wrote, because that is what it can know locally. Two nodes that both fold
    /// the partition can still overwrite each other's pointers; closing that needs a conditional write in
    /// the substrate and is not attempted here.
    private static boolean isAdvancing(Registration registration,
                                       int partition,
                                       EntityFold.CheckpointCandidate candidate) {
        return candidate.throughOffset() > registration.checkpointedThrough()
                                                       .getOrDefault(partition, -1L);
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
                    .onSuccess(_ -> recordWrite(registration,
                                                partition,
                                                candidate.throughOffset()))
                    .onFailure(cause -> recordFailure(registration,
                                                      partition,
                                                      candidate.throughOffset(),
                                                      cause));
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
