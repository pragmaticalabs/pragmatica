// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Encapsulates cloud instance lifecycle operations (provision, terminate, restart).
/// CDM delegates cloud operations through this interface instead of calling ComputeProvider directly.
/// This enables uniform handling across all cloud providers and future NodeAction extensions.
public interface NodeLifecycleManager {
    Promise<ActionResult> executeAction(NodeAction action);
    Promise<InstanceInfo> provisionNode(ProvisionSpec spec);
    Promise<Unit> terminateNode(NodeId nodeId);
    Promise<Unit> restartNode(NodeId nodeId);
    boolean isCloudManaged();

    static NodeLifecycleManager nodeLifecycleManager(Option<ComputeProvider> computeProvider) {
        return new NodeLifecycleManagerRecord(computeProvider);
    }
}

/// Implementation that delegates to an optional ComputeProvider.
/// Uses tag-based instance lookup (aether-node-id) for terminate and restart operations.
record NodeLifecycleManagerRecord(Option<ComputeProvider> computeProvider) implements NodeLifecycleManager {
    private static final Logger log = LoggerFactory.getLogger(NodeLifecycleManagerRecord.class);

    private static final String NODE_ID_TAG = "aether.node-id";

    @Override public Promise<ActionResult> executeAction(NodeAction action) {
        return switch (action){
            case NodeAction.StartNode startNode -> provisionNode(startNode.spec()).map(ActionResult.NodeStarted::new);
            case NodeAction.StopNode stopNode -> terminateNode(stopNode.nodeId()).map(_ -> new ActionResult.NodeStopped(stopNode.nodeId()));
            case NodeAction.RestartNode restartNode -> restartNode(restartNode.nodeId()).map(_ -> new ActionResult.NodeRestarted(restartNode.nodeId()));
            case NodeAction.MigrateSlices _ -> EnvironmentError.operationNotSupported("migrateSlices").promise();
        };
    }

    @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
        return computeProvider.fold(() -> EnvironmentError.operationNotSupported("provisionNode: no ComputeProvider")
                                                                                .promise(),
                                    provider -> {
                                        log.info("Provisioning new instance: size={}, pool={}",
                                                 spec.instanceSize(),
                                                 spec.pool());
                                        return provider.provision(spec);
                                    });
    }

    @Override public Promise<Unit> terminateNode(NodeId nodeId) {
        return computeProvider.fold(() -> EnvironmentError.operationNotSupported("terminateNode: no ComputeProvider")
                                                                                .promise(),
                                    provider -> lookupAndTerminate(provider, nodeId));
    }

    @Override public Promise<Unit> restartNode(NodeId nodeId) {
        return computeProvider.fold(() -> EnvironmentError.operationNotSupported("restartNode: no ComputeProvider")
                                                                                .promise(),
                                    provider -> lookupAndRestart(provider, nodeId));
    }

    @Override public boolean isCloudManaged() {
        return computeProvider.isPresent();
    }

    private Promise<Unit> lookupAndTerminate(ComputeProvider provider, NodeId nodeId) {
        return provider.listInstances(Map.of(NODE_ID_TAG,
                                             nodeId.id())).flatMap(instances -> terminateMatchedInstance(provider,
                                                                                                         nodeId,
                                                                                                         instances))
                                     .onFailure(cause -> log.warn("Failed to look up cloud instance for node {}: {}",
                                                                  nodeId,
                                                                  cause.message()));
    }

    private Promise<Unit> lookupAndRestart(ComputeProvider provider, NodeId nodeId) {
        return provider.listInstances(Map.of(NODE_ID_TAG,
                                             nodeId.id())).flatMap(instances -> restartMatchedInstance(provider,
                                                                                                       nodeId,
                                                                                                       instances))
                                     .onFailure(cause -> log.warn("Failed to look up cloud instance for restart of node {}: {}",
                                                                  nodeId,
                                                                  cause.message()));
    }

    private Promise<Unit> terminateMatchedInstance(ComputeProvider provider,
                                                   NodeId nodeId,
                                                   List<InstanceInfo> instances) {
        if (instances.size() == 1) {
            var instanceId = instances.getFirst().id();
            log.info("Terminating cloud instance {} for node {}", instanceId.value(), nodeId);
            return provider.terminate(instanceId)
                                     .onSuccess(_ -> log.info("Cloud instance {} terminated successfully",
                                                              instanceId.value()));
        }
        return logMismatch("terminate", nodeId, instances.size());
    }

    private Promise<Unit> restartMatchedInstance(ComputeProvider provider,
                                                 NodeId nodeId,
                                                 List<InstanceInfo> instances) {
        if (instances.size() == 1) {
            var instanceId = instances.getFirst().id();
            log.info("Restarting cloud instance {} for node {}", instanceId.value(), nodeId);
            return provider.restart(instanceId)
                                   .onSuccess(_ -> log.info("Cloud instance {} restarted successfully",
                                                            instanceId.value()));
        }
        return logMismatch("restart", nodeId, instances.size());
    }

    private static Promise<Unit> logMismatch(String operation, NodeId nodeId, int count) {
        // Return a FAILURE (not fake success) so CTM's handleTerminationSuccess/Failure path
        // can pick a different candidate instead of believing the node is gone. Without this,
        // compose-fixture nodes that have no cloud-tag match were being "terminated successfully"
        // from CTM's point of view, while remaining alive — scale-down looped terminating the
        // same fixture repeatedly and never picked the actually-provisioned nodes.
        var reason = count == 0
                    ? "no cloud instance with tag " + NODE_ID_TAG + "=" + nodeId.id()
                    : "found " + count + " instances with tag " + NODE_ID_TAG + "=" + nodeId.id() + " (expected 1)";
        log.warn("{} of {} skipped: {}", operation, nodeId.id(), reason);
        return EnvironmentError.operationNotSupported(operation + ": " + reason).promise();
    }
}
