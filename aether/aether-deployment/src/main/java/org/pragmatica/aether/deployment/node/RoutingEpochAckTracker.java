// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


public interface RoutingEpochAckTracker {
    @Contract
    void registerExpectation(SliceNodeKey sliceKey, Epoch epoch, Set<NodeId> targetNodes);

    @Contract
    void clear(SliceNodeKey sliceKey);

    Option<SliceNodeKey> observeAck(SliceNodeKey sliceKey, NodeId ackingNode, Epoch ackedEpoch);

    static RoutingEpochAckTracker routingEpochAckTracker() {
        return new RoutingEpochAckTrackerRecord(new ConcurrentHashMap<>());
    }
}

record RoutingEpochAckTrackerRecord(Map<SliceNodeKey, Expectation> pending) implements RoutingEpochAckTracker {
    @Contract
    @Override
    public void registerExpectation(SliceNodeKey sliceKey, Epoch epoch, Set<NodeId> targetNodes) {
        pending.put(sliceKey,
                    new Expectation(epoch, Set.copyOf(targetNodes), ConcurrentHashMap.newKeySet()));
    }

    @Contract
    @Override
    public void clear(SliceNodeKey sliceKey) {
        pending.remove(sliceKey);
    }

    @Override
    public Option<SliceNodeKey> observeAck(SliceNodeKey sliceKey, NodeId ackingNode, Epoch ackedEpoch) {
        var expectation = pending.get(sliceKey);

        if (expectation == null) {
            return Option.none();
        }

        if (!expectation.targets().contains(ackingNode)) {
            return Option.none();
        }

        if (!ackedEpoch.isAtLeast(expectation.epoch())) {
            return Option.none();
        }

        expectation.acks().add(ackingNode);
        if (!hasThreshold(expectation)) {
            return Option.none();
        }

        return consumeIfReady(sliceKey, expectation);
    }

    private static boolean hasThreshold(Expectation expectation) {
        return expectation.acks()
                          .containsAll(expectation.targets());
    }

    private Option<SliceNodeKey> consumeIfReady(SliceNodeKey sliceKey, Expectation expectation) {
        var removed = pending.remove(sliceKey, expectation);

        return removed
               ? Option.some(sliceKey)
               : Option.none();
    }

    record Expectation(Epoch epoch, Set<NodeId> targets, Set<NodeId> acks) {
        Expectation {
            targets = Set.copyOf(targets);
        }

        public Set<NodeId> ackedNodes() {
            return Set.copyOf(acks);
        }

        public Set<NodeId> missingAcks() {
            var missing = new HashSet<>(targets);

            missing.removeAll(acks);

            return Set.copyOf(missing);
        }
    }
}
