// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.PROVISION;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
sealed interface BootstrapPhaseProvision {
    record unused() implements BootstrapPhaseProvision {}

    static Result<BootstrapContext> execute(BootstrapContext ctx) {
        ClusterBootstrapOrchestrator.logPhase(PROVISION,
                                              "Provisioning infrastructure for %d source(s)",
                                              ctx.config().sources().size());
        var allNodes = new ArrayList<ProvisionedNode>();
        var clusterName = ctx.config().cluster().name();
        var mgmtPort = ctx.config().operations().ports().management();

        for (var entry : ctx.config().sources().entrySet()) {
            var sourceName = entry.getKey();
            var source = entry.getValue();
            var result = provisionSource(ctx, sourceName, source, mgmtPort, clusterName);

            if (result.isFailure()) {
                return result.map(_ -> ctx);
            }

            var _ = result.onSuccess(allNodes::addAll);
        }

        var updatedState = buildUpdatedState(ctx, allNodes);

        return success(ctx.withNodes(List.copyOf(allNodes)).withState(updatedState));
    }

    private static BootstrapState buildUpdatedState(BootstrapContext ctx, List<ProvisionedNode> allNodes) {
        var state = ctx.state().withProvisionedNodeIds(allNodes.stream().map(ProvisionedNode::nodeId).toList());
        var rawToml = ctx.rawTomlContent();

        for (var entry : ctx.config().sources().entrySet()) {
            var sourceName = entry.getKey();
            var source = entry.getValue();
            var providerName = resolveProviderName(source);

            for (var node : allNodes) {
                if (node.nodeId().startsWith(sourceName + "-")) {
                    state = state.withResource(CreatedResource.ProvisionedVm.provisionedVm(providerName,
                                                                                           node.serverId(),
                                                                                           sourceName,
                                                                                           extractRole(node.nodeId(),
                                                                                                       sourceName)));
                }
            }

            state = stampSourceHandle(state, rawToml, sourceName, source, providerName);
        }

        return state;
    }

    static BootstrapState stampSourceHandle(BootstrapState state,
                                            String rawToml,
                                            String sourceName,
                                            SourceProfile source,
                                            String providerName) {
        if (source.type() != SourceType.CLOUD) {
            return state;
        }

        var envVars = extractEnvVarNames(rawToml, sourceName);

        warnOnUnmappedCredentials(sourceName, providerName, envVars);
        var handle = SourceCleanupHandle.sourceCleanupHandle(providerName, source.region(), envVars);

        return state.withSource(sourceName, handle);
    }

    /// #521 — a cloud source whose `[source.<name>]` stanza yields no `${env:NAME}` credential leaves the
    /// persisted handle without the mapping `aether cluster destroy` needs to re-derive the provisioning
    /// token. Cleanup then falls back to the raw provider env var, which may name a different account.
    /// Say so at bootstrap time rather than discovering it hours later with paid VMs on the line.
    @Contract
    private static void warnOnUnmappedCredentials(String sourceName, String providerName, Map<String, String> envVars) {
        if (!envVars.isEmpty()) {
            return;
        }

        System.err.printf("  WARN: source '%s' (provider %s) records no credential env-var mapping — its "
                         + "[source.%s] stanza declares no credentials = \"${env:NAME}\". Cleanup will fall back "
                         + "to the raw provider env var.%n",
                          sourceName,
                          providerName,
                          sourceName);
    }

    // RET-06: `rawToml` is raw operator TOML content; the null/empty coalesce is parse-boundary handling.
    @SuppressWarnings({"JBCT-PAT-01", "JBCT-RET-06"})
    static Map<String, String> extractEnvVarNames(String rawToml, String sourceName) {
        if (rawToml == null || rawToml.isEmpty()) {
            return Map.of();
        }

        var stanza = extractStanza(rawToml, sourceName);

        if (stanza.isEmpty()) {
            return Map.of();
        }

        var envName = matchCredentialEnvName(stanza);

        if (envName == null) {
            return Map.of();
        }

        var result = new LinkedHashMap<String, String>();

        for (var alias : CREDENTIAL_FIELD_KEYS) {
            result.put(alias, envName);
        }

        return Map.copyOf(result);
    }

    private static String matchCredentialEnvName(String stanza) {
        var pattern = Pattern.compile("(?m)^\\s*credentials\\s*=\\s*\"\\$\\{env:([A-Z_][A-Z0-9_]*)\\}\"");
        var matcher = pattern.matcher(stanza);

        return matcher.find()
               ? matcher.group(1)
               : null;
    }

