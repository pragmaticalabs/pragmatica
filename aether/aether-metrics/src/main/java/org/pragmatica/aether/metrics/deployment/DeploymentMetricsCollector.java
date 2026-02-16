package org.pragmatica.aether.metrics.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.*;
import org.pragmatica.aether.metrics.deployment.DeploymentMetrics.DeploymentStatus;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsEntry;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPing;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPong;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;

/// Collects and manages deployment timing metrics for slice deployments.
///
///
/// Responsibilities:
///
///   - Track in-progress deployments with timestamps
///   - Store completed deployment metrics (last N per artifact)
///   - Handle DeploymentMetricsPing/Pong for cluster-wide visibility
///
///
///
/// Metrics are stored in-memory. Completed deployments retain last N entries per artifact.
public interface DeploymentMetricsCollector {
    /// Default number of completed deployments to retain per artifact.
    int DEFAULT_RETENTION_COUNT = 10;

    /// Handle deployment started event (dispatched via MessageRouter).
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onDeploymentStarted(DeploymentStarted event);

    /// Handle state transition event (dispatched via MessageRouter).
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onStateTransition(StateTransition event);

    /// Handle deployment completed event (dispatched via MessageRouter).
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onDeploymentCompleted(DeploymentCompleted event);

    /// Handle deployment failed event (dispatched via MessageRouter).
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onDeploymentFailed(DeploymentFailed event);

    /// Get all known deployment metrics (local + remote nodes).
    Map<Artifact, List<DeploymentMetrics>> allDeploymentMetrics();

    /// Get deployment metrics for a specific artifact.
    List<DeploymentMetrics> metricsFor(Artifact artifact);

    /// Get in-progress deployments.
    Map<DeploymentKey, DeploymentMetrics> inProgressDeployments();

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onDeploymentMetricsPing(DeploymentMetricsPing ping);

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onDeploymentMetricsPong(DeploymentMetricsPong pong);

    /// Handle topology changes to clean up metrics from departed nodes.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onTopologyChange(TopologyChangeNotification topologyChange);

    /// Collect local metrics as protocol entries for transmission.
    Map<String, List<DeploymentMetricsEntry>> collectLocalEntries();

    /// Key for tracking in-progress deployments.
    record DeploymentKey(Artifact artifact, NodeId nodeId) {}

    /// Create a new DeploymentMetricsCollector instance.
    static DeploymentMetricsCollector deploymentMetricsCollector(NodeId self, ClusterNetwork network) {
        return new DeploymentMetricsCollectorImpl(self, network, DEFAULT_RETENTION_COUNT);
    }

    /// Create a new DeploymentMetricsCollector with custom retention count.
    static DeploymentMetricsCollector deploymentMetricsCollector(NodeId self,
                                                                 ClusterNetwork network,
                                                                 int retentionCount) {
        return new DeploymentMetricsCollectorImpl(self, network, retentionCount);
    }
}

