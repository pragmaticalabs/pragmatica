// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;


@SuppressWarnings("JBCT-UTIL-02")
public final class CloudProviderSupport {
    private CloudProviderSupport() {}

    public static Promise<List<ProvisionedNode>> provisionVia(ComputeProvider compute, NodeGroupConfig group) {
        var provisions = IntStream.range(0, group.count()).mapToObj(_ -> provisionSingle(compute, group)).toList();

        return Promise.allOf(provisions).flatMap(CloudProviderSupport::aggregateResults);
    }

    /// The returned node is keyed by `nodeId`, the caller's own attribution key (the bootstrap's `<source>-<role>-<index>` label, parsed back by deploy,
    /// post and the cleanup ledger). It is NOT the provider-side identity: that is the id the caller put in `spec.context()`, which the provider
    /// uses (#1027). [#provisionVia] has no such key and records the planned id itself.
    public static Promise<ProvisionedNode> provisionOne(ComputeProvider compute, String nodeId, ProvisionSpec spec) {
        return compute.provision(spec)
                      .map(info -> toProvisionedNode(nodeId, info));
    }

    public static Promise<Unit> destroyVia(ComputeProvider compute, List<String> nodeIds) {
        var terminations = nodeIds.stream().map(id -> terminateSingle(compute, id)).toList();

        return Promise.allOf(terminations)
                      .flatMap(CloudProviderSupport::aggregateResults)
                      .mapToUnit();
    }

    public static Promise<List<NodeAddress>> addressesVia(ComputeProvider compute, List<String> nodeIds) {
        var lookups = nodeIds.stream().map(id -> lookupAddress(compute, id)).toList();

        return Promise.allOf(lookups).flatMap(CloudProviderSupport::aggregateResults);
    }

    private static <T> Promise<List<T>> aggregateResults(List<Result<T>> results) {
        return Result.allOf(results).async();
    }

    public static ProvisionedNode toProvisionedNode(String nodeId, InstanceInfo info) {
        return ProvisionedNode.provisionedNode(nodeId,
                                               info.id().value(),
                                               firstAddress(info));
    }

    public static NodeAddress toNodeAddress(String nodeId, InstanceInfo info) {
        return NodeAddress.nodeAddress(nodeId, firstAddress(info), secondAddress(info));
    }

    public static String firstAddress(InstanceInfo info) {
        return info.addresses()
                   .isEmpty()
               ? ""
               : info.addresses()
                     .getFirst();
    }

    public static Option<String> secondAddress(InstanceInfo info) {
        return info.addresses()
                   .size() > 1
               ? option(info.addresses().get(1))
               : Option.none();
    }

    private static Promise<ProvisionedNode> provisionSingle(ComputeProvider compute, NodeGroupConfig group) {
        var nodeId = ProvisionContext.mintCloudNodeId();

        return buildProvisionSpec(group, nodeId).async()
                                 .flatMap(compute::provision)
                                 .map(info -> toProvisionedNode(nodeId, info));
    }

    static Result<ProvisionSpec> buildProvisionSpec(NodeGroupConfig group, String nodeId) {
        return provisionSpecFor(group, nodeId, Map.of());
    }

    /// The group's spec for ONE node whose id the caller minted, carrying `extraTags` as native labels. The group
    /// path ([#provisionVia]) is this with no extra tags; a caller that must record the node id before the create
    /// (the apply ledger) mints it first and builds the spec here.
    public static Result<ProvisionSpec> provisionSpecFor(NodeGroupConfig group,
                                                         String nodeId,
                                                         Map<String, String> extraTags) {
        var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND,
                                               group.instanceType(),
                                               group.role(),
                                               toContext(group, nodeId, extraTags));

        return spec.map(s -> withZonePlacement(s, group.zone()));
    }

    private static ProvisionContext toContext(NodeGroupConfig group, String nodeId, Map<String, String> extraTags) {
        var tags = group.tags();
        var clusterName = ClusterName.maybeClusterName(tags.get("aether-cluster"));

        return ProvisionContext.provisionContext(clusterName,
                                                 group.role(),
                                                 group.sourceName(),
                                                 Option.some(nodeId),
                                                 Option.empty(),
                                                 ProvisionContext.DEFAULT_CORE_MAX,
                                                 ProvisionContext.PROVISIONED_BY_BOOTSTRAP,
                                                 extraTags);
    }

    private static ProvisionSpec withZonePlacement(ProvisionSpec spec, String zone) {
        return zone.isEmpty() || "default".equals(zone)
               ? spec
               : spec.withPlacement(PlacementHint.zoneHint(zone));
    }

    private static Promise<Unit> terminateSingle(ComputeProvider compute, String nodeId) {
        return InstanceId.instanceId(nodeId)
                         .async()
                         .flatMap(compute::terminate);
    }

    private static Promise<NodeAddress> lookupAddress(ComputeProvider compute, String nodeId) {
        return InstanceId.instanceId(nodeId)
                         .async()
                         .flatMap(compute::instanceStatus)
                         .map(info -> toNodeAddress(nodeId, info));
    }
}