    /// #521 — the stanza header is derived from the parser's own `SOURCE_PREFIX`, never re-spelled here.
    /// A local literal is exactly how this broke: the CLI mined a plural `[sources.<name>]` while the
    /// grammar is singular `[source.<name>]`, so it matched no real config and every persisted handle
    /// carried an empty `credentialEnvVars`, leaving destroy unable to re-derive the provisioning token.
    /// The header is anchored to the start of a line so a mention inside a comment cannot be mistaken for
    /// the section itself.
    private static String extractStanza(String rawToml, String sourceName) {
        var header = Pattern.compile("(?m)^[ \\t]*\\[" + Pattern.quote(ClusterBootstrapConfigParser.SOURCE_PREFIX + sourceName)
                                    + "][ \\t]*(#.*)?$");
        var matcher = header.matcher(rawToml);

        if (!matcher.find()) {
            return "";
        }

        var headerIndex = matcher.start();
        var after = rawToml.indexOf("\n[", matcher.end());

        return after < 0
               ? rawToml.substring(headerIndex)
               : rawToml.substring(headerIndex, after);
    }

    List<String> CREDENTIAL_FIELD_KEYS = List.of("api_token", "access_key", "credentials_file");

    static String resolveProviderName(SourceProfile source) {
        return source.provider()
                     .map(CloudProviderName::value)
                     .or(source.type().value());
    }

