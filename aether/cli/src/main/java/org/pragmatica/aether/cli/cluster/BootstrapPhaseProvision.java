// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.PROVISION;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) sealed interface BootstrapPhaseProvision {
    record unused() implements BootstrapPhaseProvision{}

    static Result<BootstrapContext> execute(BootstrapContext ctx) {
        ClusterBootstrapOrchestrator.logPhase(PROVISION,
                                              "Provisioning infrastructure for %d source(s)",
                                              ctx.config().sources()
                                                        .size());
        var allNodes = new ArrayList<ProvisionedNode>();
        var clusterName = ctx.config().cluster()
                                    .name();
        var mgmtPort = ctx.config().operations()
                                 .ports()
                                 .management();
        for (var entry : ctx.config().sources()
                                   .entrySet()) {
            var sourceName = entry.getKey();
            var source = entry.getValue();
            var result = provisionSource(ctx, sourceName, source, mgmtPort, clusterName);
            if (result.isFailure()) {return result.map(_ -> ctx);}
            var _ = result.onSuccess(allNodes::addAll);
        }
        var updatedState = buildUpdatedState(ctx, allNodes);
        return success(ctx.withNodes(List.copyOf(allNodes)).withState(updatedState));
    }

    private static BootstrapState buildUpdatedState(BootstrapContext ctx, List<ProvisionedNode> allNodes) {
        var state = ctx.state().withProvisionedNodeIds(allNodes.stream().map(ProvisionedNode::nodeId)
                                                                      .toList());
        for (var entry : ctx.config().sources()
                                   .entrySet()) {
            var sourceName = entry.getKey();
            var source = entry.getValue();
            var providerName = resolveProviderName(source);
            for (var node : allNodes) {if (node.nodeId().startsWith(sourceName + "-")) {state = state.withResource(CreatedResource.ProvisionedVm.provisionedVm(providerName,
                                                                                                                                                               node.nodeId(),
                                                                                                                                                               sourceName,
                                                                                                                                                               extractRole(node.nodeId(),
                                                                                                                                                                           sourceName)));}}
        }
        return state;
    }

    static String resolveProviderName(SourceProfile source) {
        return source.provider().map(CloudProviderName::value)
                              .or(source.type().value());
    }

    private static String extractRole(String nodeId, String sourceName) {
        var suffix = nodeId.substring(sourceName.length() + 1);
        var dashIndex = suffix.lastIndexOf('-');
        return dashIndex > 0
              ? suffix.substring(0, dashIndex)
              : suffix;
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<List<ProvisionedNode>> provisionSource(BootstrapContext ctx,
                                                                                                  String sourceName,
                                                                                                  SourceProfile source,
                                                                                                  int managementPort,
                                                                                                  String clusterName) {
        return switch (source.type()){
            case CLOUD -> provisionCloudSource(ctx, sourceName, source, clusterName);
            case DOCKER -> provisionDockerSource(sourceName, source, clusterName);
            case SSH -> provisionSshSource(sourceName, source);
            case FORGE -> provisionForgeSource(sourceName, source, managementPort);
        };
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<List<ProvisionedNode>> provisionCloudSource(BootstrapContext ctx,
                                                                                                       String sourceName,
                                                                                                       SourceProfile source,
                                                                                                       String clusterName) {
        var providerName = resolveProviderName(source);
        var sshKeyIds = ctx.sshKeyIdsFor(providerName);
        return ProviderResolver.resolveCloudCompute(source, sshKeyIds, "")
                                                   .flatMap(compute -> provisionCloudWithCompute(compute,
                                                                                                 ctx,
                                                                                                 sourceName,
                                                                                                 source,
                                                                                                 clusterName));
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<List<ProvisionedNode>> provisionDockerSource(String sourceName,
                                                                                                        SourceProfile source,
                                                                                                        String clusterName) {
        return ProviderResolver.resolveDockerCompute()
                                                    .flatMap(compute -> provisionWithCompute(compute,
                                                                                             sourceName,
                                                                                             source,
                                                                                             clusterName));
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"}) private static Result<List<ProvisionedNode>> provisionWithCompute(ComputeProvider compute,
                                                                                                                       String sourceName,
                                                                                                                       SourceProfile source,
                                                                                                                       String clusterName) {
        var allNodes = new ArrayList<ProvisionedNode>();
        var roleOrder = List.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT);
        for (var role : roleOrder) {
            var roleTable = option(source.roles().get(role));
            var result = roleTable.flatMap(rt -> rt.count())
                                          .map(count -> provisionRoleGroup(compute,
                                                                           sourceName,
                                                                           role,
                                                                           count,
                                                                           source,
                                                                           clusterName));
            if (result.isPresent()) {
                var provisionResult = result.unwrap();
                if (provisionResult.isFailure()) {return provisionResult;}
                var _ = provisionResult.onSuccess(allNodes::addAll);
            }
        }
        return success(List.copyOf(allNodes));
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"}) private static Result<List<ProvisionedNode>> provisionCloudWithCompute(ComputeProvider compute,
                                                                                                                            BootstrapContext ctx,
                                                                                                                            String sourceName,
                                                                                                                            SourceProfile source,
                                                                                                                            String clusterName) {
        var allNodes = new ArrayList<ProvisionedNode>();
        var roleOrder = List.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT);
        var nodeIndex = 0;
        for (var role : roleOrder) {
            var roleTable = option(source.roles().get(role));
            var count = roleTable.flatMap(rt -> rt.count()).or(0);
            if (count == 0) {continue;}
            var result = provisionCloudRoleGroup(compute, ctx, sourceName, role, count, source, clusterName, nodeIndex);
            if (result.isFailure()) {return result;}
            var _ = result.onSuccess(allNodes::addAll);
            nodeIndex += count;
        }
        return success(List.copyOf(allNodes));
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<List<ProvisionedNode>> provisionRoleGroup(ComputeProvider compute,
                                                                                                    String sourceName,
                                                                                                    NodeRole role,
                                                                                                    int count,
                                                                                                    SourceProfile source,
                                                                                                    String clusterName) {
        logProvisionRole(sourceName, source.type(), role, Option.some(count));
        var instanceType = source.roles().containsKey(role)
                          ? source.roles().get(role)
                                        .instanceType()
                                        .or("default")
                          : "default";
        var zone = source.zone().or("default");
        var labels = Map.of("aether-cluster", clusterName, "aether-source", sourceName, "aether-role", role.value());
        var group = NodeGroupConfig.nodeGroupConfig(sourceName, role.value(), count, instanceType, zone, labels);
        return CloudProviderSupport.provisionVia(compute, group).await();
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<List<ProvisionedNode>> provisionCloudRoleGroup(ComputeProvider compute,
                                                                                                         BootstrapContext ctx,
                                                                                                         String sourceName,
                                                                                                         NodeRole role,
                                                                                                         int count,
                                                                                                         SourceProfile source,
                                                                                                         String clusterName,
                                                                                                         int nodeIndexBase) {
        logProvisionRole(sourceName, source.type(), role, Option.some(count));
        var nodes = new ArrayList<ProvisionedNode>();
        for (int i = 0;i <count;i++) {
            var nodeId = sourceName + "-" + role.value() + "-" + i;
            var globalIndex = nodeIndexBase + i;
            var specResult = buildCloudProvisionSpec(ctx, sourceName, source, role, nodeId, globalIndex, clusterName);
            if (specResult.isFailure()) {return specResult.map(_ -> List.<ProvisionedNode>of());}
            var provisionResult = CloudProviderSupport.provisionOne(compute, nodeId, specResult.unwrap()).await();
            if (provisionResult.isFailure()) {return provisionResult.map(_ -> List.<ProvisionedNode>of());}
            var _ = provisionResult.onSuccess(nodes::add);
        }
        return success(List.copyOf(nodes));
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<ProvisionSpec> buildCloudProvisionSpec(BootstrapContext ctx,
                                                                                                 String sourceName,
                                                                                                 SourceProfile source,
                                                                                                 NodeRole role,
                                                                                                 String nodeId,
                                                                                                 int nodeIndex,
                                                                                                 String clusterName) {
        var instanceType = source.roles().containsKey(role)
                          ? source.roles().get(role)
                                        .instanceType()
                                        .or("default")
                          : "default";
        var zone = source.zone().or("default");
        var labels = Map.of("aether-cluster", clusterName, "aether-source", sourceName, "aether-role", role.value());
        return NodeConfigBuilder.compose(ctx,
                                         source,
                                         nodeId,
                                         nodeIndex,
                                         role,
                                         List.of(),
                                         Option.empty(),
                                         Option.some(ctx.clusterSecret())).map(composedConfig -> renderUserData(ctx,
                                                                                                                source,
                                                                                                                role,
                                                                                                                nodeId,
                                                                                                                nodeIndex,
                                                                                                                clusterName,
                                                                                                                composedConfig))
                                        .flatMap(userData -> ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND,
                                                                                         instanceType,
                                                                                         role.value(),
                                                                                         labels).map(spec -> applyZone(spec,
                                                                                                                       zone))
                                                                                        .map(spec -> spec.withUserData(userData)));
    }

    private static String renderUserData(BootstrapContext ctx,
                                         SourceProfile source,
                                         NodeRole role,
                                         String nodeId,
                                         int nodeIndex,
                                         String clusterName,
                                         TomlDocument composedConfig) {
        return UserDataTemplate.render(ctx.config(),
                                       source,
                                       role,
                                       nodeId,
                                       nodeIndex,
                                       ctx.clusterSecret(),
                                       clusterName,
                                       composedConfig,
                                       ctx.sshPublicKeys());
    }

    private static ProvisionSpec applyZone(ProvisionSpec spec, String zone) {
        return zone.isEmpty() || "default".equals(zone)
              ? spec
              : spec.withPlacement(PlacementHint.zoneHint(zone));
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<List<ProvisionedNode>> provisionSshSource(String sourceName,
                                                                                                     SourceProfile source) {
        var nodes = new ArrayList<ProvisionedNode>();
        for (var entry : source.roles().entrySet()) {
            var role = entry.getKey();
            entry.getValue().hosts()
                          .onPresent(hosts -> addSshNodes(nodes, sourceName, role, hosts));
        }
        logProvisionRole(sourceName,
                         source.type(),
                         NodeRole.CORE,
                         Option.some(nodes.size()));
        return success(List.copyOf(nodes));
    }

    @Contract private static void addSshNodes(List<ProvisionedNode> nodes,
                                              String sourceName,
                                              NodeRole role,
                                              List<String> hosts) {
        for (int i = 0;i <hosts.size();i++) {
            var nodeId = sourceName + "-" + role.value() + "-" + i;
            nodes.add(ProvisionedNode.provisionedNode(nodeId, "ssh", hosts.get(i)));
        }
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<List<ProvisionedNode>> provisionForgeSource(String sourceName,
                                                                                                       SourceProfile source,
                                                                                                       int managementPort) {
        System.out.println("  Forge source: nodes are virtual (in-process via EmberCluster)");
        System.out.println("  Start the forge binary separately: aether forge --config <forge.toml>");
        var nodes = new ArrayList<ProvisionedNode>();
        var counter = 0;
        var roleOrder = List.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT);
        for (var role : roleOrder) {
            var count = option(source.roles().get(role)).flatMap(rt -> rt.count()).or(0);
            for (int i = 0;i <count;i++) {
                var nodeId = sourceName + "-" + role.value() + "-" + i;
                var nodePort = managementPort + counter;
                nodes.add(ProvisionedNode.provisionedNode(nodeId, "forge", "127.0.0.1"));
                counter++;
            }
            if (count > 0) {logProvisionRole(sourceName, source.type(), role, Option.some(count));}
        }
        return success(List.copyOf(nodes));
    }

    @Contract private static void logProvisionRole(String sourceName,
                                                   SourceType type,
                                                   NodeRole role,
                                                   Option<Integer> count) {
        count.onPresent(c -> System.out.printf("  [%s/%s] %s: provisioning %d node(s)%n",
                                               sourceName,
                                               type.value(),
                                               role.value(),
                                               c));
    }
}
