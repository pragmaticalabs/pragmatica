// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Cause;


/// Typed failures for the [DurableEntity] primitive.
///
/// All durable-entity failures travel the [org.pragmatica.lang.Promise] error channel as
/// instances of this sealed type — never as exceptions. Fixed-message variants are grouped
/// into a single enum; variants carrying the offending key are records.
public sealed interface EntityError extends Cause {
    /// The string form of the entity key, used to render a stable, human-readable message
    /// without constraining the key type `K` to implement any particular contract.
    String key();

    /// A [DurableEntity#create] was issued for a key that already holds state.
    record EntityAlreadyExists(String key) implements EntityError {
        @Override
        public String message() {
            return "Durable entity already exists for key: " + key;
        }
    }

    /// An [DurableEntity#update], [DurableEntity#delete], or timer operation referenced a key
    /// that holds no state.
    record EntityNotFound(String key) implements EntityError {
        @Override
        public String message() {
            return "Durable entity not found for key: " + key;
        }
    }

    /// A timer operation referenced a key that holds no state, or a token that is not (or no longer)
    /// registered for that key.
    ///
    /// **No code path constructs this**, and the absence is deliberate rather than pending: every case it
    /// could name is answered by something else. Scheduling on a key that holds no state fails with
    /// [EntityNotFound]. A token that is not registered is not a failure at all —
    /// [DurableEntity#cancelTimer] is idempotent, so a token that never landed, already fired, was already
    /// cancelled, or belonged to a deleted key succeeds with no record appended. A due timer that cannot be
    /// applied reports [TimerFireFailed], which reaches the log rather than a caller. Retained because the
    /// durable-entity spec §5.3 pins it in the author-facing error surface; a slice matching this sealed
    /// type may name it, and nothing will send it.
    ///
    /// Carries the TOKEN as well as the key (spec §5.3). A caller holding several timers for one
    /// entity cannot act on "a timer was not found" — it needs to know WHICH, and the token is the
    /// only thing that distinguishes them.
    record TimerNotFound(String key, DurableEntity.TimerToken token) implements EntityError {
        @Override
        public String message() {
            return "Durable entity timer " + token.value() + " not found for key: " + key;
        }
    }

    /// A forwarded [DurableEntity#scheduleTimer] came back naming a token OTHER than the one the caller
    /// minted and sent.
    ///
    /// The caller's token is the handle it will later [DurableEntity#cancelTimer] by, so an owner that
    /// applied a different one leaves a durable timer the caller cannot name — precisely the hazard
    /// caller-side minting removes. The mismatch is reported rather than absorbed because it can only mean
    /// the token's identity was lost in the wire encoding or in the owner's already-pending check, and
    /// both are defects that must not pass as a success. `appliedToken` names what the owner answered, so
    /// the operator's recovery action is to cancel THAT token on the key, or to delete the key (which
    /// auto-cancels its pending timers).
    record TimerTokenMismatch(String key, DurableEntity.TimerToken token, String appliedToken) implements EntityError {
        @Override
        public String message() {
            return "Durable entity timer schedule for key '" + key
                 + "' was sent with token " + token.value()
                 + " but the owner answered with token '" + appliedToken
                 + "' — the scheduled timer cannot be cancelled by the token the caller holds";
        }
    }

    /// A forwarded [DurableEntity#scheduleTimer] arrived carrying a NEGATIVE delay.
    ///
    /// `delayMillis` is a wire field, and the arriving owner stamps the fire instant from it. A negative
    /// value names an instant already past, which the fold finds due on the very next tick — so a caller
    /// asking for "one-shot, later" would silently get "one-shot, now", with the acknowledgement giving no
    /// hint that anything was reinterpreted. Refused at the boundary rather than clamped to zero, because
    /// clamping would apply a timer the sender never asked for; the sender still holds the token it
    /// minted, so its recovery action is to re-send with a delay it means.
    record TimerDelayInvalid(String key, long delayMillis) implements EntityError {
        @Override
        public String message() {
            return "Durable entity timer schedule for key '" + key
                 + "' arrived with a negative delay of " + delayMillis
                 + " ms — a timer cannot be scheduled into the past";
        }
    }

