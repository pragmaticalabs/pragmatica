// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.stream.Collectors;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

/// #915 — every lifecycle await in this module is bounded, and every expiry NAMES its step.
///
/// ## The defect this closes
///
/// `cluster.start().await()`, `cluster.stop().await()` and `cluster.addNode(..).await()` were untimed
/// across this module. An untimed [Promise#await] parks until the promise resolves and consults
/// nothing else — so a lifecycle step that never settles is not merely slow, it is unreachable by any
/// caller-side deadline. #914 records why the framework cannot rescue it either: `await()` never
/// checks the interrupt flag, so JUnit's lifecycle backstop interrupts a thread that simply re-parks.
/// The observable result on CI was a class-level ERROR at ~515 s (the 8-minute lifecycle backstop
/// plus a ~35 s constant prefix) whose entire text was the backstop's own timeout — naming the class,
/// but never the step that stalled nor the state it stalled in.
///
/// ## Why a bound alone is not the fix
///
/// [Promise#await(TimeSpan)] expires into `CoreError.Timeout("Promise is not resolved within
/// specified timeout")`. That string is identical for a cluster that never elected a leader and a
/// node that never bound its port. Bounding without naming converts a slow silent failure into a fast
/// silent one. So every expiry here carries the step's own name, the bound it exceeded, and a
/// snapshot of what the cluster was doing when it gave up.
///
/// ## What the snapshot deliberately omits
///
/// [EmberCluster.NodeStatus#state] is not rendered. On this branch `toNodeStatus` passes the string
/// literal `"healthy"` for every node, so the field is a constant wearing the costume of an
/// observation — a broken node and a formed one report it identically. Printing it into a failure
/// dump would point the next reader away from the fault. `ready` below is read from the node itself
/// ([AetherNode#isReady], the consensus-active sample), which is a real observation. #913 fixes the
/// literal at its source in `EmberCluster`; this class does not wait on that and does not touch it.
///
/// ## Bounds
///
/// Measured on 2026-09-07 in `pragmatica-stream-h` (16-core box, load average ~10 — two sibling trees
/// building, so these are LOADED-box numbers, deliberately: a quiet-box baseline would understate
/// what a CI runner does). `MultiSourceCommunitySmokeTest` green in 42.09 s: cluster start resolved
/// in ~3-8 s, whole formation 18.5 s, `addNode` 1.1 s, `stop` 16.6 s.
///
/// [#LIFECYCLE_BOUND] is 240 s — ~30x the measured start and ~14x the measured stop. It matches the
/// 240 s guard #913 gives `SliceInvocationTest` and the longest existing internal guard in the
/// non-Heavy set, which is what keeps `junit-platform.properties`' stated invariant true: the 8 m
/// lifecycle backstop is double the longest internal guard, so the test's own await is the mechanism
/// that fires and the backstop stays the outer net it was designed to be.
///
/// [#NODE_BOUND] is 60 s — ~55x the measured 1.1 s. A single node join or kill is a far smaller step
/// than standing a cluster up, and giving it the full 240 s would spend four minutes to report a
/// one-second operation that stalled.
final class LifecycleAwait {
    /// Cluster-wide start and stop.
    static final TimeSpan LIFECYCLE_BOUND = TimeSpan.timeSpan(240).seconds();
    /// Single-node join, kill and blackhole.
    static final TimeSpan NODE_BOUND = TimeSpan.timeSpan(60).seconds();

    private LifecycleAwait() {}

    /// Await `promise` under `bound`, returning its value, or fail with a named [AssertionError].
    ///
    /// The failure path covers BOTH a promise that expired and a promise that resolved to a failure:
    /// each one leaves the lifecycle step undone, and each one is worth the same dump. The step name
    /// is supplied by the caller because only the caller knows which of several awaits in the same
    /// method this is.
    static <T> T settled(String step, EmberCluster cluster, TimeSpan bound, Promise<T> promise) {
        return promise.await(bound)
                      .fold(cause -> {
                                throw new AssertionError(step + " did not settle within " + bound
                                                         + ": " + cause.message()
                                                         + "\nCluster state when the wait ended:\n"
                                                         + snapshot(cluster));
                            },
                            value -> value);
    }

    /// The cluster-wide bound, for `start` and `stop`.
    static <T> T settled(String step, EmberCluster cluster, Promise<T> promise) {
        return settled(step, cluster, LIFECYCLE_BOUND, promise);
    }

    /// The single-node bound, for `addNode`, `killNode` and `blackhole`.
    static <T> T nodeSettled(String step, EmberCluster cluster, Promise<T> promise) {
        return settled(step, cluster, NODE_BOUND, promise);
    }

    /// Every value here is read at call time; nothing is defaulted. A cluster with no registered node
    /// says so in words rather than rendering an empty block, because an empty dump and an absent dump
    /// read identically in a CI log.
    static String snapshot(EmberCluster cluster) {
        if (cluster == null) {
            return "  no cluster: the field was never assigned";
        }

        var status = cluster.status();

        if (status.nodes().isEmpty()) {
            return "  no node is registered with this cluster (nodeCount=" + cluster.nodeCount() + ")";
        }

        return "  leader=" + status.leaderId() + " nodeCount=" + cluster.nodeCount() + "\n"
               + status.nodes()
                       .stream()
                       .map(node -> nodeLine(cluster, node))
                       .collect(Collectors.joining("\n"));
    }

    /// `ready` is [AetherNode#isReady] read from the node, or the named absence `unregistered` when
    /// the id in the status answer no longer resolves to a node — which is itself a finding, not a
    /// blank to fill with `false`.
    private static String nodeLine(EmberCluster cluster, EmberCluster.NodeStatus node) {
        return "  " + node.id()
               + " port=" + node.port()
               + " mgmt=" + node.mgmtPort()
               + " leader=" + node.isLeader()
               + " ready=" + cluster.getNode(node.id())
                                    .map(AetherNode::isReady)
                                    .map(String::valueOf)
                                    .or("unregistered");
    }
}
