// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Unit;


/// Declares to the node that this process has provisioned an entity `keyspace` spread over
/// `partitionCount` ownership arcs (#345 I1, narrow C) — and retracts the declaration when the
/// keyspace's last local consumer unloads.
///
/// ## Why this seam exists
/// Entity writes are admitted only by the committed owner of the key's arc, and those ownership records
/// are minted by a LEADER-ONLY reconcile pass over the shared `StreamPartitionOwnershipWriter`. The
/// leader therefore has to know which entity keyspaces exist, how many arcs each spans, and — because
/// owners must be minted ONLY over nodes that can actually serve the keyspace — which nodes host its
/// declaring slice. It cannot work any of that out for itself: `DurableEntityConfig` is per-slice and
/// node-local, and the slice manifest carries only the config SECTION name, never the section's
/// contents. Provisioning is the first moment `partitionCount` is known, so it is where the declaration
/// is made; each node's declaration commits as its OWN per-node record, and the set of committed records
/// IS the hosting set the leader places owners over.
///
/// ## Contract
/// [#declare] and [#retract] are **synchronous, IO-free and idempotent**. They record intent; they do
/// not perform the consensus write. Provisioning must stay resolved-on-return (a slice load that blocked
/// on a consensus round would fail whenever the cluster was briefly unquorate), and a one-shot
/// fire-and-forget commit would silently strand the keyspace forever if that single apply failed. The
/// node's implementation is therefore level-triggered IN BOTH DIRECTIONS: every reconcile tick it makes
/// this node's committed records equal this node's declared set — asserting declared keyspaces until the
/// put commits, and pruning committed records for keyspaces no longer declared until the remove commits.
/// The prune leg is what makes [#retract] durable, and it also self-heals the case retract can never
/// see: a node that died and restarted WITHOUT the slice (moved away while it was down) finds its stale
/// record and removes it, because "committed but not declared" is the same observable state.
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
/// ## A crashed node's record is stale until the node returns
/// Nothing runs on a node that dies abruptly, so its record stays committed until the node itself comes
/// back and prunes (or re-asserts) it. That staleness is harmless for placement: the leader intersects
/// the hosting set with live membership before minting, so a dead host is never chosen. A node that
/// never returns leaves a permanently dead record — consistent, describing nothing, excluded from every
/// decision — reaped only by hand until a reaper exists.
public interface EntityKeyspaceRegistrar {
    /// Record that `keyspace` is live on this node over `partitionCount` ownership arcs. Idempotent:
    /// re-declaring the same keyspace is a no-op.
    Unit declare(String keyspace, int partitionCount);
    /// Record that `keyspace` is no longer live on this node — the last local consumer of its entity
    /// resource unloaded. Idempotent: retracting an undeclared keyspace is a no-op. The committed
    /// record disappears on the node's next reconcile tick, and the leader then re-places any arcs this
    /// node owned within the remaining hosting set.
    Unit retract(String keyspace);
}
