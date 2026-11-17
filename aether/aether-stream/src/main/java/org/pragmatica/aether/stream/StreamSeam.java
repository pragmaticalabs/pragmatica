// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

/// The late-bound seams of [StreamPartitionManager] (#2201 A-14, spec 5.1 target, bullet 4): 20 of them, bound by setters in
/// `StreamAssembly` (the owner-epoch source and the committed epoch starts through the production factory), not in `AetherNode`.
/// Each defaults to a value that lets a unit, Forge or legacy manager run without the cluster wiring; production binds every
/// one. [StreamPartitionManager#unboundSeams] lists the ones still at their default.
///
/// `[guess]` The safety-relevant classification is a judgement, not a derivation: a seam is safety-relevant when its default,
/// left in place on a node that keeps a WAL, would let the node act as an owner, admit or acknowledge writes, release a ring,
/// or skip a durable check, with no committed state behind it (Step 0 §4). [#EPOCH_STARTS] is NOT classed safety-relevant
/// although it guards replica trust, because its default is conservative rather than permissive (no history is known, so a
/// copy holding anything is doubted from 0 until compared); since #1971 it is a required argument of the production factory
/// instead. The operational and observability seams are not classed either.
public enum StreamSeam {
    /// The owner epoch a local publish is stamped with; the default stamps `Epoch.ZERO`.
    OWNER_EPOCH_SOURCE(true),
    /// The durable partition flag; the default only logs a provenance failure.
    PARTITION_FLAGS(true),
    OPERATOR_WARNINGS(false),
    /// The committed ownership records a validated consumer read is checked against; the default checks nothing.
    OWNERSHIP_RECORDS(true),
    /// This node's lease (#2981 step 2); the default is [OwnerLease#UNWIRED], never valid, so the owner checks fail closed.
    OWNER_LEASE(true),
    TIERED_READER(false),
    /// Where each committed epoch began (#1971); the default knows none and doubts every copy from 0 (conservative). Production
    /// passes it to the factory.
    EPOCH_STARTS(false),
    REPAIR_REPORT_BOUND(false),
    EXHAUSTION_SINK(false),
    /// The placement role; the default reports OWNER for every partition.
    PLACEMENT_ROLE_SUPPLIER(true),
    CLUSTER_SIZE_SUPPLIER(false),
    COMMITTED_CONFIG_SOURCE(false),
    /// The replicas' catch-up view; the default reports everyone caught up, so a ring may be released early.
    CATCHUP_SOURCE(true),
    /// The committed-owner check before a release; the default reports the owner elsewhere, so an owner's ring may be released.
    OWNER_RELEASE_GUARD(true),
    /// The committed-owner check on application appends; the default admits a non-owner's appends.
    OWNER_WRITE_ADMISSION(true),
    /// The owner promotion gate; the default serves and acknowledges as owner without promotion.
    OWNER_SERVE_GATE(true),
    OWNER_BLOCK_SOURCE(false),
    /// The durable anchor of stream lives; the default anchors neither life adoption nor reclamation.
    STREAM_FOOTPRINT(true),
    SEGMENT_TIER_PRESSURE(false),
    RESHUFFLE_CONCURRENCY(false);
    private final boolean safetyRelevant;
    StreamSeam(boolean safetyRelevant) {
        this.safetyRelevant = safetyRelevant;
    }
    /// Whether this seam's default, left in place on a node that keeps a WAL, is alarmed at boot (`[guess]`, see the type).
    public boolean safetyRelevant() {
        return safetyRelevant;
    }
}
