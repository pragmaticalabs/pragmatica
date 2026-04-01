package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeHealth;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.lang.concurrent.CancellableTask;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/// Implementation of ClusterTopologyManager that delegates read-only operations to
/// TopologyObserver and manages cluster size via a NodeReconcilerState state machine.
@SuppressWarnings("JBCT-RET-01") record ClusterTopologyManagerRecord( TopologyObserver observer,
                                                                      NodeLifecycleManager lifecycleManager,
                                                                      AutoHealConfig autoHealConfig,
                                                                      AtomicInteger desiredSizeRef,
                                                                      AtomicReference<NodeReconcilerState> stateRef,
                                                                      AtomicBoolean active,
                                                                      CancellableTask recheckFuture) implements ClusterTopologyManager {
    private static final Logger log = LoggerFactory.getLogger(ClusterTopologyManager.class);
    private static final int MINIMUM_CLUSTER_SIZE = 3;
    private static final int MAX_WAVE_SIZE = 5;

    static ClusterTopologyManagerRecord clusterTopologyManagerRecord(TopologyObserver observer,
                                                                     NodeLifecycleManager lifecycleManager,
                                                                     AutoHealConfig config) {
        return new ClusterTopologyManagerRecord(observer,
                                                lifecycleManager,
                                                config,
                                                new AtomicInteger(observer.clusterSize()),
                                                new AtomicReference<>(new NodeReconcilerState.Inactive("not yet activated")),
                                                new AtomicBoolean(false),
                                                CancellableTask.cancellableTask());
    }

    // --- ClusterTopologyManager interface ---
    @Override public NodeReconcilerState reconcilerState() {
        return stateRef.get();
    }

    @Override public Result<Unit> setDesiredSize(int size) {
        if ( size < MINIMUM_CLUSTER_SIZE) {
        return Causes.cause("Cluster size cannot be below " + MINIMUM_CLUSTER_SIZE + " (quorum requirement)").result();}
        desiredSizeRef.set(size);
        observer.handleSetClusterSize(new TopologyManagementMessage.SetClusterSize(size));
        reconcile();
        return Result.success(unit());
    }

    @Override public int desiredSize() {
        return desiredSizeRef.get();
    }

    @Override public void onNodeReady(NodeId nodeId) {
        if ( stateRef.get() instanceof NodeReconcilerState.Reconciling) {
            log.info("Node {} reached ON_DUTY, checking reconciliation progress", nodeId);
            reconcile();
        }
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onTopologyChange(TopologyChangeNotification topologyChange) {
        if ( !active.get()) {
        return;}
        switch ( topologyChange) {
            case NodeAdded added -> {
                log.info("CTM: Node {} added, triggering reconciliation", added.nodeId());
                reconcile();
            }
            case NodeRemoved removed -> {
                log.info("CTM: Node {} removed, triggering reconciliation", removed.nodeId());
                reconcile();
            }
            case NodeDown down -> {
                log.warn("CTM: Node {} is down, triggering immediate reconciliation", down.nodeId());
                reconcile();
            }
            default -> {}
        }
    }

    @Override public void activate() {
        if ( !active.compareAndSet(false, true)) {
        return;}
        var actual = observer.activeNodeCount();
        var desired = desiredSizeRef.get();
        log.info("CTM: Activated, desired size={}, current active nodes={}", desired, actual);
        // Use readyNodeCount (ON_DUTY nodes tracked by observer) — includes dynamically provisioned
        // nodes that may not be in the local QUIC topology yet (e.g., after leader failover)
        var readyCount = observer.readyNodeCount();
        var effectiveActual = Math.max(actual, readyCount);
        log.info("CTM: Observer sees {} active, {} ready (ON_DUTY)", actual, readyCount);
        // readyCount > 0 means nodes previously registered ON_DUTY — cluster was already formed.
        // readyCount == 0 means initial formation — no nodes have registered yet.
        var clusterWasFormed = readyCount > 0;
        if ( effectiveActual >= desired) {
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Cluster at target size, skipping formation");
        } else




        if ( clusterWasFormed && effectiveActual >= desired - 1) {
            // Leader failover: one node short (the dead leader). Immediate reconciliation.
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Leader failover detected ({}/{}), enabling immediate reconciliation",
                     effectiveActual,
                     desired);
            handleDeficit(effectiveActual, desired);
        } else {
            // Initial formation or significant node loss — wait for nodes to join
            transitionTo(new NodeReconcilerState.Forming(Instant.now()));
            SharedScheduler.schedule(this::checkFormationComplete, autoHealConfig.startupCooldown());
        }
    }

    @Override public void deactivate() {
        if ( !active.compareAndSet(true, false)) {
        return;}
        cancelRecheck();
        transitionTo(new NodeReconcilerState.Inactive("deactivated (not leader)"));
        log.info("CTM: Deactivated");
    }

    @Override public TopologyObserver observer() {
        return observer;
    }

    // --- TopologyManager delegation ---
    @Override public NodeInfo self() {
        return observer.self();
    }

    @Override public Option<NodeInfo> get(NodeId id) {
        return observer.get(id);
    }

    @Override public int clusterSize() {
        return observer.clusterSize();
    }

    @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
        return observer.reverseLookup(socketAddress);
    }

    @Override public Promise<Unit> start() {
        return observer.start();
    }

    @Override public Promise<Unit> stop() {
        deactivate();
        return observer.stop();
    }

    @Override public TimeSpan pingInterval() {
        return observer.pingInterval();
    }

    @Override public TimeSpan helloTimeout() {
        return observer.helloTimeout();
    }

    @Override public Option<TlsConfig> tls() {
        return observer.tls();
    }

    @Override public Option<NodeState> getState(NodeId id) {
        return observer.getState(id);
    }

    @Override public List<NodeId> topology() {
        return observer.topology();
    }

    // --- State machine transitions ---
    private void transitionTo(NodeReconcilerState newState) {
        var previous = stateRef.getAndSet(newState);
        log.info("CTM state: {} -> {}",
                 stateName(previous),
                 stateName(newState));
    }

    private void checkFormationComplete() {
        if ( !active.get()) {
        return;}
        if ( ! (stateRef.get() instanceof NodeReconcilerState.Forming)) {
        return;}
        var actual = observer.activeNodeCount();
        var desired = desiredSizeRef.get();
        if ( actual >= desired) {
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Cluster formation complete ({}/{})", actual, desired);
        } else




        {
            // Cooldown expired but not all nodes joined — transition to CONVERGED
            // to enable provisioning on next reconciliation cycle
            log.info("CTM: Formation cooldown expired, cluster at {}/{}, enabling reconciliation", actual, desired);
            transitionTo(new NodeReconcilerState.Converged());
            handleDeficit(actual, desired);
        }
    }

    private void reconcile() {
        if ( !active.get()) {
        return;}
        var currentState = stateRef.get();
        if ( currentState instanceof NodeReconcilerState.Inactive) {
        return;}
        // FORMING state: only check if formation is complete, never provision
        if ( currentState instanceof NodeReconcilerState.Forming) {
            var actual = observer.activeNodeCount();
            var desired = desiredSizeRef.get();
            if ( actual >= desired) {
                transitionTo(new NodeReconcilerState.Converged());
                log.info("CTM: Cluster formation complete ({}/{})", actual, desired);
            }
            return;
        }
        var actual = observer.activeNodeCount();
        var desired = desiredSizeRef.get();
        if ( actual == desired) {
            cancelRecheck();
            if ( ! (currentState instanceof NodeReconcilerState.Converged)) {
            transitionTo(new NodeReconcilerState.Converged());}
            return;
        }
        if ( actual < desired) {
        handleDeficit(actual, desired);} else
        {
            log.warn("CTM: Cluster has {} nodes, desired {}, excess nodes should be drained manually", actual, desired);
            transitionTo(new NodeReconcilerState.Converged());
        }
    }

    private void handleDeficit(int actual, int desired) {
        // Already reconciling — don't provision again, wait for in-flight to complete
        if ( stateRef.get() instanceof NodeReconcilerState.Reconciling) {
            log.debug("CTM: Already reconciling, waiting for in-flight provisions to complete");
            return;
        }
        var deficit = desired - actual;
        if ( !lifecycleManager.isCloudManaged()) {
            log.debug("CTM: Cluster deficit of {} but no ComputeProvider, cannot auto-provision", deficit);
            transitionTo(new NodeReconcilerState.Reconciling(desired, actual, List.of(), Instant.now()));
            return;
        }
        var batchSize = provisionBatchSize(deficit);
        log.info("CTM: Cluster at {}/{}, provisioning {} replacement(s)", actual, desired, batchSize);
        transitionTo(new NodeReconcilerState.Reconciling(desired, actual, buildInFlightList(batchSize), Instant.now()));
        provisionNodes(batchSize);
        scheduleRecheck();
    }

    private void provisionNodes(int count) {
        for ( var i = 0; i < count; i++) {
        provisionSingleNode();}
    }

    private void provisionSingleNode() {
        var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "default", "core", Map.of()).unwrap();
        lifecycleManager.provisionNode(spec).onSuccess(_ -> log.info("CTM: Node provisioning succeeded"))
                                      .onFailure(cause -> log.warn("CTM: Node provisioning failed: {}",
                                                                   cause.message()));
    }

    private void scheduleRecheck() {
        recheckFuture.set(SharedScheduler.scheduleAtFixedRate(this::reconcile, autoHealConfig.retryInterval()));
    }

    private void cancelRecheck() {
        recheckFuture.cancel();
    }

    private static int provisionBatchSize(int deficit) {
        return switch (deficit) {case 1 -> 1; case 2, 3 -> deficit;default -> Math.min(deficit, MAX_WAVE_SIZE);};
    }

    private static List<NodeReconcilerState.ProvisionAttempt> buildInFlightList(int count) {
        var now = Instant.now();
        var list = new ArrayList<NodeReconcilerState.ProvisionAttempt>(count);
        for ( var i = 0; i < count; i++) {
        list.add(new NodeReconcilerState.ProvisionAttempt(now, 1));}
        return List.copyOf(list);
    }

    private static String stateName(NodeReconcilerState state) {
        return switch (state) {case NodeReconcilerState.Inactive inactive -> "Inactive(" + inactive.reason() + ")";case NodeReconcilerState.Forming _ -> "Forming";case NodeReconcilerState.Converged _ -> "Converged";case NodeReconcilerState.Reconciling r -> "Reconciling(" + r.currentSize() + "/" + r.targetSize() + ")";};
    }
}
