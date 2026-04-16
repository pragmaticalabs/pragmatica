// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// One-time reconciliation on governor election.
/// Relies on GovernorCleanup's tracked index (populated from DHT subscription events)
/// to remove entries for nodes not in the alive set.
///
/// On a fresh election the index may be empty — ongoing subscription events will
/// populate it going forward, and the steady-state cleanup handles departures.
@SuppressWarnings({"JBCT-RET-01", "JBCT-STY-05", "JBCT-UTIL-02"}) public sealed interface GovernorReconciliation {
    Logger log = LoggerFactory.getLogger(GovernorReconciliation.class);

    static Promise<Unit> reconcile(Set<NodeId> aliveNodes, GovernorCleanup cleanup, DHTNode dhtNode) {
        log.info("Governor reconciliation: rebuilding index from DHT storage");
        return cleanup.rebuildIndex(dhtNode).flatMap(_ -> reconcileFromIndex(aliveNodes, cleanup));
    }

    static Promise<Unit> reconcile(Set<NodeId> aliveNodes, GovernorCleanup cleanup) {
        return reconcileFromIndex(aliveNodes, cleanup);
    }

    private static Promise<Unit> reconcileFromIndex(Set<NodeId> aliveNodes, GovernorCleanup cleanup) {
        log.info("Governor reconciliation: checking DHT entries against {} alive nodes", aliveNodes.size());
        return cleanup.cleanupDeadNodes(aliveNodes).onSuccess(_ -> log.info("Governor reconciliation complete"));
    }

    record unused() implements GovernorReconciliation{}
}
