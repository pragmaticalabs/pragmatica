// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
package org.pragmatica.aether.worker.metadata;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.lang.Unit;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;


/// Core-only index. Each committed key updates only its shared scopes; worker reads never scan
/// all cluster keys. Caller holds the KV commit monitor while mutating or capturing a manifest.
public final class WorkerMetadataIndex {
    public static final String GLOBAL = "global";
    public static final String DIRECTORY = "directory";
    public static final String ENDPOINT_DIRECTORY = "endpoint-directory";

    private final Map<StructuredKey, Object> values = new HashMap<>();
    private final Map<String, Map<StructuredKey, Object>> scopes = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private long revision;

    private WorkerMetadataIndex() {}

    public static WorkerMetadataIndex workerMetadataIndex() {
        return new WorkerMetadataIndex();
    }

    public Unit initialize(Map<?, ?> committed) {
        values.clear();
        scopes.clear();
        revisions.clear();
        committed.forEach((key, value) -> initializeEntry(key, value));

        return Unit.unit();
    }

    private void initializeEntry(Object key, Object value) {
        if (key instanceof StructuredKey structured) {
            put(structured, value);
        }
    }

    public Unit put(StructuredKey key, Object value) {
        var previous = values.put(key, value);

        if (value.equals(previous)) {
            return Unit.unit();
        }

        if (previous != null) {
            removeScopes(key, previous);
        }

        for (var scope : scopesFor(key, value)) {
            scopes.computeIfAbsent(scope, _ -> new HashMap<>()).put(key, value);
            revisions.put(scope, ++revision);
        }

        return Unit.unit();
    }

    public Unit remove(StructuredKey key) {
        var previous = values.remove(key);

        if (previous != null) {
            removeScopes(key, previous);
        }

        return Unit.unit();
    }

    private void removeScopes(StructuredKey key, Object value) {
        for (var scope : scopesFor(key, value)) {
            var entries = scopes.get(scope);

            if (entries != null) {
                entries.remove(key);
                if (entries.isEmpty()) {
                    scopes.remove(scope);
                    revisions.remove(scope);
                } else {
                    revisions.put(scope, ++revision);
                }
            }
        }
    }

    public boolean hasScope(String scope) {
        return scopes.containsKey(scope);
    }

    public long revision() {
        return revision;
    }

    public long revision(String scope) {
        return revisions.getOrDefault(scope, 0L);
    }

    public Map<StructuredKey, Object> snapshot(String scope) {
        return Map.copyOf(scopes.getOrDefault(scope, Map.of()));
    }

    public List<String> scopesForWorker(NodeId worker) {
        var selected = new LinkedHashSet<String>();

        selected.add(GLOBAL);
        selected.add(node(worker));
        var own = scopes.getOrDefault(node(worker), Map.of());

        own.forEach((key, value) -> selectOwnScope(selected, key, value));
        expandArtifacts(selected);

        return selected.stream()
                       .sorted()
                       .toList();
    }

    private static void selectOwnScope(Set<String> selected, StructuredKey key, Object value) {
        switch (key) {
            case AetherKey.ActivationDirectiveKey _ when value instanceof AetherValue.ActivationDirectiveValue directive -> {
                if (!directive.communityId().isEmpty()) {
                    selected.add(community(directive.communityId()));
                }
            }
            case AetherKey.NodeArtifactKey assignment -> selected.add(artifact(assignment.artifact()));
            case AetherKey.SliceNodeKey assignment -> selected.add(artifact(assignment.artifact()));
            case AetherKey.EntityKeyspaceRegistrationKey entity -> selected.add("entity:" + entity.keyspace());
            case AetherKey.StorageStatusKey storage -> selected.add("storage:" + storage.instanceName());
            default -> {}
        }
    }

    private void expandArtifacts(Set<String> selected) {
        var inspected = new HashSet<String>();

        while (true) {
            var pending = selected.stream()
                                  .filter(scope -> scope.startsWith("artifact:") && !inspected.contains(scope))
                                  .toList();

            if (pending.isEmpty()) {
                return;
            }

            for (var scope : pending) {
                inspected.add(scope);
                scopes.getOrDefault(scope, Map.of()).forEach((key, value) -> expandEntry(selected, key, value));
            }
        }
    }

