// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.pragmatica.aether.deployment.cluster.DrainSink;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.metrics.DrainId;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// The cluster topology manager's drains, each held by the [DrainId] the registry gave it (#2315).
///
/// The manager asks for a drain with a node id and, when its grace-terminate or withdrawal ends the drain, clears with a node id.
/// A node id names no drain: the entry may by then belong to an operator's newer drain of the same node. This keeps the id of the
/// drain the manager set and clears by it, so the registry refuses the clear when the entry is no longer that drain. The manager
/// asks as [DrainCommandRegistry.Requester#MANAGER], so its entry is its own: the operator's drain of the same process holds another
/// entry under another id, and neither one's clear ends the other.
///
/// The held ids are at most the manager's own pending attempts: [#request] adds one per node, and the end of the attempt removes
/// it. An attempt ends in one of two ways, and each reaches the holder: [#clear] ends the attempt and the command, [#release] ends
/// the attempt and leaves the command to the node's DEAD edge (an eviction). A released command is the registry's to guard: its pin
/// leaves the live slot ([DrainCommandRegistry#release]), so the next attempt for the same node is handed a new id, never the id of
/// a command that is no longer its own.
final class TopologyDrains {
    private final DrainCommandRegistry registry;
    private final Map<NodeId, DrainId> held = new ConcurrentHashMap<>();

    private TopologyDrains(DrainCommandRegistry registry) {
        this.registry = registry;
    }

    static TopologyDrains topologyDrains(DrainCommandRegistry registry) {
        return new TopologyDrains(registry);
    }

    /// The sink the topology manager requests its drains through: adds by [#request], tells the membership state machine through
    /// `fsmCall`, and ends an attempt that leaves its command by [#release]. One place binds the three, so the wiring of the release
    /// is the one the tests drive.
    DrainSink sink(Consumer<NodeId> fsmCall) {
        return DrainSink.synchronous(this::request, fsmCall, this::release);
    }

    /// Registers the drain of `target` and remembers its id.
    @Contract
    void request(NodeId target) {
        registry.requestDrain(target, DrainCommandRegistry.Requester.MANAGER).onPresent(id -> held.put(target, id));
    }

    /// Ends the drain this manager set for `target`; a no-op when it set none, or when the entry is another drain by now.
    @Contract
    void clear(NodeId target) {
        Option.option(held.remove(target)).onPresent(registry::clearDrain);
    }

    /// Ends the attempt for `target` and keeps its command: forgets the id this manager held and releases it in the registry, so the
    /// entry stays until the node's DEAD edge clears it and no later attempt takes it. A no-op when it held none.
    @Contract
    void release(NodeId target) {
        Option.option(held.remove(target)).onPresent(registry::release);
    }

    int held() {
        return held.size();
    }
}