    private static String extractRole(String nodeId, String sourceName) {
        var suffix = nodeId.substring(sourceName.length() + 1);
        var dashIndex = suffix.lastIndexOf('-');

        return dashIndex > 0
               ? suffix.substring(0, dashIndex)
               : suffix;
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<List<ProvisionedNode>> provisionSource(BootstrapContext ctx,
                                                                 String sourceName,
                                                                 SourceProfile source,
                                                                 int managementPort,
                                                                 String clusterName) {
        return switch (source.type()) {
            case CLOUD -> provisionCloudSource(ctx, sourceName, source, clusterName);
            case DOCKER -> provisionDockerSource(sourceName, source, clusterName);
            case SSH -> provisionSshSource(sourceName, source);
            case FORGE -> provisionForgeSource(sourceName, source, managementPort);
        };
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<List<ProvisionedNode>> provisionCloudSource(BootstrapContext ctx,
                                                                      String sourceName,
                                                                      SourceProfile source,
                                                                      String clusterName) {
        var providerName = resolveProviderName(source);
        var sshKeyIds = ctx.sshKeyIdsFor(providerName);
        // Ids from BootstrapPhaseFirewall, applied AT create so the node is never up-and-unfirewalled
        // (§6.2 — a Hetzner server with no firewall association accepts all inbound traffic).
        var firewallIds = ctx.firewallIdsFor(sourceName);

        return ProviderResolver.resolveCloudCompute(source, sshKeyIds, "", clusterName, firewallIds).flatMap(compute -> provisionCloudWithCompute(compute,
                                                                                                                                                  ctx,
                                                                                                                                                  sourceName,
                                                                                                                                                  source,
                                                                                                                                                  clusterName));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<List<ProvisionedNode>> provisionDockerSource(String sourceName,
                                                                       SourceProfile source,
                                                                       String clusterName) {
        return ProviderResolver.resolveDockerCompute().flatMap(compute -> provisionWithCompute(compute,
                                                                                               sourceName,
                                                                                               source,
                                                                                               clusterName));
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"})
    private static Result<List<ProvisionedNode>> provisionWithCompute(ComputeProvider compute,
                                                                      String sourceName,
                                                                      SourceProfile source,
                                                                      String clusterName) {
        var allNodes = new ArrayList<ProvisionedNode>();
        var roleOrder = List.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT);

        for (var role : roleOrder) {
            var roleTable = option(source.roles().get(role));
            var result = roleTable.flatMap(rt -> rt.count())
                                  .map(count -> provisionRoleGroup(compute, sourceName, role, count, source, clusterName));

            if (result.isPresent()) {
                var provisionResult = result.unwrap();

                if (provisionResult.isFailure()) {
                    return provisionResult;
                }

                var _ = provisionResult.onSuccess(allNodes::addAll);
            }
        }

        return success(List.copyOf(allNodes));
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"})
    private static Result<List<ProvisionedNode>> provisionCloudWithCompute(ComputeProvider compute,
                                                                           BootstrapContext ctx,
                                                                           String sourceName,
                                                                           SourceProfile source,
                                                                           String clusterName) {
        var allNodes = new ArrayList<ProvisionedNode>();
        var roleOrder = List.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT);
        var nodeIndex = 0;
        // RFC-0017 stage 4 — cores are provisioned FIRST, so by the time worker/spot user-data is
        // rendered their addresses are known and can be baked at create ("peer set arrives at
        // birth"). Cores themselves get an EMPTY list — they self-assemble via discovery, and an
        // empty list renders no peers flag on either runtime.
        var corePeers = List.<String> of();

        for (var role : roleOrder) {
            var roleTable = option(source.roles().get(role));
            var count = roleTable.flatMap(rt -> rt.count()).or(0);

            if (count == 0) {
                continue;
            }

            var result = provisionCloudRoleGroup(compute,
                                                 ctx,
                                                 sourceName,
                                                 role,
                                                 count,
                                                 source,
                                                 clusterName,
                                                 nodeIndex,
                                                 role == NodeRole.CORE
                                                 ? List.of()
                                                 : corePeers);

            if (result.isFailure()) {
                return result;
            }

            var _ = result.onSuccess(allNodes::addAll);

            if (role == NodeRole.CORE) {
                corePeers = coreThreePartPeers(allNodes,
                                               ctx.config().operations().ports().cluster());
            }

            nodeIndex += count;
        }

        return success(List.copyOf(allNodes));
    }

    /// The 3-part `id:host:port` seed list baked into worker/spot user-data at create — CORE nodes
    /// only. Workers are not consensus members and need only core seeds to join membership; the
    /// peer set is maintained by gossip thereafter. (The removed SSH re-launch pushed the full node
    /// list to everyone; core-only is the RFC-0017 worker model and removes worker-to-worker seed
    /// coupling.)
    static List<String> coreThreePartPeers(List<ProvisionedNode> provisionedSoFar, int clusterPort) {
        return provisionedSoFar.stream()
                               .map(node -> node.nodeId() + ":" + node.publicIp() + ":" + clusterPort)
                               .toList();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<List<ProvisionedNode>> provisionRoleGroup(ComputeProvider compute,
                                                                    String sourceName,
                                                                    NodeRole role,
                                                                    int count,
                                                                    SourceProfile source,
                                                                    String clusterName) {
        logProvisionRole(sourceName, source.type(), role, Option.some(count));
        var instanceType = source.roles().containsKey(role)
                           ? source.roles().get(role).instanceType().or("default")
                           : "default";
        var zone = source.zone().or("default");
        var labels = Map.of("aether-cluster", clusterName, "aether-source", sourceName, "aether-role", role.value());
        var group = NodeGroupConfig.nodeGroupConfig(sourceName, role.value(), count, instanceType, zone, labels);

        return CloudProviderSupport.provisionVia(compute, group).await();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<List<ProvisionedNode>> provisionCloudRoleGroup(ComputeProvider compute,
                                                                         BootstrapContext ctx,
                                                                         String sourceName,
                                                                         NodeRole role,
                                                                         int count,
                                                                         SourceProfile source,
                                                                         String clusterName,
                                                                         int nodeIndexBase,
                                                                         List<String> seedPeers) {
        logProvisionRole(sourceName, source.type(), role, Option.some(count));
        ZoneProvisioner seam = (nodeId, globalIndex, zone) -> provisionOneInZone(compute,
                                                                                 ctx,
                                                                                 sourceName,
                                                                                 source,
                                                                                 role,
                                                                                 clusterName,
                                                                                 nodeId,
                                                                                 globalIndex,
                                                                                 zone,
                                                                                 seedPeers);

        return rotateZonesForRoleGroup(sourceName, role, count, nodeIndexBase, source.effectiveZones(), seam);
    }

    /// Provisions one node into a SPECIFIC zone: builds the spec without placement, applies
    /// the candidate `zone` (empty string → provider default / no placement hint), then
    /// provisions. Returned as a blocking `Result` because the bootstrap phase is synchronous.
    @SuppressWarnings("JBCT-EX-01")
    private static Result<ProvisionedNode> provisionOneInZone(ComputeProvider compute,
                                                              BootstrapContext ctx,
                                                              String sourceName,
                                                              SourceProfile source,
                                                              NodeRole role,
                                                              String clusterName,
                                                              String nodeId,
                                                              int globalIndex,
                                                              String zone,
                                                              List<String> seedPeers) {
        return buildCloudProvisionSpec(ctx, sourceName, source, role, nodeId, globalIndex, clusterName, seedPeers).map(spec -> applyZone(spec,
                                                                                                                                         zone))
                                      .flatMap(spec -> CloudProviderSupport.provisionOne(compute, nodeId, spec).await());
    }

    /// Serial per-role-group zone rotation with a cursor shared across the group's nodes:
    /// once a working zone is found, subsequent nodes start there (known-full zones are not
    /// re-tried for every node). Capacity-unavailable advances the cursor; any other failure
    /// aborts immediately (non-retryable); cursor exhaustion fails with a clear message.
    /// An empty zone list means "single attempt, provider default" (backward-compatible).
    @SuppressWarnings({"JBCT-EX-01", "JBCT-PAT-01"})
    static Result<List<ProvisionedNode>> rotateZonesForRoleGroup(String sourceName,
                                                                 NodeRole role,
                                                                 int count,
                                                                 int nodeIndexBase,
                                                                 List<String> zones,
                                                                 ZoneProvisioner seam) {
        var nodes = new ArrayList<ProvisionedNode>();
        var cursor = new int[]{0};

        for (int i = 0; i < count; i++) {
            var nodeId = sourceName + "-" + role.value() + "-" + i;
            var globalIndex = nodeIndexBase + i;
            var attempt = provisionWithRotation(sourceName, nodeId, globalIndex, zones, cursor, seam);

            if (attempt.isFailure()) {
                return attempt.map(_ -> List.<ProvisionedNode> of());
            }

            var _ = attempt.onSuccess(nodes::add);
        }

        return success(List.copyOf(nodes));
    }

    /// Provisions a single node, rotating from the shared cursor across the candidate zones.
    /// Advances the cursor past capacity-exhausted zones (so the next node skips them) and
    /// leaves it pointing at the zone that succeeded.
    @SuppressWarnings({"JBCT-EX-01", "JBCT-PAT-01"})
    private static Result<ProvisionedNode> provisionWithRotation(String sourceName,
                                                                 String nodeId,
                                                                 int globalIndex,
                                                                 List<String> zones,
                                                                 int[] cursor,
                                                                 ZoneProvisioner seam) {
        if (zones.isEmpty()) {
            return seam.provisionInZone(nodeId, globalIndex, "");
        }

        while (cursor[0]< zones.size()) {
            var zone = zones.get(cursor[0]);
            var attempt = seam.provisionInZone(nodeId, globalIndex, zone);

            if (attempt.isSuccess()) {
                return attempt;
            }

            if (!isCapacityUnavailable(attempt)) {
                return attempt;
            }

            logZoneRotation(nodeId, zone, nextZoneLabel(zones, cursor[0]));
            cursor[0]++;
        }

        return zonesExhausted(sourceName, nodeId, zones);
    }

    private static boolean isCapacityUnavailable(Result<ProvisionedNode> attempt) {
        return attempt.fold(BootstrapPhaseProvision::isCapacityCause, _ -> false);
    }

    private static boolean isCapacityCause(Cause cause) {
        return cause instanceof EnvironmentError.CapacityUnavailable;
    }

    private static String nextZoneLabel(List<String> zones, int currentIndex) {
        var next = currentIndex + 1;

        return next < zones.size()
               ? zones.get(next)
               : "(no more zones)";
    }

    @Contract
    private static void logZoneRotation(String nodeId, String fromZone, String toZone) {
        System.out.printf("  WARN: zone %s capacity-unavailable for %s, retrying in %s%n", fromZone, nodeId, toZone);
    }

    private static Result<ProvisionedNode> zonesExhausted(String sourceName, String nodeId, List<String> zones) {
        return new ZoneRotationError("all configured zones exhausted for source " + sourceName
                                    + " (node " + nodeId
                                    + "): " + String.join(", ", zones)).result();
    }

    record ZoneRotationError(String message) implements Cause {}

    interface ZoneProvisioner {
        Result<ProvisionedNode> provisionInZone(String nodeId, int globalIndex, String zone);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<ProvisionSpec> buildCloudProvisionSpec(BootstrapContext ctx,
                                                                 String sourceName,
                                                                 SourceProfile source,
                                                                 NodeRole role,
                                                                 String nodeId,
                                                                 int nodeIndex,
                                                                 String clusterName,
                                                                 List<String> seedPeers) {
        var instanceType = source.roles().containsKey(role)
                           ? source.roles().get(role).instanceType().or("default")
                           : "default";
        var roleImage = roleImage(source, role);
        var context = ProvisionContext.forBootstrap(clusterName, role.value(), sourceName, nodeId);

        return NodeConfigBuilder.compose(ctx,
                                         source,
                                         nodeIndex,
                                         role,
                                         Option.empty(),
                                         Option.some(ctx.clusterSecret()))
                                .map(composedConfig -> renderUserData(ctx,
                                                                      source,
                                                                      role,
                                                                      nodeId,
                                                                      nodeIndex,
                                                                      clusterName,
                                                                      composedConfig,
                                                                      seedPeers))
                                .flatMap(userData -> ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND,
                                                                                 instanceType,
                                                                                 role.value(),
                                                                                 context)
                                                                  .map(spec -> spec.withUserData(userData))
                                                                  .map(spec -> applyImage(spec, roleImage)));
    }

    /// RFC-0016 W2 — tier-1 per-role image: applies the role's OWN `image` (VM boot image / snapshot
    /// id) to the spec's `imageId` when present, so `ProvisionRequest.resolve` boots the node from the
    /// operator's prepared snapshot for THAT role. Absent → the spec carries no `imageId` and
    /// resolution falls to tier-2 (`[cloud.compute] image`) then the loud stock default. NEVER applies
    /// an empty image, and never a sibling role's image (no cross-role fallback).
    private static ProvisionSpec applyImage(ProvisionSpec spec, Option<String> image) {
        return image.map(spec::withImage)
                    .or(spec);
    }

    private static Option<String> roleImage(SourceProfile source, NodeRole role) {
        return option(source.roles().get(role)).flatMap(RoleSubTable::image);
    }

    private static String renderUserData(BootstrapContext ctx,
                                         SourceProfile source,
                                         NodeRole role,
                                         String nodeId,
                                         int nodeIndex,
                                         String clusterName,
                                         TomlDocument composedConfig,
                                         List<String> seedPeers) {
        return UserDataTemplate.render(ctx.config(),
                                       source,
                                       role,
                                       nodeId,
                                       nodeIndex,
                                       ctx.clusterSecret(),
                                       clusterName,
                                       composedConfig,
                                       ctx.sshPublicKeys(),
                                       seedPeers);
    }

    /// Applies a single candidate zone to a built spec as a placement hint. An empty or
    /// "default" zone means "no placement" — the provider falls back to its default region.
    private static ProvisionSpec applyZone(ProvisionSpec spec, String zone) {
        return zone.isEmpty() || "default".equals(zone)
               ? spec
               : spec.withPlacement(PlacementHint.zoneHint(zone));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<List<ProvisionedNode>> provisionSshSource(String sourceName, SourceProfile source) {
        var nodes = new ArrayList<ProvisionedNode>();

        for (var entry : source.roles().entrySet()) {
            var role = entry.getKey();

            entry.getValue().hosts().onPresent(hosts -> addSshNodes(nodes, sourceName, role, hosts));
        }

        logProvisionRole(sourceName,
                         source.type(),
                         NodeRole.CORE,
                         Option.some(nodes.size()));

        return success(List.copyOf(nodes));
    }

    @Contract
    private static void addSshNodes(List<ProvisionedNode> nodes, String sourceName, NodeRole role, List<String> hosts) {
        for (int i = 0; i < hosts.size(); i++) {
            var nodeId = sourceName + "-" + role.value() + "-" + i;

            nodes.add(ProvisionedNode.provisionedNode(nodeId, "ssh", hosts.get(i)));
        }
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<List<ProvisionedNode>> provisionForgeSource(String sourceName,
                                                                      SourceProfile source,
                                                                      int managementPort) {
        System.out.println("  Forge source: nodes are virtual (in-process via EmberCluster)");
        System.out.println("  Start the forge binary separately: aether forge --config <forge.toml>");
        var nodes = new ArrayList<ProvisionedNode>();
        var counter = 0;
        var roleOrder = List.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT);

        for (var role : roleOrder) {
            var count = option(source.roles().get(role)).flatMap(rt -> rt.count()).or(0);

            for (int i = 0; i < count; i++) {
                var nodeId = sourceName + "-" + role.value() + "-" + i;
                var nodePort = managementPort + counter;

                nodes.add(ProvisionedNode.provisionedNode(nodeId, "forge", "127.0.0.1"));
                counter++;
            }

            if (count > 0) {
                logProvisionRole(sourceName, source.type(), role, Option.some(count));
            }
        }

        return success(List.copyOf(nodes));
    }

    @Contract
    private static void logProvisionRole(String sourceName, SourceType type, NodeRole role, Option<Integer> count) {
        count.onPresent(c -> System.out.printf("  [%s/%s] %s: provisioning %d node(s)%n",
                                               sourceName,
                                               type.value(),
                                               role.value(),
                                               c));
    }
}
