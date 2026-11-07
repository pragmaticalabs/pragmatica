// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

/// What an interpreter does with a replay-flagged KV input (phase-3 design D8, 4.4). The rule depends on the node's role.
///
/// | Rule | A replay-flagged input | Any KV input at or below the snapshot revision |
/// |---|---|---|
/// | `CORE` | dropped, `dropped_replay`: a restore never runs while the engine is ACTIVE, and is bracketed by the genuine quorum edges | dropped, `dropped_revision` |
/// | `WORKER` | a fact: the install, signalled by the store ahead of its burst even when the burst is empty, resets the snapshot revision to its own, and the burst is stepped as the authoritative diff (ruling D8, know `5b4db3a518`); the same install replayed again is deduplicated | dropped, `dropped_revision` |
///
/// **Why a worker steps a replay.** A worker's store is a replica fed by a core (owner ruling QJ-7, know `23ca0fd65f` D.2): a re-subscription
/// installs a snapshot and replays the difference to what was delivered (`ReplicaApplier.install`, `KVStore.replayNotifications`), while the
/// worker's node FSM stays ACTIVE. That burst is the only carrier of the goals committed during the gap; dropping it would freeze the worker
/// (review C1). The dedupe by revision is sound on a worker because the replica carries the cores' revisions (`KVStore.applyEffects` sets
/// `slot + 1`, an install sets the snapshot's revision), so a stamp at or below the snapshot revision is in the snapshot. An install can move
/// that revision DOWN (`KVStore.restoreCommittedSnapshot` assigns it: a new incarnation, or `AHEAD` against a lagging core), so the snapshot
/// revision a worker dedupes against is the last install's, never a monotone maximum.
public enum ReplayRule {
    CORE,
    WORKER
}