    private static void expandEntry(Set<String> selected, StructuredKey key, Object value) {
        if (value instanceof AetherValue.AppBlueprintValue blueprint) {
            selected.add("blueprint:" + blueprint.blueprint().id().asString());
            blueprint.blueprint()
                     .loadOrder()
                     .forEach(slice -> {
                                  selected.add(artifact(slice.artifact()));
                                  slice.dependencies()
                                       .forEach(dependency -> selected.add(artifact(dependency)));
                              });
        }

        if (key instanceof AetherKey.StreamRegistrationKey stream) {
            selected.add("stream:" + stream.streamName());
        }
    }

    public Set<NodeId> peersForWorker(NodeId worker, Set<NodeId> cores) {
        var peers = new HashSet<>(cores);

        peers.add(worker);
        for (var scope : scopesForWorker(worker)) {
            if (scope.startsWith("community:")) {
                scopes.getOrDefault(scope, Map.of()).keySet().forEach(key -> addPeer(peers, key));
            }
        }

        return Set.copyOf(peers);
    }

    public Set<NodeId> endpointPeersForWorker(NodeId worker) {
        var peers = new HashSet<NodeId>();

        for (var scope : scopesForWorker(worker)) {
            if (scope.startsWith("artifact:")) {
                scopes.getOrDefault(scope,
                                    Map.of())
                      .forEach((key, value) -> {
                                   if (key instanceof AetherKey.NodeArtifactKey endpoint
                                       && value instanceof AetherValue.NodeArtifactValue state
                                       && state.state() == SliceState.ACTIVE) {
                                   peers.add(endpoint.nodeId());
                               }
                               });
            }
        }

        return Set.copyOf(peers);
    }

    private static void addPeer(Set<NodeId> peers, StructuredKey key) {
        if (key instanceof AetherKey.ActivationDirectiveKey activation) {
            peers.add(activation.nodeId());
        }
    }