    /// A timer operation reached a backing that has no durable log to hold a pending timer in — the
    /// HA-only in-memory cut ([InMemoryDurableEntity], [FencedDurableEntity]). It declines with this typed
    /// cause rather than silently no-op'ing, because a timer that is accepted and never fires is worse than
    /// one that is refused.
    ///
    /// **A running node never answers this.** [DurableEntityFactory] provisions only the fenced-log
    /// [PartitionFencedDurableEntity], where [DurableEntity#scheduleTimer] and
    /// [DurableEntity#cancelTimer] are ordinary fenced writes (#345 I4). This is the answer of the
    /// in-memory backings alone, which unit tests and harnesses construct directly.
    record TimerNotSupported(String key) implements EntityError {
        @Override
        public String message() {
            return "Durable entity timers are not yet supported for key: " + key;
        }
    }

    /// A due timer could not be applied: its command did not decode, the mutator threw, or the key it was
    /// scheduled on no longer holds state. Carries the TOKEN for the same reason [TimerNotFound] does —
    /// a key may hold several timers and only the token says which one.
    ///
    /// **This never reaches a caller**, because a timer has none: it is raised inside the timer tick,
    /// logged at ERROR, and the timer is then CONSUMED — durably cancelled — rather than retried. The
    /// entity's state is untouched. A retry would fail identically, because [DurableEntity#scheduleTimer]
    /// takes a pure `S -> S` command, so the operator's recovery action is to fix the command and schedule
    /// again; nothing clears this by itself.
    record TimerFireFailed(String key, DurableEntity.TimerToken token, Cause cause) implements EntityError {
        @Override
        public String message() {
            return "Durable entity timer " + token.value() + " for key '" + key + "' could not fire: " + cause.message();
        }
    }

    /// A fenced write was rejected because this node's owner epoch is stale — it has been deposed
    /// as owner of the entity's ownership arc since the operation began (split-brain handover; spec
    /// §4.2, §6). The write committed nowhere; the caller must re-resolve the current owner and
    /// retry there. `presentedEpoch` renders the rejected stamp (`term:counter`), carried straight
    /// from the underlying [org.pragmatica.dht.DHTError.StaleEpochWrite]; the committed high-water
    /// that out-ranked it is strictly newer and is observable via the ownership triad endpoint.
    record StaleOwnerEpoch(String key, String presentedEpoch) implements EntityError {
        @Override
        public String message() {
            return "Durable entity write for key '" + key
                 + "' rejected: this node's owner epoch " + presentedEpoch
                 + " is stale (deposed) — a newer owner has taken over the partition";
        }
    }

    /// A fenced write or read failed for an infrastructure reason other than a stale-owner fence
    /// rejection (e.g. a quorum/transport failure on the durable backing, or a serialization
    /// failure of the entity state). Wraps the originating [Cause] so the caller can inspect it;
    /// distinct from [StaleOwnerEpoch] so a deposition is never confused with a transport fault.
    record StorageFailed(String key, Cause cause) implements EntityError {
        @Override
        public String message() {
            return "Durable entity storage operation failed for key '" + key + "': " + cause.message();
        }
    }

