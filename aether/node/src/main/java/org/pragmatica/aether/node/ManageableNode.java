// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.LifecycleWriter;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.node.lifecycle.NodeLifecycle;
import org.pragmatica.aether.deployment.delegation.TaskAssignmentCoordinator;
import org.pragmatica.aether.slice.delegation.TaskGroupAssignmentRegistry;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.metrics.ComprehensiveSnapshotCollector;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.artifact.ArtifactMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.node.StorageFactory;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.Message;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;

import java.util.List;
import java.util.Map;
import java.util.Set;


public interface ManageableNode {
    NodeId self();
    KVStore<AetherKey, AetherValue> kvStore();
    SliceStore sliceStore();
    ClusterSyncCollector metricsCollector();
    DeploymentMetricsCollector deploymentMetricsCollector();
    ControlLoop controlLoop();
    BlueprintService blueprintService();
    MavenProtocolHandler mavenProtocolHandler();
    ArtifactStore artifactStore();
    TopologyManager topologyManager();
    InvocationMetricsCollector invocationMetrics();
    DeploymentManager deploymentManager();
    AbTestManager abTestManager();
    AppHttpServer appHttpServer();
    HttpRouteRegistry httpRouteRegistry();
    TTMManager ttmManager();
    ComprehensiveSnapshotCollector snapshotCollector();
    ArtifactMetricsCollector artifactMetricsCollector();
    DeploymentMap deploymentMap();
    ClusterEventAggregator eventAggregator();
    BackupService backupService();
    StreamPartitionManager streamPartitionManager();
    StreamReadRouter streamReadRouter();
    ConsumerGroupCoordinator consumerGroupCoordinator();
    ConsumerGroupRegistry consumerGroupRegistry();
    TaskAssignmentCoordinator taskAssignmentCoordinator();
    TaskGroupAssignmentRegistry taskGroupAssignmentRegistry();
    Map<String, StorageFactory.StorageSetup> storageSetups();
    Option<ClusterTopologyManager> clusterTopologyManager();
    Option<CertificateRenewalScheduler> certRenewalScheduler();
    Option<ClusterGenerationSnapshot> currentGenerationSnapshot();
    void requestGenerationSnapshotRefresh();
    int connectedNodeCount();
    Map<String, Number> transportMetrics();
    Set<NodeId> connectedPeerIds();
    boolean isLeader();
    boolean isReady();
    Option<NodeId> leader();
    <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands);
    int managementPort();
    int appHttpPort();
    long uptimeSeconds();
    List<NodeId> initialTopology();
    TopologyConfig topologyConfig();
    HealthSignalSink healthSignalSink();
    LifecycleWriter lifecycleWriter();
    InFlightRequestTracker inFlightRequestTracker();
    DrainCoordinator drainCoordinator();
    NodeLifecycle nodeLifecycle();
    @SuppressWarnings("JBCT-RET-01") void route(Message message);
}
