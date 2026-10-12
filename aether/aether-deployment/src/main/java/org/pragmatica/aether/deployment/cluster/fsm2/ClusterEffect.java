// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;


/// What a transition asks of the outside world (spec R-3.1.2). Effects are data: the pure transition returns them,
/// and the interpreter performs them. Completions come back as inputs ([ClusterInput.WriteSettled],
/// [ClusterInput.Timer]).
public sealed interface ClusterEffect {
    /// One `LeaderTransaction` (R-5.7.1): it carries the committed `LeaderValue` and the exact expected values of the
    /// mirror. The `Submit` effects of one step are the transactions of one mailbox drain; the interpreter sends them
    /// together as one `apply(List)` (R-5.7.4) and reports each one back with its own `WriteSettled`.
    record Submit(String correlationId,
                  long incarnation,
                  ClusterModel.SubmitKind kind,
                  KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction) implements ClusterEffect {}

    /// Read the committed mirror and deliver it as `ClusterInput.Rebuild`, the first item of the mailbox (R-3.1.6).
    record RequestRebuild(long incarnation) implements ClusterEffect {}

    /// Append `input` to the tail of this FSM's own mailbox (the coalesced `Reconcile`, the end-of-drain `Flush`).
    record Enqueue(ClusterInput input) implements ClusterEffect {}

    /// Fire `ClusterInput.Timer(timer, incarnation)` after `delayMs` of the injected scheduler's time.
    record Schedule(TimerId timer, long incarnation, long delayMs) implements ClusterEffect {}

    /// Cancel a scheduled timer.
    record Cancel(TimerId timer) implements ClusterEffect {}

    /// Route an event to the rest of the node.
    record Route(RoutedEvent event) implements ClusterEffect {}

    /// An operator warning (spec 1.3), by code, about a subject.
    record Warn(String code, String subject, String message) implements ClusterEffect {}

    /// An audit line.
    record Audit(String action, String detail) implements ClusterEffect {}

    /// A log line for the interpreter's logger.
    record Log(String message) implements ClusterEffect {}

    /// A counter increment: `aether_deployment_fsm_*` (spec 1.3, R-1.3.1).
    record Metric(String name, Map<String, String> tags, long delta) implements ClusterEffect {
        public Metric {
            tags = Map.copyOf(tags);
        }
    }

    /// Ask the schema orchestrator to (re-)dispatch the migration of `datasource` (`migrateIfNeeded`, C12).
    record DispatchMigration(String datasource) implements ClusterEffect {}
}