    private static Set<String> scopesFor(StructuredKey key, Object value) {
        if (key instanceof LeaderKey) {
            return Set.of(GLOBAL);
        }

        if (! (key instanceof AetherKey aether)) {
            return Set.of();
        }

        return switch (aether) {
            case AetherKey.NodeArtifactKey entry -> Set.of(node(entry.nodeId()), artifact(entry.artifact()));
            case AetherKey.NodeRoutesKey entry -> Set.of(node(entry.nodeId()), artifact(entry.artifact()));
            case AetherKey.SliceNodeKey entry -> Set.of(node(entry.nodeId()), artifact(entry.artifact()));
            case AetherKey.EndpointKey entry -> Set.of(artifact(entry.artifact()));
            case AetherKey.SliceTargetKey entry -> Set.of("artifact:" + entry.artifactBase().asString());
            case AetherKey.VersionRoutingKey entry -> Set.of("artifact:" + entry.artifactBase().asString());
            case AetherKey.AbTestRoutingKey entry -> Set.of("artifact:" + entry.artifactBase().asString());
            case AetherKey.PreviousVersionKey entry -> Set.of("artifact:" + entry.artifactBase().asString());
            case AetherKey.AppBlueprintKey entry -> blueprintScopes(entry, value);
            case AetherKey.BlueprintStreamBindingsKey entry -> Set.of("blueprint:" + entry.blueprintId().asString());
            case AetherKey.ActivationDirectiveKey entry -> activationScopes(entry, value);
            case AetherKey.NodePlacementKey entry -> Set.of(node(entry.nodeId()));
            case AetherKey.JoinDeadlineKey entry -> Set.of(node(entry.nodeId()));
            case AetherKey.DrainDeadlineKey entry -> Set.of(node(entry.nodeId()));
            case AetherKey.HttpNodeRouteKey entry -> Set.of(node(entry.nodeId()));
            case AetherKey.StorageStatusKey entry -> Set.of(node(entry.nodeId()));
            case AetherKey.ConfigKey entry -> Set.of(entry.nodeScope().map(WorkerMetadataIndex::node).or(GLOBAL));
            case AetherKey.GovernorAnnouncementKey entry -> Set.of(community(entry.communityId()));
            case AetherKey.CommunityKey entry -> Set.of(community(entry.communityId()));
            case AetherKey.CommunityPlacementOperationKey entry -> Set.of(community(entry.communityId()));
            case AetherKey.WorkerSliceDirectiveKey entry -> Set.of(entry.communityId().map(WorkerMetadataIndex::community).or(GLOBAL));
            case AetherKey.ScheduledTaskKey entry -> Set.of(artifact(entry.artifact()));
            case AetherKey.ScheduledTaskStateKey entry -> Set.of(artifact(entry.artifact()));
            case AetherKey.TopicSubscriptionKey entry -> Set.of(GLOBAL, artifact(entry.artifact()));
            case AetherKey.StreamRegistrationKey entry -> Set.of(artifact(entry.artifact()),
                                                                 "stream:" + entry.streamName());
            case AetherKey.StreamCursorCheckpointKey entry -> Set.of("stream:" + entry.streamName());
            case AetherKey.ConsumerAssignmentKey entry -> Set.of("stream:" + entry.streamName());
            case AetherKey.ConsumerGroupKey entry -> Set.of("stream:" + entry.streamName());
            case AetherKey.EntityKeyspaceRegistrationKey entry -> Set.of(node(entry.node()),
                                                                         "entity:" + entry.keyspace());
            case AetherKey.EntityCheckpointKey entry -> Set.of("entity:" + entry.keyspace());
            case AetherKey.StorageBlockKey entry -> Set.of("storage:" + entry.instanceName());
            case AetherKey.StorageRefKey entry -> Set.of("storage:" + entry.instanceName());
            case AetherKey.DeploymentOutcomeKey _, AetherKey.DeploymentKey _, AetherKey.AbTestKey _, AetherKey.ApiKeyAuditKey _, AetherKey.CloudCredentialsKey _, AetherKey.ClusterConfigKey _, AetherKey.CapacityLedgerKey _, AetherKey.CapacityReservationKey _, AetherKey.CommunityPlacementAvailabilityKey _, AetherKey.ProvisioningSlotKey _, AetherKey.AutoHealStateKey _ -> Set.of();
            case AetherKey.LogLevelKey _, AetherKey.ObservabilityConfigKey _, AetherKey.AlertThresholdKey _, AetherKey.SchemaVersionKey _, AetherKey.SchemaMigrationLockKey _, AetherKey.GossipKeyRotationKey _, AetherKey.StreamMetadataKey _, AetherKey.StreamConfigKey _, AetherKey.ApiKeyKey _, AetherKey.DhtPartitionOwnershipKey _, AetherKey.StreamPartitionOwnershipKey _, AetherKey.SpokesmanKey _, AetherKey.ClusterPhaseKey _, AetherKey.StreamRegistryKey _ -> Set.of(GLOBAL);
        };
    }

    private static Set<String> blueprintScopes(AetherKey.AppBlueprintKey key, Object value) {
        var result = new HashSet<String>();

        result.add("blueprint:" + key.blueprintId().asString());
        if (value instanceof AetherValue.AppBlueprintValue blueprint) {
            blueprint.blueprint().loadOrder().forEach(slice -> result.add(artifact(slice.artifact())));
        }

        return Set.copyOf(result);
    }

    private static Set<String> activationScopes(AetherKey.ActivationDirectiveKey key, Object value) {
        if (value instanceof AetherValue.ActivationDirectiveValue directive) {
            if (AetherValue.ActivationDirectiveValue.CORE.equals(directive.role())) {
                return Set.of(GLOBAL, node(key.nodeId()));
            }

            if (!directive.communityId().isEmpty()) {
                return Set.of(node(key.nodeId()), community(directive.communityId()));
            }
        }

        return Set.of(node(key.nodeId()));
    }

    private static String node(NodeId id) {
        return "node:" + id.id();
    }

    private static String community(String id) {
        return "community:" + id;
    }

    private static String artifact(Artifact artifact) {
        return "artifact:" + artifact.base()
                                     .asString();
    }
}
