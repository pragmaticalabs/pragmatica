// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #1730 owner-side ISR maintenance. On each tick, for every partition this node owns under a committed ISR, it
/// proposes one guarded ISR change:
///   - **shrink** — an ISR member that has not been caught up to the owner's head for longer than `lagMax` leaves;
///   - **expand** — a registered replica outside the ISR whose confirmed offset reached the high-water mark (the
///     lowest offset every ISR member confirmed, or the owner's head when it is alone) joins: it holds every
///     acknowledged record.
///
/// **Liveness only.** The proposal is a guarded consensus write ([StreamPartitionOwnershipWriter] `guarded`): it
/// applies only while the committed record is exactly the one decided on. Acknowledgement reads the COMMITTED ISR,
/// so nothing here can make an acknowledgement unsafe: an owner that cannot commit — a partitioned minority — keeps
/// its ISR, keeps waiting for the member it cannot reach, and acknowledges nothing. `lagMax` only decides when to
/// ask (Kafka's `replica.lag.time.max.ms`).
public final class IsrMonitor {
    private static final Logger log = LoggerFactory.getLogger(IsrMonitor.class);

    /// The partitions this node currently owns and serves, with their committed record.
    @FunctionalInterface
    public interface OwnedPartitions {
        List<Owned> owned();
    }

    public record Owned(String streamName, int partition, StreamPartitionOwnershipValue record, long head) {}

    private final NodeId self;
    private final OwnedPartitions owned;
    private final ReplicaRegistry registry;
    private final Supplier<Option<LeaderValue>> committedLeader;
    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier;
    private final TimeSpan lagMax;
    private final LongSupplier nanoClock;
    /// When each ISR member was last seen caught up to the owner's head, per partition (`System.nanoTime`).
    private final Map<PartitionKey, Map<NodeId, Long>> lastCaughtUp = new ConcurrentHashMap<>();

    private IsrMonitor(NodeId self,
                       OwnedPartitions owned,
                       ReplicaRegistry registry,
                       Supplier<Option<LeaderValue>> committedLeader,
                       Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                       TimeSpan lagMax,
                       LongSupplier nanoClock) {
        this.self = self;
        this.owned = owned;
        this.registry = registry;
        this.committedLeader = committedLeader;
        this.applier = applier;
        this.lagMax = lagMax;
        this.nanoClock = nanoClock;
    }

    public static IsrMonitor isrMonitor(NodeId self,
                                        OwnedPartitions owned,
                                        ReplicaRegistry registry,
                                        Supplier<Option<LeaderValue>> committedLeader,
                                        Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                        TimeSpan lagMax,
                                        LongSupplier nanoClock) {
        return new IsrMonitor(self, owned, registry, committedLeader, applier, lagMax, nanoClock);
    }

    /// One maintenance pass: propose every ISR change due now, as one batch. Returns the proposals made.
    public List<KVCommand<AetherKey>> tick() {
        var now = nanoClock.getAsLong();
        var proposals = committedLeader.get().map(leader -> proposals(leader, now)).or(List.of());

        if (!proposals.isEmpty()) {
            applier.apply(proposals)
                   .onFailure(cause -> log.debug("ISR change proposal failed, retried next tick: {}",
                                                 cause.message()));
        }

        return proposals;
    }

    private List<KVCommand<AetherKey>> proposals(LeaderValue leader, long now) {
        var commands = new ArrayList<KVCommand<AetherKey>>();

        for (var partition : owned.owned()) {
            if (partition.record().owner().equals(self) && partition.record().isrVersion() > 0) {
                nextIsr(partition, now).map(isr -> IsrOwnershipWriter.guarded(leader,
                                                                              partition.streamName(),
                                                                              partition.partition(),
                                                                              Option.some(partition.record()),
                                                                              partition.record().withIsr(isr)))
                       .onPresent(commands::add);
            }
        }

        return commands;
    }

    /// The ISR this owner should propose for `partition`, or none when the committed one stands.
    Option<List<NodeId>> nextIsr(Owned partition, long now) {
        var key = PartitionKey.partitionKey(partition.streamName(), partition.partition());
        var confirmed = confirmedByNode(partition);
        var record = partition.record();
        var seen = lastCaughtUp.computeIfAbsent(key, _ -> new ConcurrentHashMap<>());
        var peers = record.isr().stream().filter(member -> !member.equals(self)).toList();

        seen.keySet().retainAll(peers);
        peers.forEach(peer -> observe(seen, peer, confirmed.getOrDefault(peer, -1L), partition.head(), now));
        var next = new ArrayList<NodeId>();

        next.add(self);
        peers.stream().filter(peer -> now - seen.get(peer) <= lagMax.nanos()).forEach(next::add);
        var highWater = peers.stream()
                             .mapToLong(peer -> confirmed.getOrDefault(peer, -1L))
                             .min()
                             .orElse(partition.head());

        confirmed.forEach((replica, offset) -> admitIfCaughtUp(next, record, replica, offset, highWater));

        return next.equals(record.isr())
               ? Option.none()
               : Option.some(List.copyOf(next));
    }

    /// A member is caught up while its confirmed offset reaches the owner's head; one first seen now starts its clock.
    @Contract
    private static void observe(Map<NodeId, Long> seen, NodeId peer, long confirmed, long head, long now) {
        if (confirmed >= head) {
            seen.put(peer, now);
        } else {
            seen.putIfAbsent(peer, now);
        }
    }

    @Contract
    private void admitIfCaughtUp(List<NodeId> next,
                                 StreamPartitionOwnershipValue record,
                                 NodeId replica,
                                 long offset,
                                 long highWater) {
        if (!replica.equals(self) && !record.isr().contains(replica) && offset >= highWater) {
            next.add(replica);
        }
    }

    private Map<NodeId, Long> confirmedByNode(Owned partition) {
        var byNode = new ConcurrentHashMap<NodeId, Long>();

        registry.replicasFor(partition.streamName(),
                             partition.partition())
                .forEach(descriptor -> byNode.merge(descriptor.nodeId(),
                                                    descriptor.confirmedOffset(),
                                                    Math::max));

        return byNode;
    }
}
