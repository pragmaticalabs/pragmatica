// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.stream.IntStream;

import static org.pragmatica.lang.Option.option;


@SuppressWarnings("JBCT-UTIL-02") public final class CloudProviderSupport {
    private CloudProviderSupport() {}

    public static Promise<List<ProvisionedNode>> provisionVia(ComputeProvider compute, NodeGroupConfig group) {
        var provisions = IntStream.range(0,
                                         group.count()).mapToObj(i -> provisionSingle(compute, group, i))
                                        .toList();
        return Promise.allOf(provisions).flatMap(results -> Result.allOf(results).async());
    }

    public static Promise<ProvisionedNode> provisionOne(ComputeProvider compute, String nodeId, ProvisionSpec spec) {
        return compute.provision(spec).map(info -> toProvisionedNode(nodeId, info));
    }

    public static Promise<Unit> destroyVia(ComputeProvider compute, List<String> nodeIds) {
        var terminations = nodeIds.stream().map(id -> terminateSingle(compute, id))
                                         .toList();
        return Promise.allOf(terminations).flatMap(results -> Result.allOf(results).async())
                            .mapToUnit();
    }

    public static Promise<List<NodeAddress>> addressesVia(ComputeProvider compute, List<String> nodeIds) {
        var lookups = nodeIds.stream().map(id -> lookupAddress(compute, id))
                                    .toList();
        return Promise.allOf(lookups).flatMap(results -> Result.allOf(results).async());
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
        return info.addresses().isEmpty()
              ? ""
              : info.addresses().getFirst();
    }

    public static Option<String> secondAddress(InstanceInfo info) {
        return info.addresses().size() > 1
              ? option(info.addresses().get(1))
              : Option.none();
    }

    private static Promise<ProvisionedNode> provisionSingle(ComputeProvider compute, NodeGroupConfig group, int index) {
        var nodeId = group.sourceName() + "-" + group.role() + "-" + index;
        return buildProvisionSpec(group).async()
                                 .flatMap(compute::provision)
                                 .map(info -> toProvisionedNode(nodeId, info));
    }

    static Result<ProvisionSpec> buildProvisionSpec(NodeGroupConfig group) {
        var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, group.instanceType(), group.role(), group.tags());
        return spec.map(s -> withZonePlacement(s, group.zone()));
    }

    private static ProvisionSpec withZonePlacement(ProvisionSpec spec, String zone) {
        return zone.isEmpty() || "default".equals(zone)
              ? spec
              : spec.withPlacement(PlacementHint.zoneHint(zone));
    }

    private static Promise<Unit> terminateSingle(ComputeProvider compute, String nodeId) {
        return InstanceId.instanceId(nodeId).async()
                                    .flatMap(compute::terminate);
    }

    private static Promise<NodeAddress> lookupAddress(ComputeProvider compute, String nodeId) {
        return InstanceId.instanceId(nodeId).async()
                                    .flatMap(compute::instanceStatus)
                                    .map(info -> toNodeAddress(nodeId, info));
    }
}
