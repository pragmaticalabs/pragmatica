// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public record HetznerComputeProvider(HetznerClient client, HetznerEnvironmentConfig config) implements ComputeProvider {
    private static final Logger log = LoggerFactory.getLogger(HetznerComputeProvider.class);

    public static Result<HetznerComputeProvider> hetznerComputeProvider(HetznerClient client,
                                                                        HetznerEnvironmentConfig config) {
        return success(new HetznerComputeProvider(client, config));
    }

    @Override public Promise<InstanceInfo> provision(InstanceType instanceType) {
        var defaultLabels = buildLabels(config.clusterName().or("unknown"),
                                        "core",
                                        "");
        return client.createServer(buildCreateRequest(config.region(),
                                                      defaultLabels,
                                                      config.userData())).map(HetznerComputeProvider::toInstanceInfo)
                                  .onFailure(HetznerComputeProvider::logProvisionFailureRollbackGap)
                                  .mapError(HetznerComputeProvider::toProvisionError);
    }

    @Override public Promise<InstanceInfo> provision(ProvisionSpec spec) {
        var location = extractLocation(spec.placement());
        var userData = spec.userData().or(config.userData());
        var labels = labelsFor(spec.context());
<<<<<<< HEAD
        return client.createServer(buildCreateRequest(location,
                                                      labels,
                                                      userData)).map(HetznerComputeProvider::toInstanceInfo)
                                  .onFailure(HetznerComputeProvider::logProvisionFailureRollbackGap)
=======
        return client.createServer(buildCreateRequest(location, labels, userData)).map(HetznerComputeProvider::toInstanceInfo)
>>>>>>> e70d861e1 (chore: migrate peglib 0.5.0 -> 0.6.0; absorb formatter/lint deltas)
                                  .mapError(HetznerComputeProvider::toProvisionError);
    }

    /// Rollback acknowledgment for Hetzner provisions. `createServer` is atomic from
    /// the provider's perspective — failure means no server allocated, success means
    /// the server exists with labels and userData applied in the same request. There
    /// is no current post-create step here that can leave an orphan. If a future code
    /// path adds a second-phase call (e.g. attaching a volume / firewall), plumb a real
    /// `deleteServer` rollback through this hook. For now surface a WARN so operators
    /// can correlate provision failures with any orphan that DOES surface.
    private static void logProvisionFailureRollbackGap(Cause cause) {
        log.warn("Hetzner provision failed ({}); no server-side rollback issued because createServer is atomic — relying on caller to retry or sweep",
                 cause.message());
    }

    @Override public Promise<Unit> terminate(InstanceId instanceId) {
        var serverId = parseServerId(instanceId);
        var result = serverId.async().flatMap(this::destroyServer);
        return mapTerminateError(result, instanceId);
    }

    private static Promise<Unit> mapTerminateError(Promise<Unit> result, InstanceId instanceId) {
        return result.mapError(cause -> toTerminateError(instanceId, cause));
    }

    @Override public Promise<List<InstanceInfo>> listInstances() {
        return client.listServers().map(HetznerComputeProvider::toInstanceInfoList)
                                 .mapError(HetznerComputeProvider::toListInstancesError);
    }

    @Override public Promise<Unit> restart(InstanceId id) {
        return parseServerId(id).async().flatMap(this::rebootServer);
    }

    @Override public Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return parseServerId(id).async().flatMap(serverId -> updateLabels(serverId, tags));
    }

    @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return client.listServers(toLabelSelector(translateKeys(tagFilter))).map(HetznerComputeProvider::toInstanceInfoList)
                                 .mapError(HetznerComputeProvider::toListInstancesError);
    }

    @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        var serverId = parseServerId(instanceId);
        return serverId.async().flatMap(this::serverById)
                             .map(HetznerComputeProvider::toInstanceInfo)
                             .mapError(HetznerComputeProvider::toProvisionError);
    }

    private Promise<Unit> destroyServer(long serverId) {
        return client.deleteServer(serverId);
    }

    private Promise<Unit> rebootServer(long serverId) {
        return client.rebootServer(serverId);
    }

    private Promise<Unit> updateLabels(long serverId, Map<String, String> tags) {
        return client.updateServerLabels(serverId, tags);
    }

    private Promise<Server> serverById(long serverId) {
        return client.getServer(serverId);
    }

    private static final CreateServerRequest.PublicNetSpec IPV4_ONLY = new CreateServerRequest.PublicNetSpec(true, false);

    private CreateServerRequest buildCreateRequest(String location, Map<String, String> labels, String userData) {
        var name = generateServerName();
        var serverType = config.serverType();
        var image = config.image();
        var sshKeyIds = config.sshKeyIds();
        var networkIds = config.networkIds();
        var firewallIds = config.firewallIds();
        return CreateServerRequest.createServerRequest(name,
                                                       serverType,
                                                       image,
                                                       sshKeyIds,
                                                       networkIds,
                                                       firewallIds,
                                                       location,
                                                       userData,
                                                       true,
                                                       IPV4_ONLY,
                                                       labels);
    }

    private Map<String, String> labelsFor(ProvisionContext ctx) {
        var clusterLabel = clusterNameOrDefault(ctx);
        var role = ctx.role().isEmpty()
                  ? "core"
                  : ctx.role();
        var base = buildLabels(clusterLabel, role, ctx.sourceName());
        // Hetzner labels use the hyphenated `aether-node-id` (HCloud key regex disallows
        // bare dots in unprefixed keys), while the Docker provider tags with dotted
        // `aether.node-id`. Both flow from the same ProvisionContext.nodeId() field — the
        // dotted↔hyphenated asymmetry is encoded native-side here and at the listInstances
        // translation site (see translateKeys) so the upper layer (NodeLifecycleManager,
        // NODE_ID_TAG = "aether.node-id") stays provider-agnostic.
        ctx.nodeId().onPresent(id -> base.put(NODE_ID_LABEL, id));
        return appendCompatible(base, ctx.extraTags());
    }

    /// Provider-agnostic tag key (matches `NodeLifecycleManager.NODE_ID_TAG`) used by upper
    /// layers — translated here to the Hetzner-native hyphenated form so a terminate-by-NodeId
    /// lookup written as `Map.of("aether.node-id", id)` selects servers whose Hetzner label
    /// was set to `aether-node-id=<id>` in `labelsFor`. Without this rewrite, the dotted upper-
    /// layer key would never match the hyphenated native label and terminate would silently
    /// log "no cloud instance with tag aether.node-id=...".
    static final String UPPER_LAYER_NODE_ID_TAG = "aether.node-id";

    static final String NODE_ID_LABEL = "aether-node-id";

    static Map<String, String> translateKeys(Map<String, String> tagFilter) {
        if (tagFilter.isEmpty() || !tagFilter.containsKey(UPPER_LAYER_NODE_ID_TAG)) {
            return tagFilter;
        }
        var translated = new HashMap<>(tagFilter);
        var value = translated.remove(UPPER_LAYER_NODE_ID_TAG);
        translated.put(NODE_ID_LABEL, value);
        return translated;
    }

    private String clusterNameOrDefault(ProvisionContext ctx) {
        if (!ctx.clusterName().isEmpty()) {return ctx.clusterName();}
        // Fallback: when ClusterConfigValue isn't yet seeded in KV-Store (pre-bootstrap
        // path), source the cluster name from AETHER_CLUSTER_NAME env var so
        // CTM-provisioned Hetzner servers carry a matching `aether-cluster` label.
        // Mirrors `DockerComputeProvider.clusterOrDefault` — closes spec caveat-c.
        var fromEnv = System.getenv("AETHER_CLUSTER_NAME");
        if (fromEnv != null && !fromEnv.isEmpty()) {return fromEnv;}
        return config.clusterName().or("unknown");
    }

    private static Map<String, String> buildLabels(String clusterLabel, String role, String sourceName) {
        var labels = new HashMap<String, String>();
        labels.put("aether-cluster", clusterLabel);
        labels.put("aether-role", role);
        if (!sourceName.isEmpty()) {labels.put("aether-source", sourceName);}
        return labels;
    }

    private static final java.util.regex.Pattern HETZNER_LABEL_KEY_RX = java.util.regex.Pattern.compile("^[a-zA-Z]([a-zA-Z0-9_.-]*[a-zA-Z0-9])?(/[a-zA-Z0-9_.-]+)?$");

    private static final java.util.regex.Pattern HETZNER_LABEL_VALUE_RX = java.util.regex.Pattern.compile("^[a-zA-Z0-9_.-]*$");

    private static Map<String, String> appendCompatible(Map<String, String> base, Map<String, String> extras) {
        if (extras.isEmpty()) {return Map.copyOf(base);}
        var merged = new HashMap<>(base);
        for (var entry : extras.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (key == null || value == null || key.length() > 63 || value.length() > 63 || !HETZNER_LABEL_KEY_RX.matcher(key)
                                                                                                                         .matches() || !HETZNER_LABEL_VALUE_RX.matcher(value)
                                                                                                                                                                      .matches()) {
                log.debug("Dropping non-Hetzner-compatible label {}={} (caller is responsible for delivering this metadata via userData)",
                          key,
                          value);
                continue;
            }
            merged.put(key, value);
        }
        return Map.copyOf(merged);
    }

    private String extractLocation(Option<PlacementHint> placement) {
        return placement.flatMap(HetznerComputeProvider::locationFromHint).or(config.region());
    }

    private static Option<String> locationFromHint(PlacementHint hint) {
        return switch (hint){
            case PlacementHint.ZoneHint zone -> Option.some(zone.zoneName());
            case PlacementHint.HostGroupHint ignored -> logUnsupported("HostGroupHint");
            case PlacementHint.AffinityHint ignored -> logUnsupported("AffinityHint");
            case PlacementHint.AntiAffinityHint ignored -> logUnsupported("AntiAffinityHint");
        };
    }

    private static Option<String> logUnsupported(String hintType) {
        log.debug("Hetzner provider ignoring {} — not yet supported", hintType);
        return Option.empty();
    }

    private static String generateServerName() {
        return "aether-" + UUID.randomUUID().toString()
                                          .substring(0, 8);
    }

    private static Result<Long> parseServerId(InstanceId instanceId) {
        return Number.parseLong(instanceId.value());
    }

    static InstanceInfo toInstanceInfo(Server server) {
        return new InstanceInfo(new InstanceId(String.valueOf(server.id())),
                                mapStatus(server.status()),
                                collectAddresses(server),
                                InstanceType.ON_DEMAND,
                                safeLabels(server));
    }

    private static Map<String, String> safeLabels(Server server) {
        return option(server.labels()).or(Map.of());
    }

    static String toLabelSelector(Map<String, String> tagFilter) {
        return tagFilter.entrySet().stream()
                                 .map(HetznerComputeProvider::toLabelEntry)
                                 .collect(Collectors.joining(","));
    }

    private static String toLabelEntry(Map.Entry<String, String> entry) {
        return entry.getKey() + "=" + entry.getValue();
    }

    private static List<InstanceInfo> toInstanceInfoList(List<Server> servers) {
        return servers.stream().map(HetznerComputeProvider::toInstanceInfo)
                             .toList();
    }

    static InstanceStatus mapStatus(String hetznerStatus) {
        return switch (hetznerStatus){
            case "initializing", "starting", "rebuilding", "migrating" -> InstanceStatus.PROVISIONING;
            case "running" -> InstanceStatus.RUNNING;
            case "stopping", "off", "deleting" -> InstanceStatus.STOPPING;
            default -> InstanceStatus.TERMINATED;
        };
    }

    static List<String> collectAddresses(Server server) {
        var publicIp = publicIpv4(server);
        var privateIps = privateIps(server);
        return Stream.concat(publicIp.stream(), privateIps.stream()).toList();
    }

    private static Option<String> publicIpv4(Server server) {
        return option(server.publicNet()).flatMap(HetznerComputeProvider::ipv4FromPublicNet).map(Server.Ipv4::ip);
    }

    private static Option<Server.Ipv4> ipv4FromPublicNet(Server.PublicNet net) {
        return option(net.ipv4());
    }

    private static List<String> privateIps(Server server) {
        return option(server.privateNet()).map(HetznerComputeProvider::toPrivateIpList).or(List.of());
    }

    private static List<String> toPrivateIpList(List<Server.PrivateNet> nets) {
        return nets.stream().map(Server.PrivateNet::ip)
                          .toList();
    }

    private static EnvironmentError toProvisionError(Cause cause) {
        return EnvironmentError.provisionFailed(new RuntimeException(cause.message()));
    }

    private static EnvironmentError toTerminateError(InstanceId instanceId, Cause cause) {
        return EnvironmentError.terminateFailed(instanceId, new RuntimeException(cause.message()));
    }

    private static EnvironmentError toListInstancesError(Cause cause) {
        return EnvironmentError.listInstancesFailed(new RuntimeException(cause.message()));
    }
}