class DeploymentMetricsCollectorImpl implements DeploymentMetricsCollector {
    private static final Logger log = LoggerFactory.getLogger(DeploymentMetricsCollectorImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final int retentionCount;

    // In-progress deployments: (artifact, nodeId) -> metrics
    private final ConcurrentHashMap<DeploymentKey, DeploymentMetrics> inProgress = new ConcurrentHashMap<>();

    // Completed deployments: artifact -> list of metrics (most recent first, limited to retentionCount)
    private final ConcurrentHashMap<Artifact, List<DeploymentMetrics>> completed = new ConcurrentHashMap<>();

    // Remote deployment metrics received from other nodes
    private final ConcurrentHashMap<Artifact, List<DeploymentMetrics>> remoteMetrics = new ConcurrentHashMap<>();

    DeploymentMetricsCollectorImpl(NodeId self, ClusterNetwork network, int retentionCount) {
        this.self = self;
        this.network = network;
        this.retentionCount = retentionCount;
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onDeploymentStarted(DeploymentStarted event) {
        var key = new DeploymentKey(event.artifact(), event.targetNode());
        var metrics = DeploymentMetrics.deploymentMetrics(event.artifact(), event.targetNode(), event.timestamp());
        inProgress.put(key, metrics);
        log.debug("Deployment started: {} on {}", event.artifact(), event.targetNode());
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onStateTransition(StateTransition event) {
        var key = new DeploymentKey(event.artifact(), event.nodeId());
        inProgress.computeIfPresent(key,
                                    (_, metrics) -> updateMetricsForTransition(metrics,
                                                                               event.from(),
                                                                               event.to(),
                                                                               event.timestamp()));
        log.trace("State transition: {} on {} from {} to {}", event.artifact(), event.nodeId(), event.from(), event.to());
    }

    private DeploymentMetrics updateMetricsForTransition(DeploymentMetrics metrics,
                                                         SliceState from,
                                                         SliceState to,
                                                         long timestamp) {
        return switch (to) {
            case LOAD -> metrics.withLoadTime(timestamp);
            case LOADED -> metrics.withLoadedTime(timestamp);
            case ACTIVATE -> metrics.withActivateTime(timestamp);
            default -> metrics;
        };
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onDeploymentCompleted(DeploymentCompleted event) {
        var key = new DeploymentKey(event.artifact(), event.nodeId());
        option(inProgress.remove(key)).onPresent(metrics -> finalizeCompleted(event, metrics));
    }

    private void finalizeCompleted(DeploymentCompleted event, DeploymentMetrics metrics) {
        var completedMetrics = metrics.completed(event.timestamp());
        addToCompleted(event.artifact(), completedMetrics);
        log.info("Deployment completed: {} on {} in {}ms",
                 event.artifact(),
                 event.nodeId(),
                 completedMetrics.fullDeploymentTime());
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onDeploymentFailed(DeploymentFailed event) {
        var key = new DeploymentKey(event.artifact(), event.nodeId());
        option(inProgress.remove(key)).onPresent(metrics -> finalizeFailed(event, metrics));
    }

    private void finalizeFailed(DeploymentFailed event, DeploymentMetrics metrics) {
        var failedMetrics = toFailedMetrics(event, metrics);
        addToCompleted(event.artifact(), failedMetrics);
        log.warn("Deployment failed: {} on {} at state {}", event.artifact(), event.nodeId(), event.failedAt());
    }

    private DeploymentMetrics toFailedMetrics(DeploymentFailed event, DeploymentMetrics metrics) {
        return switch (event.failedAt()) {
            case LOADING -> metrics.failedLoading(event.timestamp());
            case ACTIVATING -> metrics.failedActivating(event.timestamp());
            default -> metrics.failedLoading(event.timestamp());
        };
    }

    private void addToCompleted(Artifact artifact, DeploymentMetrics metrics) {
        completed.compute(artifact, (_, list) -> prependAndTrim(list, metrics));
    }

    private List<DeploymentMetrics> prependAndTrim(List<DeploymentMetrics> existing, DeploymentMetrics metrics) {
        var newList = new ArrayList<>(existing != null
                                      ? existing
                                      : List.<DeploymentMetrics>of());
        newList.addFirst(metrics);
        while (newList.size() > retentionCount) {
            newList.removeLast();
        }
        return List.copyOf(newList);
    }

    @Override
    public Map<Artifact, List<DeploymentMetrics>> allDeploymentMetrics() {
        var result = new HashMap<Artifact, List<DeploymentMetrics>>();
        completed.forEach((artifact, list) -> result.put(artifact, sortByStartTimeDesc(list)));
        remoteMetrics.forEach((artifact, remoteList) -> result.merge(artifact, remoteList, this::mergeMetricsList));
        return result;
    }

    private List<DeploymentMetrics> sortByStartTimeDesc(List<DeploymentMetrics> list) {
        var sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> Long.compare(b.startTime(), a.startTime()));
        return sorted;
    }

    @Override
    public List<DeploymentMetrics> metricsFor(Artifact artifact) {
        var local = completed.getOrDefault(artifact, List.of());
        var remote = remoteMetrics.getOrDefault(artifact, List.of());
        if (remote.isEmpty() && local.isEmpty()) {
            return List.of();
        }
        return mergeMetricsList(local, remote);
    }

    /// Merge two metrics lists, sort by startTime descending, and trim to retention count.
    private List<DeploymentMetrics> mergeMetricsList(List<DeploymentMetrics> first, List<DeploymentMetrics> second) {
        var merged = new ArrayList<>(first);
        merged.addAll(second);
        merged.sort((a, b) -> Long.compare(b.startTime(), a.startTime()));
        return merged.size() > retentionCount
               ? merged.subList(0, retentionCount)
               : merged;
    }

    @Override
    public Map<DeploymentKey, DeploymentMetrics> inProgressDeployments() {
        return Map.copyOf(inProgress);
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onDeploymentMetricsPing(DeploymentMetricsPing ping) {
        // Store sender's metrics (but don't overwrite our own)
        if (!ping.sender()
                 .equals(self)) {
            storeRemoteMetrics(ping.metrics());
        }
        // Respond with our metrics
        network.send(ping.sender(), new DeploymentMetricsPong(self, collectLocalEntries()));
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onDeploymentMetricsPong(DeploymentMetricsPong pong) {
        // Store responder's metrics (but don't overwrite our own)
        if (!pong.sender()
                 .equals(self)) {
            storeRemoteMetrics(pong.metrics());
        }
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onTopologyChange(TopologyChangeNotification topologyChange) {
        if (topologyChange instanceof TopologyChangeNotification.NodeRemoved(NodeId removedNode, _)) {
            // Remove metrics from departed node
            removeMetricsForNode(removedNode);
        }
    }

    private void removeMetricsForNode(NodeId nodeId) {
        removeInProgressForNode(nodeId);
        removeRemoteMetricsForNode(nodeId);
    }

    private void removeInProgressForNode(NodeId nodeId) {
        var toRemove = inProgress.keySet()
                                 .stream()
                                 .filter(key -> key.nodeId()
                                                   .equals(nodeId))
                                 .toList();
        toRemove.forEach(inProgress::remove);
        if (!toRemove.isEmpty()) {
            log.debug("Cleaned up in-progress metrics for departed node {}", nodeId);
        }
    }

    private void removeRemoteMetricsForNode(NodeId nodeId) {
        remoteMetrics.replaceAll((_, metricsList) -> filterOutNode(metricsList, nodeId));
        remoteMetrics.entrySet()
                     .removeIf(e -> e.getValue()
                                     .isEmpty());
    }

    private List<DeploymentMetrics> filterOutNode(List<DeploymentMetrics> metricsList, NodeId nodeId) {
        return metricsList.stream()
                          .filter(m -> !m.nodeId()
                                         .equals(nodeId))
                          .toList();
    }

    private void storeRemoteMetrics(Map<String, List<DeploymentMetricsEntry>> entries) {
        entries.forEach(this::storeRemoteArtifactMetrics);
    }

    private void storeRemoteArtifactMetrics(String artifactStr, List<DeploymentMetricsEntry> entryList) {
        Artifact.artifact(artifactStr)
                .onSuccess(artifact -> storeFilteredMetrics(artifact, entryList));
    }

    private void storeFilteredMetrics(Artifact artifact, List<DeploymentMetricsEntry> entryList) {
        var filteredList = entryList.stream()
                                    .map(DeploymentMetrics::fromEntry)
                                    .flatMap(Option::stream)
                                    .filter(m -> !m.nodeId()
                                                   .equals(self))
                                    .toList();
        if (!filteredList.isEmpty()) {
            remoteMetrics.put(artifact, filteredList);
        }
    }

    @Override
    public Map<String, List<DeploymentMetricsEntry>> collectLocalEntries() {
        var result = new HashMap<String, List<DeploymentMetricsEntry>>();
        completed.forEach((artifact, metricsList) -> result.put(artifact.asString(), toEntries(metricsList)));
        return result;
    }

    private List<DeploymentMetricsEntry> toEntries(List<DeploymentMetrics> metricsList) {
        return metricsList.stream()
                          .map(DeploymentMetrics::toEntry)
                          .toList();
    }
}
