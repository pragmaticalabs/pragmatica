// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface NodeLifecycleManager {
    Promise<ActionResult> executeAction(NodeAction action);
    Promise<InstanceInfo> provisionNode(ProvisionSpec spec);
    Promise<Unit> terminateNode(NodeId nodeId);
    Promise<Unit> restartNode(NodeId nodeId);
    boolean isCloudManaged();

    /// RFC-0017 stage 5 — the worker reconciler's ACTUAL-inventory read: instances matching the
    /// upper-layer tag filter (providers translate key conventions at their boundary, see
    /// `NODE_ID_TAG`). Default refusal keeps non-provisioning fakes honest — a fake that
    /// silently returned an empty list would read as "zero workers exist" and trigger phantom
    /// provisioning.
    default Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return EnvironmentError.operationNotSupported("listInstances: no ComputeProvider").promise();
    }

    @Contract
    default void resetProvisionerState(String clusterName) {}

    /// Cap-less construction — no fleet bound is enforced. Retained for callers that provision
    /// against a non-cloud provider (Docker/forge), where an unbounded fleet is not a cost hazard.
    static NodeLifecycleManager nodeLifecycleManager(Option<ComputeProvider> computeProvider) {
        return new NodeLifecycleManagerRecord(computeProvider, Option.empty(), Option.empty());
    }

    /// #298 — construction WITH an operator-set fleet cap. The cap is enforced only when BOTH a
    /// cluster name and a `maxNodes` value are present: the count is scoped by the cluster tag, so
    /// without a name there is no correct scope to count within. A cap configured without a cluster
    /// name is a misconfiguration and is reported at construction rather than silently ignored.
    static NodeLifecycleManager nodeLifecycleManager(Option<ComputeProvider> computeProvider,
                                                     Option<String> clusterName,
                                                     Option<Integer> maxNodes) {
        return new NodeLifecycleManagerRecord(computeProvider, clusterName, maxNodes);
    }
}

