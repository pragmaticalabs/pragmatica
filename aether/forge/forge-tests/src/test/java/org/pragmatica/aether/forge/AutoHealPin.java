// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.awaitility.core.ConditionTimeoutException;

import static org.awaitility.Awaitility.await;

/// Pins auto-heal OFF for a whole Ember cluster.
///
/// The auto-heal switch is ONE replicated value (`AetherKey.AutoHealStateKey.SINGLETON`), and since #1390
/// its write is a leader transaction: `ClusterTopologyManager.setAutoHealEnabled` on a node that is not the
/// committed leader is refused with "Active committed core authority is required". Calling it on every node,
/// as the pinned tests used to, therefore always failed on the followers. One accepted write through the
/// current leader pins every node, including any future leader, because they all read the same committed
/// key. The pin is confirmed by reading the switch back on EVERY node, not by the write's acknowledgement.
final class AutoHealPin {
    private static final Duration PIN_WINDOW = Duration.ofSeconds(30);
    private static final TimeSpan WRITE_WAIT = TimeSpan.timeSpan(10).seconds();

    private AutoHealPin() {}

    static void pinOff(EmberCluster cluster, String reason) {
        var lastRefusal = new AtomicReference<>("no leader observed");

        try {
            await().atMost(PIN_WINDOW)
                   .pollInterval(Duration.ofMillis(500))
                   .until(() -> attempt(cluster, reason, lastRefusal));
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError("auto-heal pin failed: not every node reads auto-heal OFF within " + PIN_WINDOW
                                     + "; last refusal: " + lastRefusal.get(), timeout);
        }
    }

    private static boolean attempt(EmberCluster cluster, String reason, AtomicReference<String> lastRefusal) {
        if (everyNodeReadsOff(cluster)) {
            return true;
        }

        Option.from(cluster.allNodes()
                           .stream()
                           .filter(AetherNode::isLeader)
                           .findFirst())
              .flatMap(AetherNode::clusterTopologyManager)
              .onPresent(ctm -> ctm.setAutoHealEnabled(false, reason)
                                   .await(WRITE_WAIT)
                                   .onFailure(cause -> lastRefusal.set(cause.message())));

        return everyNodeReadsOff(cluster);
    }

    private static boolean everyNodeReadsOff(EmberCluster cluster) {
        return cluster.allNodes()
                      .stream()
                      .allMatch(node -> node.clusterTopologyManager()
                                            .map(ctm -> !ctm.isAutoHealEnabled())
                                            .or(true));
    }
}
