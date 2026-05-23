// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.deployment;

import org.pragmatica.aether.slice.delegation.DelegatedComponent;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPing;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.MembershipDecision.NodeDecommissioned;
import org.pragmatica.consensus.topology.MembershipDecision.NodeJoined;
import org.pragmatica.consensus.topology.MembershipDecision.NodeRemoved;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.lang.concurrent.CancellableTask;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface DeploymentMetricsScheduler extends DelegatedComponent {
    TimeSpan DEFAULT_INTERVAL = TimeSpan.timeSpan(5).seconds();

    @MessageReceiver@Contract void onMembershipDecision(MembershipDecision decision);
    @MessageReceiver@Contract void onQuorumStateChange(QuorumStateNotification notification);
    @Contract void stop();

    static DeploymentMetricsScheduler deploymentMetricsScheduler(NodeId self,
                                                                 ClusterNetwork network,
                                                                 DeploymentMetricsCollector collector) {
        return deploymentMetricsScheduler(self, network, collector, DEFAULT_INTERVAL);
    }

    static DeploymentMetricsScheduler deploymentMetricsScheduler(NodeId self,
                                                                 ClusterNetwork network,
                                                                 DeploymentMetricsCollector collector,
                                                                 TimeSpan interval) {
        return new DeploymentMetricsSchedulerImpl(self, network, collector, interval);
    }
}

class DeploymentMetricsSchedulerImpl implements DeploymentMetricsScheduler {
    private static final Logger log = LoggerFactory.getLogger(DeploymentMetricsSchedulerImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final DeploymentMetricsCollector collector;
    private final TimeSpan interval;

    private final CancellableTask pingTask = CancellableTask.cancellableTask();

    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());

    private final AtomicLong quorumSequence = new AtomicLong();

    private final AtomicBoolean active = new AtomicBoolean(false);

    DeploymentMetricsSchedulerImpl(NodeId self,
                                   ClusterNetwork network,
                                   DeploymentMetricsCollector collector,
                                   TimeSpan interval) {
        this.self = self;
        this.network = network;
        this.collector = collector;
        this.interval = interval;
    }

    @Override public Promise<Unit> activate() {
        log.info("Node {} activating deployment metrics scheduler", self);
        active.set(true);
        startPinging();
        return Promise.unitPromise();
    }

    @Override public Promise<Unit> deactivate() {
        log.info("Node {} deactivating deployment metrics scheduler", self);
        active.set(false);
        stopPinging();
        return Promise.unitPromise();
    }

    @Override public TaskGroup taskGroup() {
        return TaskGroup.METRICS;
    }

    @Override public boolean isActive() {
        return active.get();
    }

    @Override@Contract public void onMembershipDecision(MembershipDecision decision) {
        switch (decision){
            case NodeJoined(_, List<NodeId> newTopology, _, _) -> topology.set(newTopology);
            case NodeRemoved(_, List<NodeId> newTopology, _, _) -> topology.set(newTopology);
            case NodeDecommissioned(_, List<NodeId> newTopology, _, _) -> topology.set(newTopology);
            // RC1 Step 2 lifecycle-projection variants: pre-terminal transitions do not
            // change the committed topology shape — schedule unchanged.
            case MembershipDecision.NodeJoining _,
                 MembershipDecision.NodeDraining _,
                 MembershipDecision.NodeFailedDrain _,
                 MembershipDecision.NodeShuttingDown _ -> {}
        }
    }

    @Override@Contract public void onQuorumStateChange(QuorumStateNotification notification) {
        if (!notification.advanceSequence(quorumSequence)) {
            log.debug("Ignoring stale QuorumStateNotification: {}", notification);
            return;
        }
        if (notification.state() == QuorumStateNotification.State.DISAPPEARED) {
            log.info("Quorum disappeared, stopping deployment metrics scheduler");
            stopPinging();
        }
    }

    @Override@Contract public void stop() {
        stopPinging();
    }

    private void startPinging() {
        pingTask.set(SharedScheduler.scheduleAtFixedRate(this::sendPingsToAllNodes, interval));
    }

    private void stopPinging() {
        pingTask.cancel();
    }

    @SuppressWarnings("JBCT-EX-01") private void sendPingsToAllNodes() {
        try {
            var currentTopology = topology.get();
            if (currentTopology.isEmpty()) {return;}
            var localMetrics = collector.collectLocalEntries();
            var ping = new DeploymentMetricsPing(self, localMetrics);
            currentTopology.stream().filter(nodeId -> !nodeId.equals(self))
                                  .forEach(nodeId -> network.send(nodeId, ping));
            log.trace("Sent DeploymentMetricsPing to {} nodes", currentTopology.size() - 1);
        } catch (Exception e) {
            log.warn("Failed to send deployment metrics ping: {}", e.getMessage());
        }
    }
}
