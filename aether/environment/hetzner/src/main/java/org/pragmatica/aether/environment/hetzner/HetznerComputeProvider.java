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
import org.pragmatica.aether.environment.ReadinessPolicy;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerError;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.cloud.hetzner.api.SshKey;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.IdGenerator;

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

    @Override
    public Promise<InstanceInfo> provision(InstanceType instanceType) {
        var cluster = config.clusterName().or("unknown");
        var labels = buildLabels(cluster, "core", "");

        labels.put(NODE_ID_LABEL,
                   IdGenerator.generate(ProvisionContext.coreNodeNamePrefix(cluster)));

        return provisionServer(config.region(), "", labels, config.userData());
    }

    @Override
    public Promise<InstanceInfo> provision(ProvisionSpec spec) {
        var location = extractLocation(spec.placement());
        var userData = spec.userData().or(config.userData());
        var labels = labelsFor(spec.context());

        return provisionServer(location, spec.instanceSize(), labels, userData);
    }

    /// Provisions a server, resolving the two params that historically diverged between the CLI
    /// bootstrap and the CTM auto-heal paths (#442): the server type and the SSH key ids.
    ///
    /// Server type honors the caller's [ProvisionSpec#instanceSize] when it is a concrete type
    /// (the per-role `instance_type` bootstrap threads through), otherwise falls back to the
    /// provider's [HetznerEnvironmentConfig#serverType] (the leader's runtime `[cloud.compute]
    /// server_type`, which auto-heal relies on). When NEITHER resolves the provision fails loud —
    /// there is no hardcoded instance-type default.
    private Promise<InstanceInfo> provisionServer(String location,
                                                  String serverTypeHint,
                                                  Map<String, String> labels,
                                                  String userData) {
        return resolveServerType(serverTypeHint).async()
                                .flatMap(serverType -> createAndConfirm(location, serverType, labels, userData))
                                .mapError(cause -> toProvisionError(location, cause));
    }

    private Promise<InstanceInfo> createAndConfirm(String location,
                                                   String serverType,
                                                   Map<String, String> labels,
                                                   String userData) {
        return resolveSshKeyIds().map(sshKeyIds -> buildCreateRequest(location, serverType, sshKeyIds, labels, userData))
                               .flatMap(client::createServer)
                               .map(HetznerComputeProvider::toInstanceInfo)
                               .flatMap(info -> confirmRunning(info,
                                                               ReadinessPolicy.cloudDefault()))
                               .onFailure(HetznerComputeProvider::logProvisionFailureRollbackGap);
    }

    /// Rollback acknowledgment for Hetzner provisions. `createServer` is atomic from
    /// the provider's perspective — failure means no server allocated, success means
    /// the server exists with labels and userData applied in the same request. Post-create
    /// readiness confirmation (confirmRunning, infra-readiness only) can now surface a
    /// server that never reached RUNNING; that orphan IS a real resource and its cleanup
    /// is owned at a higher layer (CTM auto-heal deleteServer). Surface a WARN so
    /// operators can correlate provision failures with any orphan that DOES surface.
    private static void logProvisionFailureRollbackGap(Cause cause) {
        log.warn("Hetzner provision failed ({}); no server-side rollback issued because createServer is atomic — relying on caller to retry or sweep",
                 cause.message());
    }

    @Override
    public Promise<Unit> terminate(InstanceId instanceId) {
        var serverId = parseServerId(instanceId);
        var result = serverId.async().flatMap(this::destroyServer);

        return mapTerminateError(result, instanceId);
    }

    private static Promise<Unit> mapTerminateError(Promise<Unit> result, InstanceId instanceId) {
        return result.mapError(cause -> toTerminateError(instanceId, cause));
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances() {
        return client.listServers()
                     .map(HetznerComputeProvider::toInstanceInfoList)
                     .mapError(HetznerComputeProvider::toListInstancesError);
    }

    @Override
    public Promise<Unit> restart(InstanceId id) {
        return parseServerId(id).async()
                            .flatMap(this::rebootServer);
    }

    @Override
    public Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return parseServerId(id).async()
                            .flatMap(serverId -> updateLabels(serverId, tags));
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return client.listServers(toLabelSelector(translateKeys(tagFilter)))
                     .map(HetznerComputeProvider::toInstanceInfoList)
                     .mapError(HetznerComputeProvider::toListInstancesError);
    }

    @Override
    public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        var serverId = parseServerId(instanceId);

        return serverId.async()
                       .flatMap(this::serverById)
                       .map(HetznerComputeProvider::toInstanceInfo)
                       .mapError(cause -> toProvisionError("", cause));
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

    /// Sentinel the upper layers pass in [ProvisionSpec#instanceSize] when they carry no concrete
    /// type (CTM auto-heal always passes it; bootstrap passes it for roles with no `instance_type`).
    /// Treated as "unset" so resolution falls through to the provider config.
    static final String DEFAULT_INSTANCE_SIZE_SENTINEL = "default";
    /// Bootstrap uploads operator SSH keys to Hetzner named with this prefix and passes their ids at
    /// create time, so bootstrap VMs never get a root password. The ids live only in the bootstrap
    /// CLI process — so a leader provisioning an auto-heal replacement re-derives them here by listing
    /// the account's keys and matching this prefix (#442, option 3B: no persisted state, self-healing
    /// against deleted/recreated keys). Mirrors `BootstrapPhaseSshKey.HETZNER_KEY_NAME_PREFIX` — the
    /// `aether-cli` constant cannot be imported from this provider module.
    static final String BOOTSTRAP_KEY_NAME_PREFIX = "aether-bootstrap";

    private static final Cause NO_SERVER_TYPE = Causes.cause("No Hetzner server type resolved: neither the provision spec instance type nor the provider's "
                                                            + "[cloud.compute] server_type is set. Set instance_type on the source's core role (bootstrap) or "
                                                            + "server_type in the node cloud config so auto-heal replacements inherit the cluster's type.");

    private Result<String> resolveServerType(String specHint) {
        if (isConcreteType(specHint)) {
            return success(specHint);
        }

        var configured = config.serverType();

        return isConcreteType(configured)
               ? success(configured)
               : NO_SERVER_TYPE.result();
    }

    private static boolean isConcreteType(String value) {
        return value != null
               && !value.isBlank()
               && !DEFAULT_INSTANCE_SIZE_SENTINEL.equals(value);
    }

    private Promise<List<Long>> resolveSshKeyIds() {
        if (!config.sshKeyIds().isEmpty()) {
            log.info("Hetzner provision: SSH key ids resolved from node config (branch=config, count={}) — replacement inherits the cluster's keys",
                     config.sshKeyIds().size());

            return Promise.success(config.sshKeyIds());
        }

        return client.listSshKeys()
                     .map(HetznerComputeProvider::bootstrapKeyIds)
                     .onSuccess(HetznerComputeProvider::logPrefixBranch)
                     .recover(HetznerComputeProvider::sshKeyLookupUnavailable);
    }

    private static List<Long> bootstrapKeyIds(List<SshKey> keys) {
        return keys.stream()
                   .filter(HetznerComputeProvider::isBootstrapKey)
                   .map(SshKey::id)
                   .toList();
    }

    private static boolean isBootstrapKey(SshKey key) {
        return key.name() != null && key.name()
                                        .startsWith(BOOTSTRAP_KEY_NAME_PREFIX);
    }

    /// #442 — one operator-diagnosable line naming which branch resolved the ssh-key ids the
    /// replacement was created with. `branch=prefix` when the account carries `aether-bootstrap*`
    /// keys (the fresh-upload environment), `branch=none` when it carries none — the exact signal
    /// missing from the field run where a keyless replacement hit the PAM wall and the cause could
    /// not be told apart from a lookup failure ([#sshKeyLookupUnavailable], `branch=failed`).
    private static void logPrefixBranch(List<Long> ids) {
        if (ids.isEmpty()) {
            log.warn("Hetzner provision: no SSH keys named '{}*' on the account (branch=none); replacement created without an "
                    + "ssh_keys param (Hetzner sets a root password; key auth via cloud-init still works)",
                     BOOTSTRAP_KEY_NAME_PREFIX);

            return;
        }

        log.warn("Hetzner provision: SSH key ids resolved by '{}*' name-prefix match (branch=prefix, count={})",
                 BOOTSTRAP_KEY_NAME_PREFIX,
                 ids.size());
    }

    private static List<Long> sshKeyLookupUnavailable(Cause cause) {
        log.warn("Hetzner provision: SSH-key lookup failed (branch=failed: {}); creating the replacement without an ssh_keys param "
                + "(key auth via cloud-init still works, but a root password will be set)",
                 cause.message());

        return List.of();
    }

    private CreateServerRequest buildCreateRequest(String location,
                                                   String serverType,
                                                   List<Long> sshKeyIds,
                                                   Map<String, String> labels,
                                                   String userData) {
        var name = generateServerName();
        var image = config.image();
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
        // `aether.node-id`. Both flow from the same ProvisionContext node-id field — the
        // dotted↔hyphenated asymmetry is encoded native-side here and at the listInstances
        // translation site (see translateKeys) so the upper layer (NodeLifecycleManager,
        // NODE_ID_TAG = "aether.node-id") stays provider-agnostic. The provider OWNS the
        // identity: resolveNodeId() honors a caller-supplied id (bootstrap) or self-mints
        // one (CTM auto-heal), then echoes it back into InstanceInfo.nodeId via the label.
        base.put(NODE_ID_LABEL, ctx.resolveNodeId());

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
        if (!ctx.clusterName().isEmpty()) {
            return ctx.clusterName();
        }
        // Fallback: when ClusterConfigValue isn't yet seeded in KV-Store (pre-bootstrap
        // path), source the cluster name from AETHER_CLUSTER_NAME env var so
        // CTM-provisioned Hetzner servers carry a matching `aether-cluster` label.
        // Mirrors `DockerComputeProvider.clusterOrDefault` — closes spec caveat-c.
        var fromEnv = System.getenv("AETHER_CLUSTER_NAME");

        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }

        return config.clusterName()
                     .or("unknown");
    }

    private static Map<String, String> buildLabels(String clusterLabel, String role, String sourceName) {
        var labels = new HashMap<String, String>();

        labels.put("aether-cluster", clusterLabel);
        labels.put("aether-role", role);
        if (!sourceName.isEmpty()) {
            labels.put("aether-source", sourceName);
        }

        return labels;
    }

    private static final java.util.regex.Pattern HETZNER_LABEL_KEY_RX = java.util.regex.Pattern.compile("^[a-zA-Z]([a-zA-Z0-9_.-]*[a-zA-Z0-9])?(/[a-zA-Z0-9_.-]+)?$");

    private static final java.util.regex.Pattern HETZNER_LABEL_VALUE_RX = java.util.regex.Pattern.compile("^[a-zA-Z0-9_.-]*$");

    private static Map<String, String> appendCompatible(Map<String, String> base, Map<String, String> extras) {
        if (extras.isEmpty()) {
            return Map.copyOf(base);
        }

        var merged = new HashMap<>(base);

        for (var entry : extras.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();

            if (key == null || value == null || key.length() > 63 || value.length() > 63 || !HETZNER_LABEL_KEY_RX.matcher(key).matches() || !HETZNER_LABEL_VALUE_RX.matcher(value).matches()) {
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
        return placement.flatMap(HetznerComputeProvider::locationFromHint)
                        .or(config.region());
    }

    private static Option<String> locationFromHint(PlacementHint hint) {
        return switch (hint) {
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
        return "aether-" + UUID.randomUUID()
                               .toString()
                               .substring(0, 8);
    }

    private static Result<Long> parseServerId(InstanceId instanceId) {
        return Number.parseLong(instanceId.value());
    }

    static InstanceInfo toInstanceInfo(Server server) {
        var labels = safeLabels(server);

        return new InstanceInfo(new InstanceId(String.valueOf(server.id())),
                                mapStatus(server.status()),
                                collectAddresses(server),
                                InstanceType.ON_DEMAND,
                                labels,
                                Option.option(labels.get(NODE_ID_LABEL)));
    }

    private static Map<String, String> safeLabels(Server server) {
        return option(server.labels()).or(Map.of());
    }

    static String toLabelSelector(Map<String, String> tagFilter) {
        return tagFilter.entrySet()
                        .stream()
                        .map(HetznerComputeProvider::toLabelEntry)
                        .collect(Collectors.joining(","));
    }

    private static String toLabelEntry(Map.Entry<String, String> entry) {
        return entry.getKey() + "=" + entry.getValue();
    }

    private static List<InstanceInfo> toInstanceInfoList(List<Server> servers) {
        return servers.stream()
                      .map(HetznerComputeProvider::toInstanceInfo)
                      .toList();
    }

    static InstanceStatus mapStatus(String hetznerStatus) {
        return switch (hetznerStatus) {
            case "initializing", "starting", "rebuilding", "migrating" -> InstanceStatus.PROVISIONING;
            case "running" -> InstanceStatus.RUNNING;
            case "stopping", "off", "deleting" -> InstanceStatus.STOPPING;
            default -> InstanceStatus.TERMINATED;
        };
    }

    static List<String> collectAddresses(Server server) {
        var publicIp = publicIpv4(server);
        var privateIps = privateIps(server);

        return Stream.concat(publicIp.stream(),
                             privateIps.stream())
                     .toList();
    }

    private static Option<String> publicIpv4(Server server) {
        return option(server.publicNet()).flatMap(HetznerComputeProvider::ipv4FromPublicNet)
                     .map(Server.Ipv4::ip);
    }

    private static Option<Server.Ipv4> ipv4FromPublicNet(Server.PublicNet net) {
        return option(net.ipv4());
    }

    private static List<String> privateIps(Server server) {
        return option(server.privateNet()).map(HetznerComputeProvider::toPrivateIpList)
                     .or(List.of());
    }

    private static List<String> toPrivateIpList(List<Server.PrivateNet> nets) {
        return nets.stream()
                   .map(Server.PrivateNet::ip)
                   .toList();
    }

    private static final String CAPACITY_UNAVAILABLE_CODE = "resource_unavailable";

    private static EnvironmentError toProvisionError(String attemptedLocation, Cause cause) {
        return switch (cause) {
            case HetznerError.ApiError apiError when CAPACITY_UNAVAILABLE_CODE.equals(apiError.code()) -> EnvironmentError.capacityUnavailable(attemptedLocation,
                                                                                                                                               new RuntimeException(cause.message()));
            default -> EnvironmentError.provisionFailed(new RuntimeException(cause.message()));
        };
    }

    private static EnvironmentError toTerminateError(InstanceId instanceId, Cause cause) {
        return EnvironmentError.terminateFailed(instanceId, new RuntimeException(cause.message()));
    }

    private static EnvironmentError toListInstancesError(Cause cause) {
        return EnvironmentError.listInstancesFailed(new RuntimeException(cause.message()));
    }
}
