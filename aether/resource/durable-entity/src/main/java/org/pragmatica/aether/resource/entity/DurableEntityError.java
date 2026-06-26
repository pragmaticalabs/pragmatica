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
}
