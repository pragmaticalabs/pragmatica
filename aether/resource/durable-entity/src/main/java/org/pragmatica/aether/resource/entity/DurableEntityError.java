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
public sealed interface DurableEntityError extends Cause {
    /// The string form of the entity key, used to render a stable, human-readable message
    /// without constraining the key type `K` to implement any particular contract.
    String key();

    /// A [DurableEntity#create] was issued for a key that already holds state.
    record KeyAlreadyExists(String key) implements DurableEntityError {
        @Override
        public String message() {
            return "Durable entity already exists for key: " + key;
        }
    }

    /// An [DurableEntity#update], [DurableEntity#delete], or timer operation referenced a key
    /// that holds no state.
    record KeyNotFound(String key) implements DurableEntityError {
        @Override
        public String message() {
            return "Durable entity not found for key: " + key;
        }
    }

    /// A timer operation referenced a key that holds no state, or a token that is not (or no
    /// longer) registered for that key. Reserved for the durable-timer slice (spec §4.5); the
    /// in-memory cut declines timer operations with [TimerNotSupported].
    record TimerNotFound(String key) implements DurableEntityError {
        @Override
        public String message() {
            return "Durable entity timer not found for key: " + key;
        }
    }

    /// Timers are declared in the [DurableEntity] API (spec §5) but are owned by a later slice
    /// (durable, fenced-persisted, handover-recovered — spec §4.5). The HA-only in-memory cut
    /// declines them with this typed cause rather than silently no-op'ing.
    record TimerNotSupported(String key) implements DurableEntityError {
        @Override
        public String message() {
            return "Durable entity timers are not yet supported for key: " + key;
        }
    }

    /// A fenced write was rejected because this node's owner epoch is stale — it has been deposed
    /// as owner of the entity's ownership arc since the operation began (split-brain handover; spec
    /// §4.2, §6). The write committed nowhere; the caller must re-resolve the current owner and
    /// retry there. `presentedEpoch` renders the rejected stamp (`term:counter`), carried straight
    /// from the underlying [org.pragmatica.dht.DHTError.StaleEpochWrite]; the committed high-water
    /// that out-ranked it is strictly newer and is observable via the ownership triad endpoint.
    record StaleOwner(String key, String presentedEpoch) implements DurableEntityError {
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
    /// distinct from [StaleOwner] so a deposition is never confused with a transport fault.
    record StorageFailed(String key, Cause cause) implements DurableEntityError {
        @Override
        public String message() {
            return "Durable entity storage operation failed for key '" + key + "': " + cause.message();
        }
    }

    /// A [ReadConsistency#LINEARIZABLE] [DurableEntity#get] reached a node that is NOT the committed
    /// owner of the entity key's `(keyspace, partition)` ownership arc. No entity read-forward transport
    /// exists yet (#345 item 1e-b), so the read is rejected here rather than forwarded; the caller
    /// re-resolves the current owner and retries there. `committedOwner` names the node that IS the
    /// committed owner. The read-side sibling of the stream's `StreamError.NotCurrentOwner`;
    /// owner-forwarding an entity read cross-node is a follow-up.
    record NotCurrentOwner(String key, String committedOwner) implements DurableEntityError {
        @Override
        public String message() {
            return "Linearizable read for durable entity key '" + key
                 + "' reached a non-owner; committed owner is " + committedOwner;
        }
    }

    /// A [ReadConsistency#LINEARIZABLE] read at the committed owner found the committed owner epoch
    /// STRICTLY older than the entity key's `(keyspace, partition)` ownership-arc high-water — self is a
    /// deposed owner whose committed record is now stale (a newer owner took over, possibly during the
    /// no-op round). The read-side sibling of [StaleOwner] and the stream's `StreamError.StaleEpochRead`:
    /// the read is rejected rather than served stale, so the caller re-resolves the current owner.
    /// `presentedEpoch` is the stale committed stamp; `highWaterEpoch` the committed high-water that
    /// out-ranked it (both `term:counter`).
    record StaleEpochRead(String key, String presentedEpoch, String highWaterEpoch) implements DurableEntityError {
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
    record LinearizableUnavailable(String key) implements DurableEntityError {
        @Override
        public String message() {
            return "Linearizable read for durable entity key '" + key
                 + "' cannot be served: no linearizable barrier is wired on this node, so the no-op "
                 + "consensus round cannot be ordered — request BOUNDED_STALE to read the local committed prefix";
        }
    }
}