record NodeLifecycleManagerRecord(Option<ComputeProvider> computeProvider,
                                  Option<String> clusterName,
                                  Option<Integer> maxNodes) implements NodeLifecycleManager {
    private static final Logger log = LoggerFactory.getLogger(NodeLifecycleManagerRecord.class);
    // Upper-layer canonical tag key for binding a cloud instance to its assigned NodeId.
    // Each ComputeProvider translates this dotted key to its native label/tag convention
    // at the boundary: DockerComputeProvider sets `aether.node-id` as a Docker label
    // (dotted form is valid in Docker), HetznerComputeProvider translates to
    // `aether-node-id` (Hetzner labels use kebab-case per HCloud API). Do not re-introduce
    // direct hyphenated lookups here — provider translation lives inside each provider's
    // listInstances/labelsFor pair so this layer stays provider-agnostic.
    private static final String NODE_ID_TAG = "aether.node-id";
    // #298 — upper-layer canonical cluster tag, translated per-provider exactly like NODE_ID_TAG
    // (HetznerComputeProvider.labelsFor stamps `aether-cluster`). Counting on this tag is what
    // scopes the fleet cap to THIS cluster rather than everything in the cloud account.
    private static final String CLUSTER_TAG = "aether.cluster";

    @Override
    public Promise<ActionResult> executeAction(NodeAction action) {
        return switch (action) {
            case NodeAction.StartNode startNode -> provisionNode(startNode.spec()).map(ActionResult.NodeStarted::new);
            case NodeAction.StopNode stopNode -> terminateNode(stopNode.nodeId()).map(_ -> new ActionResult.NodeStopped(stopNode.nodeId()));
            case NodeAction.RestartNode restartNode -> restartNode(restartNode.nodeId()).map(_ -> new ActionResult.NodeRestarted(restartNode.nodeId()));
            case NodeAction.MigrateSlices _ -> EnvironmentError.operationNotSupported("migrateSlices").promise();
        };
    }

    @Override
    public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
        return computeProvider.fold(() -> EnvironmentError.operationNotSupported("provisionNode: no ComputeProvider").promise(),
                                    provider -> capGuardedProvision(provider, spec));
    }

    /// #298 — the fleet cap gate. Every provisioning path in the system funnels through
    /// `provisionNode`: the CTM auto-heal reconciler, bootstrap, and the CLI wave reprovision. Placing
    /// the guard here (rather than on the `checkQuota` SPI the ticket named) is deliberate, and the
    /// SPI has since been deleted for the reasons that forced the choice: it had no production
    /// consumer, there was no bulk provisioning path for it to guard, and every implementation
    /// returned a status whose `sufficient` flag was unconditionally `true` — so a gate built on it
    /// could never have refused anything.
    ///
    /// With no cap configured this is a straight pass-through and costs nothing.
    private Promise<InstanceInfo> capGuardedProvision(ComputeProvider provider, ProvisionSpec spec) {
        return maxNodes.fold(() -> doProvision(provider, spec), cap -> scopedCapCheck(provider, spec, cap));
    }

    /// A cap needs a scope. Counting every instance the provider can see would over-count a shared
    /// cloud account and refuse legitimate auto-heal, so enforcement requires the cluster tag. A cap
    /// configured without a cluster name is reported loudly — it is a misconfiguration, and going
    /// quiet here would leave an operator believing a bound is in force when none is.
    private Promise<InstanceInfo> scopedCapCheck(ComputeProvider provider, ProvisionSpec spec, int cap) {
        return clusterName.fold(() -> {
                                    log.error("Fleet cap of {} is configured but this node has no cluster name — "
                                             + "the cap CANNOT be enforced and provisioning proceeds unbounded. "
                                             + "Set AETHER_CLUSTER_NAME so the cap has a scope to count within.",
                                              cap);

                                    return doProvision(provider, spec);
                                },
                                name -> countThenProvision(provider, spec, cap, name));
    }

    /// A cap read that FAILS is not permission to provision — an unreachable provider API must not
    /// silently disable the guard, which is the exact shape of a surface that looks wired and has no
    /// effect. The `listInstances` failure propagates and the provision does not happen.
    private Promise<InstanceInfo> countThenProvision(ComputeProvider provider,
                                                     ProvisionSpec spec,
                                                     int cap,
                                                     String name) {
        return provider.listInstances(Map.of(CLUSTER_TAG, name))
                       .flatMap(instances -> enforceCap(provider,
                                                        spec,
                                                        cap,
                                                        instances.size(),
                                                        name));
    }

    private Promise<InstanceInfo> enforceCap(ComputeProvider provider,
                                             ProvisionSpec spec,
                                             int cap,
                                             int observed,
                                             String name) {
        if (observed >= cap) {
            log.warn("Provisioning REFUSED for cluster {}: node cap {} reached ({} already provisioned)",
                     name,
                     cap,
                     observed);

            return EnvironmentError.nodeCapExceeded(name, cap, observed).promise();
        }

        return doProvision(provider, spec);
    }

    private Promise<InstanceInfo> doProvision(ComputeProvider provider, ProvisionSpec spec) {
        log.info("Provisioning new instance: size={}, pool={}", spec.instanceSize(), spec.pool());

        return provider.provision(spec);
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return computeProvider.fold(() -> EnvironmentError.operationNotSupported("listInstances: no ComputeProvider").promise(),
                                    provider -> provider.listInstances(tagFilter));
    }

    @Override
    public Promise<Unit> terminateNode(NodeId nodeId) {
        return computeProvider.fold(() -> EnvironmentError.operationNotSupported("terminateNode: no ComputeProvider").promise(),
                                    provider -> lookupAndTerminate(provider, nodeId));
    }

    @Override
    public Promise<Unit> restartNode(NodeId nodeId) {
        return computeProvider.fold(() -> EnvironmentError.operationNotSupported("restartNode: no ComputeProvider").promise(),
                                    provider -> lookupAndRestart(provider, nodeId));
    }

    @Override
    public boolean isCloudManaged() {
        return computeProvider.isPresent();
    }

    @Contract
    @Override
    public void resetProvisionerState(String clusterName) {
        computeProvider.onPresent(provider -> provider.resetProvisionerState(clusterName));
    }

    private Promise<Unit> lookupAndTerminate(ComputeProvider provider, NodeId nodeId) {
        return provider.listInstances(Map.of(NODE_ID_TAG,
                                             nodeId.id()))
                       .flatMap(instances -> terminateMatchedInstance(provider, nodeId, instances))
                       .onFailure(cause -> log.warn("Failed to look up cloud instance for node {}: {}",
                                                    nodeId,
                                                    cause.message()));
    }

    private Promise<Unit> lookupAndRestart(ComputeProvider provider, NodeId nodeId) {
        return provider.listInstances(Map.of(NODE_ID_TAG,
                                             nodeId.id()))
                       .flatMap(instances -> restartMatchedInstance(provider, nodeId, instances))
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
        var reason = count == 0
                     ? "no cloud instance with tag " + NODE_ID_TAG + "=" + nodeId.id()
                     : "found " + count + " instances with tag " + NODE_ID_TAG + "=" + nodeId.id() + " (expected 1)";

        log.warn("{} of {} skipped: {}", operation, nodeId.id(), reason);

        return EnvironmentError.operationNotSupported(operation + ": " + reason).promise();
    }
}
