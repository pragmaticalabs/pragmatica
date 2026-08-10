// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Unit;


/// Declares to the node that this process has provisioned an entity `keyspace` spread over
/// `partitionCount` ownership arcs (#345 I1, narrow C).
///
/// ## Why this seam exists
/// Entity writes are admitted only by the committed owner of the key's arc, and those ownership records
/// are minted by a LEADER-ONLY reconcile pass over the shared `StreamPartitionOwnershipWriter`. The
/// leader therefore has to know which entity keyspaces exist and how many arcs each spans — and it
/// cannot work that out for itself: `DurableEntityConfig` is per-slice and node-local, and the slice
/// manifest carries only the config SECTION name, never the section's contents. Provisioning is the
/// first moment `partitionCount` is known, so it is where the declaration is made.
///
/// ## Contract
/// [#declare] is **synchronous, IO-free and idempotent**. It records intent; it does not perform the
/// consensus write. Provisioning must stay resolved-on-return (a slice load that blocked on a consensus
/// round would fail whenever the cluster was briefly unquorate), and a one-shot fire-and-forget commit
/// would silently strand the keyspace forever if that single apply failed. The node's implementation is
/// therefore level-triggered: it keeps the declaration and re-asserts it until it is committed, in the
/// self-healing shape `SystemStreamRegistrar` uses for the same reason.
///
/// The keyspace declared here is the RAW `resources.toml` name. The `entity:` ownership-arc prefix is
/// applied by [org.pragmatica.aether.dht.EntityPartitionArc], which owns that naming.
/// ## Why provisioning, and not the deployment FSM — with its expiry condition
/// `NodeDeploymentState` publishing an entity registration alongside its `StreamRegistrationKey` puts is
/// the architecturally correct home: same lifecycle, automatic removal on unload, no layering
/// compromise. It is foreclosed by a constraint from OUTSIDE this increment, not by preference.
/// `partitionCount` would have to reach the deployment layer either through the slice manifest — a
/// `ManifestGenerator` output-structure change, which requires bumping `ENVELOPE_FORMAT_VERSION`, which
/// is FROZEN at 1000 until GA by owner ruling — or by teaching the control plane to parse
/// `META-INF/resources.toml` itself, duplicating the config binder inside it. Both are worse than
/// declaring at the one point that already holds a bound `DurableEntityConfig`.
///
/// **Move this to `NodeDeploymentState` once the envelope unfreezes post-GA.** A layering compromise
/// with a recorded expiry condition is a decision; an unexplained one becomes folklore.
///
/// ## Known gap: a registration outlives a crashed node
/// Removal rides `ResourceFactory.close`, which does not run when a node dies abruptly. A keyspace can
/// therefore stay registered with nothing using it, and the leader keeps minting ownership records for
/// its arcs. No correctness impact — the records are consistent, they simply describe a keyspace nobody
/// reads — but the registration is not self-cleaning, and a keyspace that is genuinely gone needs its
/// registration removed by hand or by a reaper that does not exist yet.
@FunctionalInterface
public interface EntityKeyspaceRegistrar {
    /// Record that `keyspace` is live on this node over `partitionCount` ownership arcs. Idempotent:
    /// re-declaring the same keyspace, including from another node, is a no-op.
    Unit declare(String keyspace, int partitionCount);
}
