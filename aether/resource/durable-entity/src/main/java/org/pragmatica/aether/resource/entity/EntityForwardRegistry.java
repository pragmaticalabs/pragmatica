// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;


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
    }
}
