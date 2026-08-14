// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Cause;


/// Reasons a [DurableEntity] resource cannot be provisioned as declared — the refusal vocabulary of the
/// durable-entity module, distinct from [EntityError] because none of these is scoped to an
/// entity key: they describe a resource that never came into existence.
///
/// Every variant exists to make a previously SILENT wrong behaviour loud (#345 I1). A cause raised here
/// reaches the operator as `SliceLoadingFailure.Fatal` — `ResourceCreationFailed` for a provisioning
/// refusal, `ConfigurationFailed` for a config refusal — and lands verbatim in the cluster-event feed's
/// `DEPLOYMENT_FAILED` record, so a slice that cannot get the guarantees it declared fails to start
/// instead of starting wrong.
public sealed interface EntityProvisioningError extends Cause {
    /// `replication_factor` must be at least one — it becomes the backing stream's `replicas`, the total
    /// copies of each partition INCLUDING the owner, and a partition with no copies has no owner to write
    /// to.
    ///
    /// **This replaced `ReplicationNotSupported` in #345 I3.** Until I3 the field was refused above `1`,
    /// because the entity committed to a single process-local `StorageEngine` and could not replicate
    /// anything; refusing was the honest reading at the time. I3 moved entity state onto a fenced,
    /// fsync-durable, REPLICATED stream partition, so the field is now honoured — which is what closes
    /// the gap the refusal was standing in for. See [DurableEntityConfig#minSyncReplicas()] for what each
    /// value buys.
    record InvalidReplicationFactor(int requested) implements EntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity replication_factor = " + requested + " is invalid: must be at least 1";
        }
    }

    /// The keyspace's durable log could not be materialized, so the entity would have had nowhere to
    /// persist anything.
    ///
    /// Refused rather than degraded, on the same reasoning as [FenceUnavailable]: an entity with no log is
    /// an entity with no durability, and starting one would mean serving a resource that answers to the
    /// name "durable entity" while holding state no restart survives. The realistic cause is a cluster
    /// whose stream partition budget or ring pool is exhausted, which is an operator-actionable condition
    /// rather than a code fault — so the underlying cause is carried through verbatim.
    record LogUnavailable(String keyspace, Cause reason) implements EntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity keyspace '" + keyspace
                 + "' cannot be provisioned: its durable log could not be created — " + reason.message()
                 + "; refusing rather than serving an entity that persists nothing";
        }
    }

    /// `partition_count` must be at least one — it is the divisor of the key→`(keyspace, partition)`
    /// ownership-arc mapping ([org.pragmatica.aether.dht.EntityPartitionArc]), so a non-positive value
    /// has no meaning and would fail later, deep inside a modulo.
    record InvalidPartitionCount(int requested) implements EntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity partition_count = " + requested + " is invalid: must be at least 1";
        }
    }

    /// A collaborator the WRITE FENCE depends on was absent from the provisioning context, so the entity
    /// could only have been built unfenced.
    ///
    /// Provisioning refuses rather than falling back, per the #345 I1 owner ruling: an absent fence costs
    /// SAFETY, not freshness — it accepts writes from a deposed owner, the five-writers-for-one-key shape
    /// I0 measured. A silent fallback would reintroduce that defect behind a green build, and a future
    /// refactor that dropped a single `registerExtension` call would do it invisibly. Contrast
    /// [EntityError.LinearizableUnavailable], where the missing collaborator (the barrier) costs
    /// only freshness and is therefore refused per-READ rather than per-RESOURCE.
    record FenceUnavailable(String keyspace, String collaborator) implements EntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity keyspace '" + keyspace
                 + "' cannot be provisioned: the write fence requires " + collaborator
                 + ", which this node did not register — refusing rather than serving an unfenced entity";
        }
    }
}
