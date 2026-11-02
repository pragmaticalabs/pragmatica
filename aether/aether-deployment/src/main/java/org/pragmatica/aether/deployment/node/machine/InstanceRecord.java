// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;


/// The per-key record of the node FSM (spec 3.4 `InstanceRecord`), one per artifact this node has a desired or observed
/// row for.
///
/// - `desired`, `observed`: the applied mirror of the node's two keys. `observed` is the node's own row.
/// - `lastVersion`: version of the last confirmed write, or of the stale row N0 found (R-3.4.9 (1)); 0 after a Remove.
/// - `chainGeneration`: generation of the goal the latest chain executed. `appliedGeneration`: generation of the last
///   completed chain, or N0's corroborated row (R-3.4.1).
/// - `chain`: the live chain; its step token is the spec's `opToken`. The spec's `localPhase` is not stored: it is
///   what `chain`, `write` and `observed` already say.
/// - `write`: the one observed write in flight. `unconfirmed`: the last submitted write not yet seen committed.
/// - `parked`: a start refused by the committed-target gate (N2). `suspended`: ACTIVE and unpublished on a quorum loss.
/// - `deferredFailure`: a failure to write as FAILED once the in-flight write resolves.
/// - `serving`: the invocation bridge is registered for the slice. `desiredPending`: the desired row changed during
///   the live chain and is re-evaluated when it ends (R-3.4.2).
/// - `tombstoneArmed`: the version of the tombstone whose grace timer is armed, 0 when none.
public record InstanceRecord(Option<SliceDesiredValue> desired,
                             Option<SliceObservedValue> observed,
                             long lastVersion,
                             Option<Generation> chainGeneration,
                             Option<Generation> appliedGeneration,
                             Option<Chain> chain,
                             Option<InFlight> write,
                             Option<Unconfirmed> unconfirmed,
                             Option<Parked> parked,
                             Option<FailureNote> deferredFailure,
                             boolean suspended,
                             boolean serving,
                             boolean desiredPending,
                             long tombstoneArmed) {
    public static InstanceRecord instanceRecord() {
        return new InstanceRecord(none(),
                                  none(),
                                  0L,
                                  none(),
                                  none(),
                                  none(),
                                  none(),
                                  none(),
                                  none(),
                                  none(),
                                  false,
                                  false,
                                  false,
                                  0L);
    }

    public InstanceRecord withDesired(Option<SliceDesiredValue> desired) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withObserved(Option<SliceObservedValue> observed) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withLastVersion(long lastVersion) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withChainGeneration(Option<Generation> chainGeneration) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withAppliedGeneration(Option<Generation> appliedGeneration) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withChain(Option<Chain> chain) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withWrite(Option<InFlight> write) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withUnconfirmed(Option<Unconfirmed> unconfirmed) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withParked(Option<Parked> parked) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withDeferredFailure(Option<FailureNote> deferredFailure) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withSuspended(boolean suspended) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withServing(boolean serving) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withDesiredPending(boolean desiredPending) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    public InstanceRecord withTombstoneArmed(long tombstoneArmed) {
        return new InstanceRecord(desired,
                                  observed,
                                  lastVersion,
                                  chainGeneration,
                                  appliedGeneration,
                                  chain,
                                  write,
                                  unconfirmed,
                                  parked,
                                  deferredFailure,
                                  suspended,
                                  serving,
                                  desiredPending,
                                  tombstoneArmed);
    }

    /// The committed goal is `goal`.
    public boolean hasGoal(SliceGoal goal) {
        return desired.filter(value -> value.goal() == goal)
                      .isPresent();
    }

    /// The node's committed row is in `state`.
    public boolean hasRow(SliceObservedState state) {
        return observed.filter(row -> row.state() == state)
                       .isPresent();
    }

    /// The generation `generation` was already executed by a chain of this key.
    public boolean executed(Generation generation) {
        return chainGeneration.filter(generation::equals)
                              .isPresent() || appliedGeneration.filter(generation::equals)
                                                               .isPresent();
    }

    public boolean isIdle() {
        return chain.isEmpty() && write.isEmpty();
    }

    public long opToken() {
        return chain.map(Chain::stepToken)
                    .or(0L);
    }

    /// Nothing left to say about the key: no mirror, no live work, nothing parked or owed.
    public boolean isVacant() {
        return desired.isEmpty()
               && observed.isEmpty()
               && isIdle()
               && parked.isEmpty()
               && unconfirmed.isEmpty()
               && deferredFailure.isEmpty()
               && !suspended
               && !serving;
    }
}
