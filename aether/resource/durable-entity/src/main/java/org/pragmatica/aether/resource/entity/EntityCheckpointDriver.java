// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
/// quietly, and the driver never lets one partition's failure stop the others.
public final class EntityCheckpointDriver {
    private static final Logger LOG = LoggerFactory.getLogger(EntityCheckpointDriver.class);

    private final List<Registration> registrations = new CopyOnWriteArrayList<>();

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
        return new CheckpointSnapshot(registrations.stream().map(EntityCheckpointDriver::keyspaceSnapshot).toList());
    }

    private static KeyspaceCheckpoints keyspaceSnapshot(Registration registration) {
        return new KeyspaceCheckpoints(registration.keyspace(),
                                       registration.partitionCount(),
                                       registration.writes().get(),
                                       registration.failures().get(),
                                       Map.copyOf(registration.checkpointedThrough()));
    }

    /// Register a provisioned keyspace's fold for periodic checkpointing. Idempotent per keyspace: a
    /// second registration of the same keyspace is ignored, so re-provisioning does not double the
    /// checkpoint rate.
    @Contract
    public void register(String keyspace, int partitionCount, EntityFold fold, EntityLogSubstrate substrate) {
        if (registrations.stream().anyMatch(registration -> registration.keyspace()
                                                                        .equals(keyspace))) {
            return;
        }

        registrations.add(Registration.registration(keyspace, partitionCount, fold, substrate));
        LOG.info("Entity checkpoint: keyspace '{}' registered over {} partition(s)", keyspace, partitionCount);
    }

    /// One tick over every registered keyspace and partition.
    ///
    /// `ScheduledExecutorService` CANCELS a periodic task whose run throws, silently and permanently. This
    /// tick is the only thing that ever bounds an entity log, so a single escaping exception would stop
    /// every keyspace's checkpointing for the life of the node — and the symptom would be unbounded disk
    /// growth appearing hours later, with nothing pointing here. Catching keeps the driver alive and names
    /// the failure. This is an adapter-boundary lift, not business logic swallowing an error.
    @Contract
    public void tick() {
        try {
            registrations.forEach(EntityCheckpointDriver::checkpointKeyspace);
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
    /// Only partitions this node has actually FOLDED are checkpointed: `checkpointableThrough` returns
    /// `-1` for a partition never rebuilt here, which correctly means "this node has nothing to say about
    /// it". A node that does not own a partition therefore writes no checkpoint for it, rather than
    /// publishing an empty fold over the real owner's work.
    @Contract
    private static void checkpointPartition(Registration registration, int partition) {
        var through = registration.fold().checkpointableThrough(partition);

        if (through < 0) {
            return;
        }

        registration.substrate()
                    .saveCheckpoint(registration.keyspace(),
                                    partition,
                                    through,
                                    registration.fold().snapshot(partition))
                    .onSuccess(_ -> recordWrite(registration, partition, through))
                    .onFailure(cause -> recordFailure(registration, partition, through, cause));
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