    /// An operation reached a node that is NOT the committed owner of the entity key's
    /// `(keyspace, partition)` ownership arc. `committedOwner` names the node that IS.
    ///
    /// On the WRITE path (#345 I1) this is the admission check that makes the entity single-writer: the
    /// per-partition epoch fence answers "is this writer's view current?", which rejects a DEPOSED owner
    /// but waves through every live non-owner, since they all read the same committed epoch. Only an
    /// owner check answers "is this writer the owner at all", and without it five nodes each accept a
    /// create for one key and each believe they hold the only copy.
    ///
    /// On the READ path it rejects a [ReadConsistency#LINEARIZABLE] [DurableEntity#get] rather than
    /// forwarding it: no entity read-forward transport exists yet (#345 item 1e-b). Either way the caller
    /// re-resolves the current owner and retries there. The sibling of the stream's
    /// `StreamError.NotCurrentOwner`; owner-forwarding an entity operation cross-node is a follow-up.
    ///
    /// Distinct from [OwnershipNotYetCommitted], where NOBODY owns the arc yet.
    record NotCurrentOwner(String key, String committedOwner) implements EntityError {
        @Override
        public String message() {
            return "Durable entity operation for key '" + key
                 + "' reached a non-owner; committed owner is " + committedOwner;
        }
    }

    /// No ownership record is committed for the entity key's `(keyspace, partition)` arc yet, so no node
    /// can prove it is the owner and the write is refused.
    ///
    /// **This is TRANSIENT and self-clearing** — the distinction from [NotCurrentOwner], which is a stable
    /// "someone else owns this, go there". Ownership records are minted by a leader-only reconcile pass,
    /// so a freshly provisioned keyspace has a window in which no arc has an owner. Accepting writes
    /// through that window would reopen exactly the hole the owner check closes — and at the check the
    /// window is indistinguishable from an arc that will never have an owner, so admitting on absence
    /// admits both. The caller retries; a fixture waits for ownership to converge rather than sleeping.
    record OwnershipNotYetCommitted(String key, String keyspace, int partition) implements EntityError {
        @Override
        public String message() {
            return "Durable entity write for key '" + key
                 + "' refused: no owner is committed yet for arc (" + keyspace
                 + ", " + partition
                 + ") — transient, retry once the ownership reconcile has converged";
        }
    }

    /// A [ReadConsistency#LINEARIZABLE] read at the committed owner found the committed owner epoch
    /// STRICTLY older than the entity key's `(keyspace, partition)` ownership-arc high-water — self is a
    /// deposed owner whose committed record is now stale (a newer owner took over, possibly during the
    /// no-op round). The read-side sibling of [StaleOwnerEpoch] and the stream's `StreamError.StaleEpochRead`:
    /// the read is rejected rather than served stale, so the caller re-resolves the current owner.
    /// `presentedEpoch` is the stale committed stamp; `highWaterEpoch` the committed high-water that
    /// out-ranked it (both `term:counter`).
    record StaleEpochRead(String key, String presentedEpoch, String highWaterEpoch) implements EntityError {
        @Override
        public String message() {
            return "Linearizable read for durable entity key '" + key
                 + "' rejected: committed owner epoch " + presentedEpoch
                 + " is stale (deposed) — arc high-water is " + highWaterEpoch;
        }
    }

    /// A [ReadConsistency#LINEARIZABLE] read reached an entity provisioned WITHOUT an
    /// [EntityLinearizableBarrier], so the no-op consensus round that makes the post-round epoch fence
    /// current cannot be ordered. The guarantee the caller asked for cannot be met here, and the
    /// alternative — quietly serving the local [ReadConsistency#BOUNDED_STALE] read under the stronger
    /// name — is the thing this variant exists to prevent. Distinct in kind from every sibling above:
    /// those report that an OPERATION was refused, this reports that a requested GUARANTEE is
    /// unavailable on this node. [ReadConsistency#BOUNDED_STALE] reads of the same key are unaffected,
    /// so the caller either accepts bounded staleness or retries where the barrier is wired.
    record LinearizableUnavailable(String key) implements EntityError {
        @Override
        public String message() {
            return "Linearizable read for durable entity key '" + key
                 + "' cannot be served: no linearizable barrier is wired on this node, so the no-op "
                 + "consensus round cannot be ordered — request BOUNDED_STALE to read the local committed prefix";
        }
    }
}
