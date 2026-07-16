// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.PlacementPolicy;
import org.pragmatica.aether.deployment.cluster.AllocationPool;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.Blueprint;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState.Active;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Allocation/placement seam extracted (move-only) from {@link Active}. Builds the
/// {@link AllocationPool}, resolves a {@link PlacementPolicy} to a target node set, and issues the
/// scale-up/scale-down LOAD/UNLOAD commands that drive desired→current instance reconciliation.
/// Truly-empty-node-first scale-up, round-robin spillover, and min-instance-budget scale-down are all
/// preserved verbatim; community/worker-directive distribution is delegated to
/// {@link CommunityPlacementPlanner}.
record SliceAllocationEngine(Active active) {
    private static final Logger log = LoggerFactory.getLogger(SliceAllocationEngine.class);

    AllocationPool buildAllocationPool() {
        var communityWorkers = active.communityPlanner().buildCommunityWorkerMap();

        return AllocationPool.allocationPool(active.allocatableNodes(),
                                             List.copyOf(active.workerNodes()),
                                             communityWorkers);
    }

    // Fire-and-forget allocation orchestration: dispatches issue*Command sweeps whose outcomes are
    // handled inline; callers (reconcile/scale paths) ignore the return. void is the contract.
    @Contract
    void issueAllocationCommandsWithPlacement(Artifact artifact, int desiredInstances, String placement) {
        var policy = PlacementPolicy.valueOf(placement);
        var pool = buildAllocationPool();
        var targetNodes = pool.nodesForPolicy(policy);

        if (targetNodes.isEmpty()) {
            log.warn("No nodes available for placement {} of {}, falling back to core", placement, artifact);
            issueAllocationCommands(artifact, desiredInstances);

            return;
        }

        if (policy != PlacementPolicy.CORE_ONLY && pool.hasWorkers()) {
            active.communityPlanner().distributeWorkerOrCommunity(artifact, desiredInstances, placement, pool);
        }

        if (policy == PlacementPolicy.CORE_ONLY || (policy == PlacementPolicy.WORKERS_PREFERRED && !pool.hasWorkers()) || policy == PlacementPolicy.ALL) {
            issueAllocationCommands(artifact, desiredInstances);
        }
    }

    // Fire-and-forget allocation orchestration (see issueAllocationCommandsWithPlacement).
    @Contract
    void issueAllocationCommands(Artifact artifact, int desiredInstances) {
        if (hasNoAllocatableNodes(artifact)) {
            return;
        }

        var currentInstances = active.getCurrentInstances(artifact);

        logAllocationAttempt(artifact, desiredInstances, currentInstances);
        issueAdjustmentCommands(artifact, desiredInstances, currentInstances);
    }

    private boolean hasNoAllocatableNodes(Artifact artifact) {
        if (active.allocatableNodes().isEmpty()) {
            log.warn("No allocatable nodes available for allocation of {}", artifact);

            return true;
        }

        return false;
    }

    private void logAllocationAttempt(Artifact artifact, int desiredInstances, List<SliceNodeKey> currentInstances) {
        log.debug("Allocating {} instances of {} (current: {}) across {} allocatable nodes",
                  desiredInstances,
                  artifact,
                  currentInstances.size(),
                  active.allocatableNodes().size());
    }

    private void issueAdjustmentCommands(Artifact artifact, int desiredInstances, List<SliceNodeKey> currentInstances) {
        var currentCount = currentInstances.size();

        if (desiredInstances > currentCount) {
            issueScaleUpCommands(artifact, desiredInstances - currentCount, currentInstances);
        } else if (desiredInstances < currentCount) {
            issueScaleDownCommands(artifact, currentCount - desiredInstances, currentInstances);
        }
    }

    private void issueScaleUpCommands(Artifact artifact, int toAdd, List<SliceNodeKey> existingInstances) {
        var nodes = active.allocatableNodes();

        log.debug("issueScaleUpCommands: artifact={}, toAdd={}, allocatableNodes={}, nodeIds={}",
                  artifact,
                  toAdd,
                  nodes.size(),
                  nodes);
        var nodesWithInstances = existingInstances.stream().map(SliceNodeKey::nodeId).collect(Collectors.toSet());
        var trulyEmptyNodes = findTrulyEmptyNodes();

        log.debug("issueScaleUpCommands: found {} truly empty nodes: {}", trulyEmptyNodes.size(), trulyEmptyNodes);
        var allocated = issueAllocationsForNodes(artifact, toAdd, trulyEmptyNodes);

        log.debug("issueScaleUpCommands: allocated {} instances to truly empty nodes", allocated);
        var remaining = toAdd - allocated;

        if (remaining <= 0) {
            return;
        }

        var emptyForArtifactCount = issueAllocationsForEmptyNodes(artifact, remaining, nodesWithInstances);

        allocated += emptyForArtifactCount;
        log.debug("issueScaleUpCommands: allocated {} instances to nodes without this artifact, remaining={}",
                  emptyForArtifactCount,
                  remaining - emptyForArtifactCount);
        issueRoundRobinAllocations(artifact, toAdd - allocated);
    }

