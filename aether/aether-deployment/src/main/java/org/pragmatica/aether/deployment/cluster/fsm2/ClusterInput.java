// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Everything that can enter the cluster FSM's mailbox (spec 1.2). An input is never handled on the delivering
/// thread; it is delivered to [ClusterFsm#step] by the single consumer of the mailbox (R-3.1.1).
///
/// KV notifications are two inputs, [KvPut] and [KvRemove], dispatched by the key's type: that is CI-2 to CI-12
/// (blueprint, target, routing, observed, desired, directive, schema, community, announcement, placement,
/// departure record, judgment, apply, outcome). The leader key has its own input, [LeaderObserved] (CI-16).
public sealed interface ClusterInput {
    /// CI-1: this node became the leader.
    record Activate() implements ClusterInput {}

    /// CI-1: this node stopped being the leader (or is stopping).
    record Deactivate() implements ClusterInput {}

    /// The first mailbox item after an activation (R-3.1.6, C1): the committed mirror, read once.
    record Rebuild(Snapshot snapshot) implements ClusterInput {}

    /// CI-2..CI-12: a committed put, as notified.
    record KvPut(AetherKey key, AetherValue value) implements ClusterInput {}

    /// CI-2..CI-12: a committed remove, as notified.
    record KvRemove(AetherKey key) implements ClusterInput {}

    /// CI-16: the committed `LeaderValue`.
    record LeaderObserved(LeaderValue value) implements ClusterInput {}

    /// CI-8: membership admitted a core (`NodeJoined`).
    record NodeJoined(NodeId node, Admission admission) implements ClusterInput {}

    /// CI-7: membership admitted a worker (`WorkerJoinDecision`).
    record WorkerJoin(NodeId node, Admission admission) implements ClusterInput {}

    /// CI-8: a core left (`NodeRemoved`).
    record NodeRemoved(NodeId node) implements ClusterInput {}

    /// CI-9: a worker left (`WorkerLeaveDecision`).
    record WorkerLeave(NodeId node) implements ClusterInput {}

    /// CI-15b: the seam reports a token for `node` that it reported as unknown before.
    record ProcessKnown(NodeId node, long token) implements ClusterInput {}

    /// CI-10: a DRAINING pong report.
    record NodeDraining(NodeId node) implements ClusterInput {}

    /// CI-15, CI-15c: a fresh sample of the read seams; delivered on every tick and whenever a seam changes.
    record SeamsRefreshed(Seams seams) implements ClusterInput {}

    /// The internal coalesced reconcile pass (R-3.1.5, R-3.3.8). Queued by the FSM itself through
    /// `ClusterEffect.Enqueue`.
    record Reconcile() implements ClusterInput {}

    /// The end of a mailbox drain (R-5.7.4): the FSM turns the intents of the drain into transactions. Queued by the
    /// FSM itself, at the tail of the mailbox.
    record Flush() implements ClusterInput {}

    /// CI-13: a timer fired. Carries the incarnation it was scheduled in; a stale one is dropped (R-3.1.3).
    record Timer(TimerId timer, long incarnation) implements ClusterInput {}

    /// CI-14: the outcome of a `Submit`.
    record WriteSettled(String correlationId, long incarnation, Settlement settlement) implements ClusterInput {}

    /// R-5.7.1 (a): the interpreter's self-check found the committed `LeaderValue` does not name this node.
    record WitnessStale() implements ClusterInput {}

    /// The committed mirror read by `Rebuild`: every entry the FSM indexes, the read seams and the admissions
    /// membership knows, and the committed leader.
    record Snapshot(List<KvPut> entries, Seams seams, Map<NodeId, Admission> admissions, Option<LeaderValue> leader) {
        public Snapshot {
            entries = List.copyOf(entries);
            admissions = Map.copyOf(admissions);
        }

        public static Snapshot snapshot(List<KvPut> entries,
                                        Seams seams,
                                        Map<NodeId, Admission> admissions,
                                        Option<LeaderValue> leader) {
            return new Snapshot(entries, seams, admissions, leader);
        }
    }

    /// How a `Submit` ended (CI-14).
    sealed interface Settlement {
        /// The transaction was accepted.
        record Accepted() implements Settlement {}

        /// The applier refused it. `leader` and `values` are the re-read of the committed mirror for the keys the
        /// transaction touched (R-5.7.2).
        record Refused(Option<LeaderValue> leader, Map<AetherKey, Option<AetherValue>> values) implements Settlement {
            public Refused {
                values = Map.copyOf(values);
            }
        }

        /// The submission failed, or its result could not be attributed.
        record Failed(String reason) implements Settlement {}
    }
}
