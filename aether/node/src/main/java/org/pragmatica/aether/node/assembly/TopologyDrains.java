// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.metrics.DrainId;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// The cluster topology manager's drains, each held by the [DrainId] the registry gave it (#2315).
///
/// The manager asks for a drain with a node id and, when its grace-terminate or withdrawal ends the drain, clears with a node id.
/// A node id names no drain: the entry may by then belong to an operator's newer drain of the same node. This keeps the id of the
/// drain the manager set and clears by it, so the registry refuses the clear when the entry is no longer that drain.
///
/// The held ids are at most the manager's own pending drains: [#request] adds one per node and the manager's clear removes it.
final class TopologyDrains {
    private final DrainCommandRegistry registry;
    private final Map<NodeId, DrainId> held = new ConcurrentHashMap<>();

    private TopologyDrains(DrainCommandRegistry registry) {
        this.registry = registry;
    }

    static TopologyDrains topologyDrains(DrainCommandRegistry registry) {
        return new TopologyDrains(registry);
    }

    /// Registers the drain of `target` and remembers its id.
    @Contract
    void request(NodeId target) {
        registry.requestDrain(target).onPresent(id -> held.put(target, id));
    }

    /// Ends the drain this manager set for `target`; a no-op when it set none, or when the entry is another drain by now.
    @Contract
    void clear(NodeId target) {
        Option.option(held.remove(target)).onPresent(registry::clearDrain);
    }

    int held() {
        return held.size();
    }
}
