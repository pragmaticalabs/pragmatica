// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
/// The cluster (leader-side) deployment state machine of the rewrite specified in
/// `oss/internal/spec-rewrite/deployment-fsm-spec.md` (draft 6), as a pure transition function.
///
/// **Shape.** [ClusterFsm#step] takes a [ClusterState] and a [ClusterInput] and returns the next state and a list of
/// [ClusterEffect]s. The only reads beyond its arguments are the two injected clocks (read once per step) and the
/// injected jitter of [Settings]. The [ClusterModel] is owned by one step at a time and every field is an immutable value
/// ([PMap], [PSet], records), so a model that was returned never changes.
///
/// **Where the transitions live.**
/// - [Handlers]: the table of 3.3, one arm per input.
/// - [Lifecycle], [Reconcile]: the Active entry, the leader mirror, the timers and the ten-step reconcile pass.
/// - [Blueprints], [Schemas], [Slices], [Failures], [Strays], [Placement], [Rebalance], [Drains], [Communities], [Orphans],
///   [Divergence], [Outcomes]: the slice side.
/// - [Joins], [DepartureFlow], [Absence], [Backstop], [Directory], [Departures]: the membership side (the join table,
///   the fenced departure record, the per-class absence backstop and the community breaker).
/// - [Outbox]: the write side. Handlers record intents; a queued `Flush` turns the intents of one mailbox drain into
///   `LeaderTransaction`s; [Outbox#settle] handles completions and refusals.
///
/// **Not here (phase 3).** The effect interpreter (mailbox, scheduler, applier submission, snapshot read), the seams
/// (`admission(n)`, `coreView()`, the local alive view), and every reader of `NodeArtifactKey`.
package org.pragmatica.aether.deployment.cluster.fsm2;
