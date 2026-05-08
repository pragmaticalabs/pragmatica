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
        var defaultLabels = buildLabels(config.clusterName().or("unknown"), "core", "");
        return client.createServer(buildCreateRequest(config.region(),
                                                      defaultLabels,
                                                      config.userData())).map(HetznerComputeProvider::toInstanceInfo)
                                  .mapError(HetznerComputeProvider::toProvisionError);
    }

    @Override public Promise<InstanceInfo> provision(ProvisionSpec spec) {
        var location = extractLocation(spec.placement());
        var userData = spec.userData().or(config.userData());
        var labels = labelsFor(spec.context());
        return client.createServer(buildCreateRequest(location,
                                                      labels,
                                                      userData)).map(HetznerComputeProvider::toInstanceInfo)
                                  .mapError(HetznerComputeProvider::toProvisionError);
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
        return client.listServers(toLabelSelector(tagFilter)).map(HetznerComputeProvider::toInstanceInfoList)
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

    /// Derive Hetzner-spec labels from a [ProvisionContext]. Pulls the well-known
    /// fields (cluster, role, source) into Hetzner's `aether-*` dashed naming and
    /// folds in any caller-supplied [ProvisionContext#extraTags] that pass the
    /// Hetzner key/value regex; everything else is logged-and-dropped (the caller
    /// is expected to deliver such metadata via `userData`).
    private Map<String, String> labelsFor(ProvisionContext ctx) {
        var clusterLabel = clusterNameOrDefault(ctx);
        var role = ctx.role().isEmpty()
                  ? "core"
                  : ctx.role();
        return appendCompatible(buildLabels(clusterLabel, role, ctx.sourceName()), ctx.extraTags());
    }

    private String clusterNameOrDefault(ProvisionContext ctx) {
        return ctx.clusterName().isEmpty()
              ? config.clusterName().or("unknown")
              : ctx.clusterName();
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
