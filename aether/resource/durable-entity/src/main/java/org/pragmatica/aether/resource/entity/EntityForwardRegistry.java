// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Lets an arriving forwarded command reach the keyspace's LIVE entity instance (#596).
///
/// The receiving side must apply the command to the SAME instance the owner's own slice writes through,
/// or the two diverge: the per-key serialization queue and the folded state both live on the instance,
/// so a second instance would mean two writers for one key — the exact condition the ownership fence
/// exists to make impossible.
///
/// Registration mirrors [EntityCheckpointDriver]: the factory hands the provisioned entity to a
/// node-supplied extension at provisioning time. The entity does not know what a transport is, and the
/// node does not know what the entity's `K`/`S`/`C` are — the encoded boundary is the whole seam.
public interface EntityForwardRegistry {
    /// Called once per provisioned keyspace, at provisioning time.
    ///
    /// `void` by the same reasoning as [EntityCheckpointDriver#register]: this is a registration sink,
    /// not an operation with an outcome a caller could act on — there is nothing to fold.
    @Contract
    void register(String keyspace, ForwardTarget target);

    /// Called when the keyspace's entity resource unloads. Idempotent: unregistering an unknown
    /// keyspace is a no-op. Without this, an arriving forward still finds the unloaded entity and
    /// applies the command through a slice whose classloader is gone — instead of the honest typed
    /// refusal a genuinely absent keyspace produces.
    @Contract
    void unregister(String keyspace);

    /// A keyspace's apply-an-encoded-command entry point. Bound to one entity instance, which owns the
    /// per-key queue and the codecs for its own `K`, `S` and `C`.
    interface ForwardTarget {
        Promise<byte[]> applyForwarded(byte[] encodedKey, byte[] encodedCommand);
        /// Create, as [#applyForwarded] is update. Runs the owner's own already-exists check, so a
        /// forwarded create cannot overwrite a key a local create would have refused.
        Promise<byte[]> createForwarded(byte[] encodedKey, byte[] encodedInitial);
        /// Delete, as [#applyForwarded] is update. Answers with EMPTY bytes: a delete has no post-state,
        /// and the outcome the caller needs is the success or failure itself.
        Promise<byte[]> deleteForwarded(byte[] encodedKey);
        /// The `BOUNDED_STALE` read half (#596): serve the key from THIS node's fold, through the same
        /// ready/caught-up gates a local read runs — the answer's staleness bound is the serving node's,
        /// exactly as if the caller had been here. Absence is an EXPLICIT empty Option, never a byte
        /// convention: a zero-length-encoding edge reading as ABSENT is this ticket's original defect.
        /// Deliberately NOT behind the write admission — a bounded-stale read on the owner runs none
        /// locally either, and the answer stays honest under deposal (it claims a staleness bound, not
        /// currency).
        Promise<Option<byte[]>> getForwarded(byte[] encodedKey);

        /// Schedule a one-shot timer, as [#applyForwarded] is update (#345 I4). The DELAY arrives rather
        /// than an absolute instant, and THIS node stamps the fire instant from its own wall clock — so the
        /// clock that mints the instant is the clock that later finds it due, and no sender/owner skew
        /// enters the timer. A NEGATIVE delay is refused rather than applied: it would name an instant
        /// already past and fire on the next tick, turning "later" into "now" with nothing in the answer
        /// to say so.
        ///
        /// The TOKEN arrives too, minted by the sender before the hop: this node applies that token, and a
        /// token already pending on the key names a schedule that ALREADY landed, so it is answered without
        /// appending a second timer. Answers the token this node actually applied, in its STRING form — the
        /// token type belongs to this module and never crosses the wire, so the encoded boundary carries a
        /// string, the sender re-wraps it and verifies it against what it sent.
        Promise<String> scheduleTimerForwarded(byte[] encodedKey,
                                               long delayMillis,
                                               byte[] encodedOnFire,
                                               String token);

        /// Cancel a timer, as [#deleteForwarded] is delete (#345 I4). A cancel has no post-state and is
        /// idempotent on the owner, so the outcome the caller needs is the success or failure itself —
        /// answered as [Unit] rather than as empty bytes. The sender's half
        /// ([EntityOwnerForward#forwardCancelTimer]) already answers `Promise<Unit>`, and one operation
        /// cannot have two answers to "there is no payload" without the byte convention on one end drifting
        /// into meaning something.
        Promise<Unit> cancelTimerForwarded(byte[] encodedKey, String token);
    }
}
