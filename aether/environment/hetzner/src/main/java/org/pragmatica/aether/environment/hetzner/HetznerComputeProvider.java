// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.hetzner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.IngressHandle;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ReadinessPolicy;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerError;
import org.pragmatica.cloud.hetzner.api.Firewall;
import org.pragmatica.cloud.hetzner.api.Firewall.CreateFirewallRequest;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.cloud.hetzner.api.SshKey;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;

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
    public ProviderDefaults providerDefaults() {
        return ProviderDefaults.providerDefaults(config.serverType(),
                                                 config.image(),
                                                 DEFAULT_IMAGE,
                                                 config.region(),
                                                 option(config.userData()),
                                                 true);
    }

    /// Translate a fully-resolved [ProvisionRequest] into a Hetzner create-server call.
    /// [ProvisionRequest#resolve] has already applied the server-type / image / zone / user-data
    /// precedence (#442, #459), so this consumes those fields verbatim — no provider-side
    /// re-derivation. SSH-key ids stay resolved provider-side (an async account lookup) but are now
    /// scoped to THIS cluster (RFC-0016 W3 §3.2–3.3): the lookup filters the cluster-scoped
    /// `aether-bootstrap-<cluster>` name prefix derived from the persisted cluster name — never the
    /// old account-wide bare prefix. A SPOT request is REJECTED loud: Hetzner has no spot product, so
    /// honoring it silently would provision an on-demand server — the exact silent-downgrade class
    /// this surface eliminates.
    @Override
    public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
        if (request.market() instanceof InstanceType.Spot) {
            return SPOT_UNSUPPORTED.promise();
        }

        return createAndConfirm(request, labelsFor(request.context())).mapError(cause -> toProvisionError(request.zone(),
                                                                                                          cause));
    }

    /// Hetzner offers no spot/preemptible product. PF-16 ([ClusterBootstrapConfigValidator]) already
    /// rejects a spot sub-table on Hetzner at parse, so a SPOT request reaching this provider is a
    /// bug — the rejection makes the guarantee structural instead of dependent on a distant validator.
    private static final Cause SPOT_UNSUPPORTED = EnvironmentError.provisionFailed(new RuntimeException("Hetzner offers no spot/preemptible product; spot sub-tables are rejected at validation (PF-16) — "
                                                                                                       + "a SPOT request reaching this provider is a bug"));

    private Promise<InstanceInfo> createAndConfirm(ProvisionRequest request, Map<String, String> labels) {
        return resolveSshKeyIds(clusterNameOrDefault(request.context())).map(sshKeyIds -> buildCreateRequest(request,
                                                                                                             sshKeyIds,
                                                                                                             labels))
                               .onSuccess(HetznerComputeProvider::logCreateRequest)
                               .flatMap(client::createServer)
                               .map(HetznerComputeProvider::toInstanceInfo)
                               .flatMap(info -> confirmRunning(info,
                                                               ReadinessPolicy.cloudDefault()))
                               .onFailure(HetznerComputeProvider::logProvisionFailureRollbackGap);
    }

    /// #442 v2b — operator-visible record of the EXACT labels sent to Hetzner at create time. The
    /// server is created with whatever this map holds, so this line is the ground truth for
    /// "did the VM get aether-cluster/aether-role?" — it distinguishes a provider that stamped them
    /// (any absence is then Hetzner-side) from a caller that supplied an unlabeled context (a wiring
    /// gap). Fires once per provisioned VM (bootstrap seed, CTM replacement, scale) — all
    /// infrequent, so INFO is not hot-path noise.
    private static void logCreateRequest(CreateServerRequest request) {
        log.info("Hetzner provision: creating server '{}' (serverType={}) with {} ssh key(s) and labels {}",
                 request.name(),
                 request.serverType(),
                 request.sshKeys().size(),
                 request.labels());
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
                            .flatMap(serverId -> mergeLabels(serverId, tags));
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

    /// #442 v2b — Hetzner's label update REPLACES the server's whole label map, so a partial
    /// `applyTags` (the node self-stamps only `aether-node-id` at join, `AetherNode.tagMatchingInstance`)
    /// would wipe the `aether-cluster` / `aether-role` / `aether-source` labels the create stamped —
    /// leaving a VM the harness's label-scoped enumeration can no longer find. Read-modify-write: fetch
    /// the server's current labels, overlay the new tags (new keys win), and write the union back. The
    /// VM's existing labels are the source of truth for the base set, so this is config-independent and
    /// correct across replacement-of-replacement chains (each generation's create already stamped the
    /// right base labels). The self-tag / self-register calls run once at join and are serialized on the
    /// node, so the read-modify-write lost-update window is negligible here.
    private Promise<Unit> mergeLabels(long serverId, Map<String, String> tags) {
        return client.getServer(serverId)
                     .map(server -> mergedLabels(server, tags))
                     .flatMap(merged -> client.updateServerLabels(serverId, merged));
    }

    private static Map<String, String> mergedLabels(Server server, Map<String, String> tags) {
        var merged = new HashMap<>(safeLabels(server));

        merged.putAll(tags);

        return Map.copyOf(merged);
    }

    private Promise<Server> serverById(long serverId) {
        return client.getServer(serverId);
    }

    private static final CreateServerRequest.PublicNetSpec IPV4_ONLY = new CreateServerRequest.PublicNetSpec(true, false);

    /// Bootstrap uploads operator SSH keys to Hetzner named with the cluster-scoped prefix
    /// `aether-bootstrap-<cluster>` and passes their ids at create time, so bootstrap VMs never get a
    /// root password. The ids live only in the bootstrap CLI process — so a leader provisioning an
    /// auto-heal replacement re-derives them here by listing the account's keys and matching the
    /// prefix scoped to ITS OWN cluster (RFC-0016 W3 §3.2–3.3: no persisted numeric id, self-healing
    /// against deleted/recreated keys, and — unlike the deleted account-wide bare match — bounded to
    /// this cluster's keys at a delimiter boundary (#444: matches `aether-bootstrap-<cluster>-`, so
    /// `prod` no longer matches `production`). NESTED-DELIMITER names (`prod` vs `prod-eu`) still
    /// collide — that is the #444 exact-id remainder, not fixed by prefix matching). Mirrors
    /// `BootstrapPhaseSshKey.HETZNER_KEY_NAME_PREFIX` — the `aether-cli` constant cannot be imported
    /// from this provider module.
    static final String BOOTSTRAP_KEY_NAME_PREFIX = "aether-bootstrap";
    /// Sentinel returned by [#clusterNameOrDefault] when no cluster name resolves. Used to gate the
    /// ssh-key prefix scope: an unresolved (blank/`unknown`) cluster produces NO cluster-scoped
    /// prefix, so the resolver falls to the loud keyless branch instead of guessing account-wide.
    private static final String UNKNOWN_CLUSTER = "unknown";
    /// #459 — Hetzner's stock boot image, supplied to [ProvisionRequest#resolve] as the provider
    /// [ProviderDefaults#fallbackImage] for the LOUD final image fallback: applied only when neither
    /// the source's role `image` nor the node's `[cloud.compute] image` resolves. Unlike the server
    /// type (which fails loud, because a wrong default caused an over-provisioning death spiral), the
    /// stock OS image is a SAFE default — a VM boots — so it is retained, but resolve() warns, so an
    /// operator who intended a prepared snapshot sees the stock image was used instead.
    private static final String DEFAULT_IMAGE = "ubuntu-22.04";

    private Promise<List<Long>> resolveSshKeyIds(String clusterName) {
        if (!config.sshKeyIds().isEmpty()) {
            log.info("Hetzner provision: SSH key ids resolved from node config (branch=config, count={}) — replacement inherits the cluster's keys",
                     config.sshKeyIds().size());

            return Promise.success(config.sshKeyIds());
        }

        return bootstrapKeyPrefix(clusterName).map(this::lookupBootstrapKeyIds)
                                 .or(HetznerComputeProvider::sshKeyScopeUnresolved);
    }

    private Promise<List<Long>> lookupBootstrapKeyIds(String prefix) {
        return client.listSshKeys()
                     .map(keys -> bootstrapKeyIds(keys, prefix))
                     .onSuccess(ids -> logPrefixBranch(ids, prefix))
                     .recover(HetznerComputeProvider::sshKeyLookupUnavailable);
    }

    /// Cluster-scoped ssh-key name prefix `aether-bootstrap-<cluster>` (RFC-0016 W3 §3.2–3.3), or
    /// empty when the cluster is unresolved (blank/[#UNKNOWN_CLUSTER]) — the empty case gates the
    /// resolver to the loud keyless branch rather than account-wide guessing. Blank-defensive in the
    /// same shape as `BootstrapPhaseSshKey.hetznerKeyPrefix`, so the provider's filter and the CLI's
    /// upload name derive the identical scoped prefix.
    private static Option<String> bootstrapKeyPrefix(String clusterName) {
        return Option.option(clusterName)
                     .map(String::trim)
                     .filter(name -> !name.isEmpty())
                     .filter(name -> !UNKNOWN_CLUSTER.equals(name))
                     .map(name -> BOOTSTRAP_KEY_NAME_PREFIX + "-" + name);
    }

    private static List<Long> bootstrapKeyIds(List<SshKey> keys, String prefix) {
        return keys.stream()
                   .filter(key -> isBootstrapKey(key, prefix))
                   .map(SshKey::id)
                   .toList();
    }

    private static boolean isBootstrapKey(SshKey key, String prefix) {
        // #444 — require a delimiter boundary (`prefix + "-"`): a `prod` cluster (prefix
        // `aether-bootstrap-prod`) no longer matches `aether-bootstrap-production-*`. Keys are named
        // `aether-bootstrap-<cluster>-<blob>`, so a same-cluster key always carries the trailing '-'.
        // Nested-delimiter names (`prod` vs `prod-eu`) still collide — the #444 exact-id remainder.
        return key.name() != null && key.name()
                                        .startsWith(prefix + "-");
    }

    /// #442 / RFC-0016 W3 — one operator-diagnosable line naming which branch resolved the ssh-key
    /// ids the replacement was created with. `branch=prefix` when the account carries keys named with
    /// this cluster's scoped `aether-bootstrap-<cluster>*` prefix (the fresh-upload environment),
    /// `branch=none` when it carries none — the exact signal missing from the field run where a
    /// keyless replacement hit the PAM wall and the cause could not be told apart from a lookup
    /// failure ([#sshKeyLookupUnavailable], `branch=failed`) or an unresolved cluster scope
    /// ([#sshKeyScopeUnresolved]).
    private static void logPrefixBranch(List<Long> ids, String prefix) {
        if (ids.isEmpty()) {
            log.warn("Hetzner provision: no SSH keys named '{}*' (cluster-scoped) on the account (branch=none); replacement created without an "
                    + "ssh_keys param (Hetzner sets a root password; key auth via cloud-init still works)",
                     prefix);

            return;
        }

        log.warn("Hetzner provision: SSH key ids resolved by cluster-scoped '{}*' name-prefix match (branch=prefix, count={})",
                 prefix,
                 ids.size());
    }

    /// RFC-0016 W3 §3.3 — the cluster name is unresolved (blank/`unknown`) AND the node config carries
    /// no `ssh_key_ids`, so NO cluster-scoped `aether-bootstrap-<cluster>*` prefix exists to resolve.
    /// Fail-loud posture: rather than fall back to the deleted account-wide bare-prefix guessing (which
    /// could attach another cluster's keys), create the replacement without an `ssh_keys` param — key
    /// auth via cloud-init still works, exactly as the `branch=none` path does, though a root password
    /// will be set.
    private static Promise<List<Long>> sshKeyScopeUnresolved() {
        log.warn("Hetzner provision: cluster name unresolved (blank/unknown) and node config carries no ssh_key_ids "
                + "(branch=none); no cluster-scoped 'aether-bootstrap-<cluster>*' keys could be resolved, so the "
                + "replacement is created without an ssh_keys param (key auth via cloud-init still works, but a root "
                + "password will be set)");

        return Promise.success(List.of());
    }

    private static List<Long> sshKeyLookupUnavailable(Cause cause) {
        log.warn("Hetzner provision: cluster-scoped SSH-key lookup failed (branch=failed: {}); creating the replacement without an ssh_keys param "
                + "(key auth via cloud-init still works, but a root password will be set)",
                 cause.message());

        return List.of();
    }

    private CreateServerRequest buildCreateRequest(ProvisionRequest request,
                                                   List<Long> sshKeyIds,
                                                   Map<String, String> labels) {
        return CreateServerRequest.createServerRequest(generateServerName(),
                                                       request.instanceSize(),
                                                       request.image(),
                                                       sshKeyIds,
                                                       config.networkIds(),
                                                       config.firewallIds(),
                                                       request.zone(),
                                                       request.userData().or(""),
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
                     .or(UNKNOWN_CLUSTER);
    }

    private static Map<String, String> buildLabels(String clusterLabel, String role, String sourceName) {
        var labels = new HashMap<String, String>();

        labels.put(CLUSTER_LABEL, sanitizeLabelValue(clusterLabel));
        labels.put("aether-role", sanitizeLabelValue(role));
        if (!sourceName.isEmpty()) {
            labels.put(SOURCE_LABEL, sanitizeLabelValue(sourceName));
        }

        return labels;
    }

    static final String CLUSTER_LABEL = "aether-cluster";
    static final String SOURCE_LABEL = "aether-source";

    /// Ingress (REQ-5.1.8.4), create-or-patch. Per §6.2 Aether creates its rules as a STANDALONE
    /// firewall associated with the source's servers — one firewall per (cluster, source), carrying
    /// every rule that source declares. A `"tcp+udp"` entry arrives here as two calls (REQ-5.1.8.1);
    /// the first creates the firewall, the second patches it and returns the SAME handle.
    ///
    /// The returned id is fed back into server-create (`firewall_ids`), so the rule is in force
    /// before the instance exists. Applying it afterwards would leave a window in which the node is
    /// up and — per §6.2, Hetzner servers with no firewall association accept ALL inbound — fully
    /// open.
    @Override
    public Promise<IngressHandle> openIngress(String sourceId,
                                              int port,
                                              String protocol,
                                              String sourceCidr,
                                              String description) {
        return firewallCluster().async()
                              .flatMap(cluster -> openIngressFor(cluster,
                                                                 sourceId,
                                                                 port,
                                                                 protocol,
                                                                 sourceCidr,
                                                                 description));
    }

    @Override
    public Promise<Unit> closeIngress(String sourceId, int port, String protocol, String sourceCidr) {
        return firewallCluster().async()
                              .flatMap(cluster -> client.listFirewalls(firewallSelector(cluster, sourceId)))
                              .flatMap(found -> withdrawFrom(found, port, protocol, sourceCidr));
    }

    /// An ingress firewall MUST carry `aether-cluster` — the out-of-band reaper
    /// (`tools/cloud-reaper.sh`) enumerates by that label, so an unlabelled firewall is invisible to
    /// every cleanup path and leaks as a paid resource. Refusing loudly beats creating one we cannot
    /// later find; the bootstrap caller always supplies the cluster.
    private Result<String> firewallCluster() {
        var cluster = config.clusterName().or("");

        return cluster.isEmpty()
               ? EnvironmentError.operationNotSupported(NO_CLUSTER_FOR_INGRESS).result()
               : success(cluster);
    }

    private static final String NO_CLUSTER_FOR_INGRESS = "openIngress/closeIngress (no cluster name resolved for this provider — an ingress firewall "
                                                       + "without the aether-cluster label cannot be reclaimed by cleanup and would leak)";

    private Promise<IngressHandle> openIngressFor(String cluster,
                                                  String sourceId,
                                                  int port,
                                                  String protocol,
                                                  String sourceCidr,
                                                  String description) {
        var rule = Firewall.Rule.inbound(port, protocol, sourceCidr, description);

        return client.listFirewalls(firewallSelector(cluster, sourceId))
                     .flatMap(found -> openOrPatch(cluster, sourceId, rule, port, protocol, sourceCidr, found));
    }

    private Promise<IngressHandle> openOrPatch(String cluster,
                                               String sourceId,
                                               Firewall.Rule rule,
                                               int port,
                                               String protocol,
                                               String sourceCidr,
                                               List<Firewall> found) {
        if (found.isEmpty()) {
            return createIngressFirewall(cluster, sourceId, rule);
        }

        return patchIngressFirewall(found.getFirst(), rule, port, protocol, sourceCidr);
    }

    private Promise<IngressHandle> createIngressFirewall(String cluster, String sourceId, Firewall.Rule rule) {
        var labels = Map.of(CLUSTER_LABEL, sanitizeLabelValue(cluster), SOURCE_LABEL, sanitizeLabelValue(sourceId));
        var request = CreateFirewallRequest.createFirewallRequest(firewallName(cluster, sourceId), List.of(rule), labels);

        return client.createFirewall(request)
                     .onSuccess(firewall -> log.info("Hetzner ingress: created firewall '{}' (id={}) for source '{}' with rule {}/{} from {}",
                                                     firewall.name(),
                                                     firewall.id(),
                                                     sourceId,
                                                     rule.port(),
                                                     rule.protocol(),
                                                     sourceCidr(rule)))
                     .map(firewall -> IngressHandle.ingressHandle(Long.toString(firewall.id())));
    }

    /// Patch = union, never replace. Hetzner exposes no add-one-rule action, so the current rule set
    /// is read and the new rule appended. Sending only the new rule would silently drop every other
    /// rule on the firewall — and REQ-5.1.8.1 requires that rules not listed are not touched.
    private Promise<IngressHandle> patchIngressFirewall(Firewall firewall,
                                                        Firewall.Rule rule,
                                                        int port,
                                                        String protocol,
                                                        String sourceCidr) {
        var current = rulesOf(firewall);
        var handle = IngressHandle.ingressHandle(Long.toString(firewall.id()));

        if (current.stream().anyMatch(existing -> existing.sameTargetAs(port, protocol, sourceCidr))) {
            return Promise.success(handle);
        }

        return client.setFirewallRules(firewall.id(),
                                       Stream.concat(current.stream(),
                                                     Stream.of(rule)).toList())
                     .map(_ -> handle);
    }

    private Promise<Unit> withdrawFrom(List<Firewall> found, int port, String protocol, String sourceCidr) {
        if (found.isEmpty()) {
            return Promise.success(Unit.unit());
        }

        var firewall = found.getFirst();
        var current = rulesOf(firewall);
        var remaining = current.stream()
                               .filter(existing -> !existing.sameTargetAs(port, protocol, sourceCidr))
                               .toList();

        if (remaining.size() == current.size()) {
            return Promise.success(Unit.unit());
        }

        return remaining.isEmpty()
               ? client.deleteFirewall(firewall.id())
               : client.setFirewallRules(firewall.id(), remaining);
    }

    private static List<Firewall.Rule> rulesOf(Firewall firewall) {
        return option(firewall.rules()).or(List.of());
    }

    private static String sourceCidr(Firewall.Rule rule) {
        var ips = option(rule.sourceIps()).or(List.<String> of());

        return ips.isEmpty()
               ? ""
               : ips.getFirst();
    }

    /// Selects the ONE firewall belonging to this (cluster, source). Scoping by both labels is what
    /// keeps create-or-patch and withdraw from ever touching a firewall Aether did not create —
    /// the 2026-08-03 test-pg incident (#572) is what unscoped cleanup costs.
    private static String firewallSelector(String cluster, String sourceId) {
        return CLUSTER_LABEL
             + "=" + sanitizeLabelValue(cluster)
             + "," + SOURCE_LABEL
             + "=" + sanitizeLabelValue(sourceId);
    }

    private static String firewallName(String cluster, String sourceId) {
        return sanitizeLabelValue("aether-" + cluster + "-" + sourceId);
    }

    private static final Pattern LABEL_VALUE_DISALLOWED = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Pattern LABEL_VALUE_EDGE_TRIM = Pattern.compile("^[._-]+|[._-]+$");
    private static final int HETZNER_LABEL_VALUE_MAX = 63;

    /// #442 v2b — coerce a metadata label VALUE into Hetzner's constraint (max 63 chars,
    /// `[a-zA-Z0-9._-]`, start AND end alphanumeric when non-empty). Disallowed characters collapse
    /// to `-`, the value is truncated to 63 keeping the PREFIX (the harness matches `aether-cluster`
    /// by prefix), then leading/trailing separators are trimmed to satisfy the start/end rule.
    /// Applied to the cluster/role/source labels — which previously bypassed the [#appendCompatible]
    /// check that only guards caller extras — so a cluster name with a stray character can never make
    /// Hetzner reject the whole create (a keyless-looking VM) or drop the label. NOT applied to
    /// `aether-node-id`: that value is the node identity terminate-by-tag matches on and must stay
    /// byte-exact. An empty value passes through unchanged (Hetzner accepts and drops an empty label).
    private static String sanitizeLabelValue(String raw) {
        var mapped = LABEL_VALUE_DISALLOWED.matcher(raw).replaceAll("-");
        var capped = mapped.length() > HETZNER_LABEL_VALUE_MAX
                     ? mapped.substring(0, HETZNER_LABEL_VALUE_MAX)
                     : mapped;

        return LABEL_VALUE_EDGE_TRIM.matcher(capped).replaceAll("");
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

    /// A `404 not_found` on delete means the server is ALREADY GONE, which is the outcome terminate
    /// was asked for — so it maps to [EnvironmentError.InstanceNotFound], not a generic failure, and
    /// callers doing idempotent teardown can treat it as success.
    ///
    /// Observed live on 2026-08-05: after a partially-failed `cluster destroy`, every retry re-reported
    /// "Instance termination failed: 404 not_found" for servers that no longer existed, so cleanup could
    /// never reach success and the cluster registry entry stayed KEPT forever.
    private static EnvironmentError toTerminateError(InstanceId instanceId, Cause cause) {
        if (cause instanceof HetznerError.ApiError apiError && apiError.statusCode() == 404) {
            return EnvironmentError.InstanceNotFound.instanceNotFound(instanceId).unwrap();
        }

        return EnvironmentError.terminateFailed(instanceId, new RuntimeException(cause.message()));
    }

    private static EnvironmentError toListInstancesError(Cause cause) {
        return EnvironmentError.listInstancesFailed(new RuntimeException(cause.message()));
    }
}
