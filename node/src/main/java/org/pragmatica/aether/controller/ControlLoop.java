package org.pragmatica.aether.controller;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlContext;
import org.pragmatica.aether.metrics.MetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintValue;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Control loop that runs the ClusterController periodically on the leader node.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Run only on leader node</li>
 *   <li>Periodically evaluate controller with current metrics</li>
 *   <li>Apply scaling decisions by updating blueprints in KVStore</li>
 * </ul>
 */
public interface ControlLoop {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onTopologyChange(TopologyChangeNotification topologyChange);

    /**
     * Register a blueprint for controller management.
     */
    void registerBlueprint(Artifact artifact, int instances);

    /**
     * Unregister a blueprint from controller management.
     */
    void unregisterBlueprint(Artifact artifact);

    /**
     * Stop the control loop.
     */
    void stop();

    static ControlLoop controlLoop(NodeId self,
                                   ClusterController controller,
                                   MetricsCollector metricsCollector,
                                   ClusterNode<KVCommand<AetherKey>> cluster,
                                   long intervalMs) {
        return new ControlLoopImpl(self, controller, metricsCollector, cluster, intervalMs);
    }

    /**
     * Create with default 5-second interval.
     */
    static ControlLoop controlLoop(NodeId self,
                                   ClusterController controller,
                                   MetricsCollector metricsCollector,
                                   ClusterNode<KVCommand<AetherKey>> cluster) {
        return controlLoop(self, controller, metricsCollector, cluster, 5000);
    }
}

class ControlLoopImpl implements ControlLoop {
    private static final Logger log = LoggerFactory.getLogger(ControlLoopImpl.class);

    private final NodeId self;
    private final ClusterController controller;
    private final MetricsCollector metricsCollector;
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final long intervalMs;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                                                                                                      var thread = new Thread(r,
                                                                                                                              "control-loop");
                                                                                                      thread.setDaemon(true);
                                                                                                      return thread;
                                                                                                  });

    private final AtomicReference<ScheduledFuture< ? >> evaluationTask = new AtomicReference<>();
    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());
    private final ConcurrentHashMap<Artifact, ClusterController.Blueprint> blueprints = new ConcurrentHashMap<>();

    ControlLoopImpl(NodeId self,
                    ClusterController controller,
                    MetricsCollector metricsCollector,
                    ClusterNode<KVCommand<AetherKey>> cluster,
                    long intervalMs) {
        this.self = self;
        this.controller = controller;
        this.metricsCollector = metricsCollector;
        this.cluster = cluster;
        this.intervalMs = intervalMs;
    }

    @Override
    public void onLeaderChange(LeaderChange leaderChange) {
        if (leaderChange.localNodeIsLeader()) {
            log.info("Node {} became leader, starting control loop", self);
            startEvaluation();
        }else {
            log.info("Node {} is no longer leader, stopping control loop", self);
            stopEvaluation();
        }
    }

    @Override
    public void onTopologyChange(TopologyChangeNotification topologyChange) {
        switch (topologyChange) {
            case NodeAdded(_, List<NodeId> newTopology) -> topology.set(newTopology);
            case NodeRemoved(_, List<NodeId> newTopology) -> topology.set(newTopology);
            default -> {}
        }
    }

    @Override
    public void registerBlueprint(Artifact artifact, int instances) {
        blueprints.put(artifact, new ClusterController.Blueprint(artifact, instances));
        log.info("Registered blueprint: {} with {} instances", artifact, instances);
    }

    @Override
    public void unregisterBlueprint(Artifact artifact) {
        blueprints.remove(artifact);
        log.info("Unregistered blueprint: {}", artifact);
    }

    @Override
    public void stop() {
        stopEvaluation();
        scheduler.shutdown();
        try{
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread()
                  .interrupt();
        }
    }

    private void startEvaluation() {
        stopEvaluation();
        var task = scheduler.scheduleAtFixedRate(
        this::runEvaluation, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        evaluationTask.set(task);
    }

    private void stopEvaluation() {
        var existing = evaluationTask.getAndSet(null);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    private void runEvaluation() {
        try{
            if (blueprints.isEmpty()) {
                log.trace("No blueprints registered, skipping evaluation");
                return;
            }
            var context = new ControlContext(
            metricsCollector.allMetrics(), Map.copyOf(blueprints), topology.get());
            controller.evaluate(context)
                      .onSuccess(this::applyDecisions)
                      .onFailure(cause -> log.error("Controller evaluation failed: {}",
                                                    cause.message()));
        } catch (Exception e) {
            log.error("Control loop error: {}", e.getMessage(), e);
        }
    }

    private void applyDecisions(ClusterController.ControlDecisions decisions) {
        if (decisions.changes()
                     .isEmpty()) {
            log.trace("No scaling decisions");
            return;
        }
        for (var change : decisions.changes()) {
            applyChange(change);
        }
    }

    private void applyChange(BlueprintChange change) {
        var artifact = change.artifact();
        var currentBlueprint = blueprints.get(artifact);
        if (currentBlueprint == null) {
            log.warn("Blueprint not found for {}, skipping change", artifact);
            return;
        }
        int newInstances = switch (change) {
            case BlueprintChange.ScaleUp(_, int additional) ->
            currentBlueprint.instances() + additional;
            case BlueprintChange.ScaleDown(_, int reduceBy) ->
            Math.max(1, currentBlueprint.instances() - reduceBy);
        };
        if (newInstances == currentBlueprint.instances()) {
            return;
        }
        log.info("Applying scaling decision: {} from {} to {} instances",
                 artifact,
                 currentBlueprint.instances(),
                 newInstances);
        // Update local blueprint
        blueprints.put(artifact, new ClusterController.Blueprint(artifact, newInstances));
        // Update blueprint in KVStore via consensus
        var key = new BlueprintKey(artifact);
        var value = new BlueprintValue(newInstances);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        cluster.apply(List.of(command))
               .onFailure(cause -> log.error("Failed to apply blueprint change: {}",
                                             cause.message()));
    }
}