    private Set<NodeId> findTrulyEmptyNodes() {
        var nodesWithAnySlice = active.sliceStates()
                                      .keySet()
                                      .stream()
                                      .map(SliceNodeKey::nodeId)
                                      .collect(Collectors.toSet());

        return active.allocatableNodes()
                     .stream()
                     .filter(node -> !nodesWithAnySlice.contains(node))
                     .collect(Collectors.toSet());
    }

    int issueAllocationsForNodes(Artifact artifact, int toAdd, Set<NodeId> targetNodes) {
        var allocated = 0;

        for (var node : targetNodes) {
            if (allocated >= toAdd) {
                break;
            }

            if (tryAllocate(artifact, node)) {
                allocated++;
            }
        }

        return allocated;
    }

    private int issueAllocationsForEmptyNodes(Artifact artifact, int toAdd, Set<NodeId> nodesWithInstances) {
        var nodes = active.allocatableNodes();
        var nodeCount = nodes.size();

        if (nodeCount == 0) {
            return 0;
        }

        var allocated = 0;

        for (var i = 0; i < nodeCount && allocated < toAdd; i++) {
            var nodeIndex = Math.floorMod(active.allocationIndex().getAndIncrement(),
                                          nodeCount);
            var node = nodes.get(nodeIndex);

            if (!nodesWithInstances.contains(node) && tryAllocate(artifact, node)) {
                allocated++;
            }
        }

        return allocated;
    }

    boolean tryAllocate(Artifact artifact, NodeId node) {
        var sliceKey = SliceNodeKey.sliceNodeKey(artifact, node);
        var alreadyExists = active.sliceStates().containsKey(sliceKey);

        log.debug("tryAllocate: artifact={}, node={}, sliceKey={}, alreadyExists={}",
                  artifact,
                  node,
                  sliceKey,
                  alreadyExists);
        if (!alreadyExists) {
            active.sliceStates().put(sliceKey, SliceState.LOAD);
            active.issueLoadCommand(sliceKey);

            return true;
        }

        return false;
    }

    private void issueRoundRobinAllocations(Artifact artifact, int remaining) {
        if (remaining <= 0) {
            return;
        }

        var nodes = active.allocatableNodes();

        if (nodes.isEmpty()) {
            log.warn("No allocatable nodes for round-robin allocation of {}", artifact);

            return;
        }

        var nodeCount = nodes.size();
        var allocated = 0;
        var attempts = 0;
        var maxAttempts = nodeCount * 2;

        while (allocated < remaining && attempts < maxAttempts) {
            var nodeIndex = Math.floorMod(active.allocationIndex().getAndIncrement(),
                                          nodeCount);
            var node = nodes.get(nodeIndex);

            if (tryAllocate(artifact, node)) {
                allocated++;
            }

            attempts++;
        }

        if (allocated < remaining) {
            log.warn("Could only allocate {} of {} requested instances for {} (not enough nodes without instances)",
                     allocated,
                     remaining,
                     artifact);
        }
    }

    private void issueScaleDownCommands(Artifact artifact, int toRemove, List<SliceNodeKey> existingInstances) {
        var minInstances = Option.option(active.blueprints().get(artifact)).map(Blueprint::minInstances).or(1);
        var activeCount = existingInstances.size();
        var maxRemovable = Math.max(0, activeCount - minInstances);
        var actualRemove = Math.min(toRemove, maxRemovable);

        if (actualRemove < toRemove) {
            log.info("Budget enforcement: capping scale-down of {} from {} to {} (min: {}, active: {})",
                     artifact,
                     toRemove,
                     actualRemove,
                     minInstances,
                     activeCount);
        }

        if (actualRemove == 0) {
            return;
        }

        existingInstances.stream().skip(Math.max(0, activeCount - actualRemove)).forEach(active::issueUnloadCommand);
    }
}
