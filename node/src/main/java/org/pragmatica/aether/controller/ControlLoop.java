package org.pragmatica.aether.controller;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlContext;
import org.pragmatica.aether.metrics.MetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.consensus.topology.QuorumStateNotification;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

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
     * Handle blueprint creation/update from KVStore.
     */
    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    /**
     * Handle blueprint removal from KVStore.
     */
    @MessageReceiver
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    /**
     * Handle quorum state changes (stop evaluation when quorum disappears).
     */
    @MessageReceiver
    void onQuorumStateChange(QuorumStateNotification notification);

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
                                   TimeSpan interval) {
        return new ControlLoopImpl(self, controller, metricsCollector, cluster, interval);
    }

    /**
     * Create with default 5-second interval.
     */
    static ControlLoop controlLoop(NodeId self,
                                   ClusterController controller,
                                   MetricsCollector metricsCollector,
                                   ClusterNode<KVCommand<AetherKey>> cluster) {
        return controlLoop(self,
                           controller,
                           metricsCollector,
                           cluster,
                           TimeSpan.timeSpan(5)
                                   .seconds());
    }
}

class ControlLoopImpl implements ControlLoop {
    private static final Logger log = LoggerFactory.getLogger(ControlLoopImpl.class);

    private final NodeId self;
    private final ClusterController controller;
    private final MetricsCollector metricsCollector;
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final TimeSpan interval;

    private final AtomicReference<ScheduledFuture<?>> evaluationTask = new AtomicReference<>();
    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());
    private final ConcurrentHashMap<Artifact, ClusterController.Blueprint> blueprints = new ConcurrentHashMap<>();

    ControlLoopImpl(NodeId self,
                    ClusterController controller,
                    MetricsCollector metricsCollector,
                    ClusterNode<KVCommand<AetherKey>> cluster,
                    TimeSpan interval) {
        this.self = self;
        this.controller = controller;
        this.metricsCollector = metricsCollector;
        this.cluster = cluster;
        this.interval = interval;
    }

    @Override
    public void onLeaderChange(LeaderChange leaderChange) {
        if (leaderChange.localNodeIsLeader()) {
            log.info("Node {} became leader, starting control loop", self);
            startEvaluation();
        } else {
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
    public void onQuorumStateChange(QuorumStateNotification notification) {
        if (notification == QuorumStateNotification.DISAPPEARED) {
            log.info("Quorum disappeared, stopping control loop evaluation");
            stopEvaluation();
        }
    }

    @Override
    public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
        var key = valuePut.cause()
                          .key();
        var value = valuePut.cause()
                            .value();
        if (key instanceof BlueprintKey blueprintKey && value instanceof BlueprintValue blueprintValue) {
            registerBlueprint(blueprintKey.artifact(), (int) blueprintValue.instanceCount());
        }
    }

    @Override
    public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
        var key = valueRemove.cause()
                             .key();
        if (key instanceof BlueprintKey blueprintKey) {
            unregisterBlueprint(blueprintKey.artifact());
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
    }

    private void startEvaluation() {
        stopEvaluation();
        var task = SharedScheduler.scheduleAtFixedRate(this::runEvaluation, interval);
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
            var context = new ControlContext(metricsCollector.allMetrics(), Map.copyOf(blueprints), topology.get());
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
        Option.option(blueprints.get(artifact))
              .onEmpty(() -> log.warn("Blueprint not found for {}, skipping change", artifact))
              .onPresent(currentBlueprint -> applyChangeToBlueprint(change, artifact, currentBlueprint));
    }

    private void applyChangeToBlueprint(BlueprintChange change,
                                        Artifact artifact,
                                        ClusterController.Blueprint currentBlueprint) {
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
