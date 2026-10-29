// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.ReplicaKeyFilter;
import org.pragmatica.cluster.state.kvstore.StructuredKey;


/// The worker-replica exclusion set, in one place: the store's replica ring and its replica snapshot both ask this class, so what a
/// replica is fed and what it is snapshotted from cannot disagree. Part of the worker replica feed (owner ruling QJ-7: workers hold a
/// full replica of the KV, fed by a core and governor replica feed).
///
/// The classification is an EXHAUSTIVE `switch` over the sealed [AetherKey] with no `default`: a new key class does not compile
/// until it is classified here. The replica is "full minus an exclusion set": the stream cursor checkpoints (declarative consumers are
/// core-only, programmatic ones read cursors through forwarded batches) and the cluster configuration (its TOML may carry credentials).
/// The five keys of the deployment state machine rewrite (`SliceDesiredKey`, `SliceObservedKey`, `DeploymentApplyKey`,
/// `ArtifactJudgmentKey`, `NodeDepartureKey`) are EXCLUDED until P3-D decides what a worker replicates of them (marked guess: nothing
/// reads them on a worker, so the fail-closed class is the conservative one; adding them to the INCLUDE arm later is a one-line move).
/// Every other key class is INCLUDED, including the ones a worker has never seen (deployment, capacity, provisioning, upgrade and
/// auto-heal state, the API key audit, the leader key).
///
/// The store is mixed: besides [AetherKey] it holds [LeaderKey]. The leader key is INCLUDED; any other key type is EXCLUDED and counted
/// ([#foreignKeyExclusions()]), so an unclassified key family fails closed.
public final class ReplicaFilter implements ReplicaKeyFilter {
    private static final ReplicaFilter INSTANCE = new ReplicaFilter();

    private final AtomicLong foreignKeyExclusions = new AtomicLong();

    private ReplicaFilter() {}

    public static ReplicaFilter replicaFilter() {
        return INSTANCE;
    }

    @Override
    public boolean excludes(StructuredKey key) {
        return switch (key) {
            case AetherKey aether -> excludesAetherKey(aether);
            case LeaderKey _ -> false;
            default -> excludesForeignKey();
        };
    }

    /// How many keys of a type this filter does not know were excluded (fail closed).
    public long foreignKeyExclusions() {
        return foreignKeyExclusions.get();
    }

    private boolean excludesForeignKey() {
        foreignKeyExclusions.incrementAndGet();

        return true;
    }

    /// The classification table. EXCLUDE: stream cursor checkpoints, the cluster configuration and the five deployment-rewrite keys. INCLUDE: everything else, by name.
    static boolean excludesAetherKey(AetherKey key) {
        return switch (key) {
            case AetherKey.StreamCursorCheckpointKey _, AetherKey.ClusterConfigKey _, AetherKey.SliceDesiredKey _, AetherKey.SliceObservedKey _, AetherKey.DeploymentApplyKey _, AetherKey.ArtifactJudgmentKey _, AetherKey.NodeDepartureKey _ -> true;
            case AetherKey.AbTestKey _, AetherKey.ActivationDirectiveKey _, AetherKey.AlertThresholdKey _, AetherKey.ApiKeyAuditKey _, AetherKey.ApiKeyKey _, AetherKey.AppBlueprintKey _, AetherKey.ArtifactContentKey _, AetherKey.ArtifactVersionsKey _, AetherKey.AutoHealStateKey _, AetherKey.BackupRestoreKey _, AetherKey.BlueprintStreamBindingsKey _, AetherKey.CapacityAdmissionKey _, AetherKey.CapacityLedgerKey _, AetherKey.CapacityReservationKey _, AetherKey.ClusterIncarnationKey _, AetherKey.ClusterPhaseKey _, AetherKey.CommunityKey _, AetherKey.CommunityPlacementAvailabilityKey _, AetherKey.CommunityPlacementOperationKey _, AetherKey.ConfigKey _, AetherKey.ConsumerAssignmentKey _, AetherKey.ConsumerGroupKey _, AetherKey.DeploymentKey _, AetherKey.DeploymentOutcomeKey _, AetherKey.DhtPartitionOwnershipKey _, AetherKey.DhtReplicationChangeKey _, AetherKey.DhtReplicationReportKey _, AetherKey.DrainDeadlineKey _, AetherKey.EndpointKey _, AetherKey.EntityCheckpointKey _, AetherKey.EntityKeyspaceRegistrationKey _, AetherKey.GossipKeyRotationKey _, AetherKey.GovernorAnnouncementKey _, AetherKey.HttpNodeRouteKey _, AetherKey.JoinDeadlineKey _, AetherKey.LogLevelKey _, AetherKey.NodeArtifactKey _, AetherKey.NodePlacementKey _, AetherKey.NodeReplacementKey _, AetherKey.NodeRoutesKey _, AetherKey.ObservabilityConfigKey _, AetherKey.PreviousVersionKey _, AetherKey.ProvisioningSlotKey _, AetherKey.ScheduledTaskClaimKey _, AetherKey.ScheduledTaskKey _, AetherKey.ScheduledTaskPauseKey _, AetherKey.ScheduledTaskStateKey _, AetherKey.SchemaMigrationLockKey _, AetherKey.SchemaVersionKey _, AetherKey.SliceNodeKey _, AetherKey.SliceTargetKey _, AetherKey.SpokesmanKey _, AetherKey.StorageStatusKey _, AetherKey.StreamConfigKey _, AetherKey.StreamPartitionOwnershipKey _, AetherKey.StreamPartitionRecoveryKey _, AetherKey.StreamRegistrationKey _, AetherKey.StreamRegistryHolderKey _, AetherKey.StreamRegistryKey _, AetherKey.TopicPublisherKey _, AetherKey.TopicSubscriptionKey _, AetherKey.UnconfirmedTerminationKey _, AetherKey.UpgradeRunKey _, AetherKey.VersionRoutingKey _, AetherKey.WorkerSliceDirectiveKey _ -> false;
        };
    }
}
