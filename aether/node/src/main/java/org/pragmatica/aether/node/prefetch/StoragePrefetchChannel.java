// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.prefetch;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.PrefetchHint;
import org.pragmatica.storage.PrefetchManager;
import org.pragmatica.swim.GossipChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


/// Carries storage prefetch hints between nodes on SWIM's application gossip ([GossipChannel]).
///
/// Once per probe round SWIM asks [#outgoing] what this node has to say; the answer goes to ONE random peer in a
/// datagram of its own. One storage instance speaks per round, in rotation, so a quiet instance never starves a busy
/// one and a datagram never carries two instances' blocks. What arrives is routed to the prefetch manager of the
/// instance it names, and a hint for an instance this node does not have is dropped and counted -- instances are
/// configured per node, so that is routine, never a warning.
///
/// Bounded on both sides: a hint list is trimmed (hottest kept) until its encoding fits the budget SWIM offers, and
/// SWIM drops what it cannot place. Hints are advisory: a lost or dropped one costs a later read of a block, nothing
/// else.
public final class StoragePrefetchChannel implements GossipChannel {
    /// The channel id on SWIM's application gossip.
    public static final int CHANNEL = 1;
    private static final Logger log = LoggerFactory.getLogger(StoragePrefetchChannel.class);

    private final Map<String, PrefetchManager> managers;
    private final List<String> instances;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final AtomicInteger rotation = new AtomicInteger();
    private final AtomicLong droppedUnknownInstance = new AtomicLong();
    private final AtomicLong droppedMalformed = new AtomicLong();
    private final AtomicLong trimmed = new AtomicLong();

    private StoragePrefetchChannel(Map<String, PrefetchManager> managers,
                                   Serializer serializer,
                                   Deserializer deserializer) {
        this.managers = Map.copyOf(managers);
        this.instances = List.copyOf(new TreeMap<>(managers).keySet());
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    /// `managers` maps a storage instance name to its prefetch manager; an instance without prefetch is simply absent.
    public static StoragePrefetchChannel storagePrefetchChannel(Map<String, PrefetchManager> managers,
                                                                Serializer serializer,
                                                                Deserializer deserializer) {
        return new StoragePrefetchChannel(managers, serializer, deserializer);
    }

    /// What the channel has done beyond what each manager counts.
    public record ChannelStats(long droppedUnknownInstance, long droppedMalformed, long trimmed) {
        public static ChannelStats channelStats(long droppedUnknownInstance, long droppedMalformed, long trimmed) {
            return new ChannelStats(droppedUnknownInstance, droppedMalformed, trimmed);
        }
    }

    public ChannelStats stats() {
        return ChannelStats.channelStats(droppedUnknownInstance.get(), droppedMalformed.get(), trimmed.get());
    }

    @Override
    public Option<byte[]> outgoing(int budgetBytes) {
        if (instances.isEmpty()) {
            return Option.none();
        }

        var start = Math.floorMod(rotation.getAndIncrement(), instances.size());

        return Option.from(IntStream.range(0,
                                           instances.size())
                                    .mapToObj(offset -> instances.get((start + offset) % instances.size()))
                                    .map(instance -> fittedGossip(instance, budgetBytes))
                                    .flatMap(Option::stream)
                                    .findFirst());
    }

    /// The instance's collected hints, as many of the hottest as fit `budgetBytes`; none when it has none.
    private Option<byte[]> fittedGossip(String instance, int budgetBytes) {
        var hints = managers.get(instance).collectHints();

        return Option.from(IntStream.iterate(hints.size(),
                                             kept -> kept > 0,
                                             kept -> kept - 1)
                                    .mapToObj(kept -> new Fitted(kept,
                                                                 encoded(instance, hints, kept)))
                                    .filter(fitted -> fitted.bytes().length <= budgetBytes)
                                    .findFirst())
                     .onPresent(fitted -> countTrimmed(fitted.kept(),
                                                       hints.size()))
                     .map(Fitted::bytes);
    }

    private record Fitted(int kept, byte[] bytes) {}

    private byte[] encoded(String instance, List<PrefetchHint> hints, int kept) {
        return serializer.encode(StoragePrefetchGossip.from(instance, hints.subList(0, kept)));
    }

    private void countTrimmed(int kept, int collected) {
        if (kept < collected) {
            trimmed.incrementAndGet();
        }
    }

    @Override
    @Contract
    public void incoming(NodeId from, byte[] payload) {
        Result.<StoragePrefetchGossip> lift(Causes::fromThrowable,
                                            () -> deserializer.decode(payload))
              .onSuccess(this::deliver)
              .onFailure(cause -> dropMalformed(from,
                                                cause.message()));
    }

    private void deliver(StoragePrefetchGossip gossip) {
        option(managers.get(gossip.instance())).onPresent(manager -> manager.processHints(gossip.toPrefetchHints()))
              .onEmpty(() -> dropUnknownInstance(gossip.instance()));
    }

    private void dropUnknownInstance(String instance) {
        droppedUnknownInstance.incrementAndGet();
        log.debug("Prefetch hints for unknown storage instance '{}' dropped", instance);
    }

    private void dropMalformed(NodeId from, String reason) {
        droppedMalformed.incrementAndGet();
        log.debug("Undecodable prefetch hints from {} dropped: {}", from.id(), reason);
    }
}
