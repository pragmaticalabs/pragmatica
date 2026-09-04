// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public final class ClusterBootstrapConfigValidator {
    /// #585: full semver 2.0.0 grammar (semver.org), not a bare `X.Y.Z` triplet — pre-release
    /// (`-rc3`, `-alpha.1`) and build metadata (`+build.5`) are valid semver and CL-02 must accept
    /// them; the project's own current version string (`1.0.0-rc3`/`1.0.0-rc4`) was being rejected
    /// at bootstrap VALIDATE. Still rejects what semver rejects: leading zeros in numeric
    /// identifiers (`1.0.0-01`), empty identifiers (`1.0.0-`), and characters outside
    /// `[0-9A-Za-z-]` (`1.0.0-rc_4`).
    private static final Pattern SEMVER_PATTERN =
        Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                       + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
                       + "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
                       + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

    private static final Pattern CIDR_PATTERN = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d{1,2}$");

    private static final Set<String> VALID_PROTOCOLS = Set.of("tcp", "udp", "tcp+udp");

    private static final Set<RuntimeType> CLOUD_RUNTIME_TYPES = EnumSet.of(RuntimeType.CONTAINER, RuntimeType.JVM);

    private static final Set<RuntimeType> SSH_RUNTIME_TYPES = EnumSet.of(RuntimeType.CONTAINER,
                                                                         RuntimeType.JVM,
                                                                         RuntimeType.EMBER);

    private ClusterBootstrapConfigValidator() {}

    public static Result<ClusterBootstrapConfig> validate(ClusterBootstrapConfig config) {
        var errors = new ArrayList<String>();

        validateClusterLevel(config, errors);
        validateCoreTopology(config, errors);
        validateSources(config, errors);
        validatePortDistinctness(config, errors);
        validateAutoHealDisableHonesty(config, errors);
        if (errors.isEmpty()) {
            return success(config);
        }

        return new ClusterConfigError.ParseFailed(String.join("; ", errors)).result();
    }

    public static List<String> warnings(ClusterBootstrapConfig config) {
        var warnings = new ArrayList<String>();

        checkCoreMajorityWarning(config, warnings);
        checkCapacityMajorityWarning(config, warnings);
        config.sources()
              .forEach((name, source) -> checkFirewallAllowsBootstrapPorts(name,
                                                                           source,
                                                                           config.operations().ports().management(),
                                                                           warnings));

        return List.copyOf(warnings);
    }

    /// #575: `[operations.auto_heal] enabled = false` (or the `[operations] auto_heal = false`
    /// shortcut — both parse to the same [AutoHealSpec.enabled]) parses, validates, and diffs
    /// cleanly, then changes nothing: the runtime reads a separately-hand-maintained
    /// `AutoHealConfig` (`environment-integration`) that has no `enabled` field at all, so nothing
    /// in the provisioning path ever consults the parsed value. An operator who sets this to stop
    /// replacement provisioning during an incident gets silent no-op, not the suppression they
    /// asked for. Mirrors [#checkIngressProviderSupport] (PF-23) — reject a declared knob loudly
    /// rather than parse it and do nothing. `enabled = true` is NOT rejected: it matches the
    /// runtime's actual always-on behavior, so it does not assert anything false, even though it is
    /// equally inert.
    ///
    /// This does not leave auto-heal impossible to disable — [ClusterTopologyManager
    /// #setAutoHealEnabled] (`aether cluster topology auto-heal disable`, #603) is a real,
    /// already-wired runtime switch; it is simply a different mechanism (an imperative, per-leader
    /// -term toggle) from this bootstrap-time declarative key.
    private static void validateAutoHealDisableHonesty(ClusterBootstrapConfig config, List<String> errors) {
        if (config.operations().autoHeal().enabled()) {
            return;
        }

        errors.add("PF-25: [operations.auto_heal] enabled = false has no runtime effect — the parsed"
                  + " value is never read by the cluster's provisioning path. Remove the key (or set"
                  + " it to true, its only honest value) and use the live operator toggle instead:"
                  + " `aether cluster topology auto-heal disable`, which actually suppresses"
                  + " replacement provisioning for the current leader term.");
    }

    private static void validateClusterLevel(ClusterBootstrapConfig config, List<String> errors) {
        // CL-01 (cluster-name grammar) is not checked here: `config.cluster().name()` is a
        // `ClusterName`, so an out-of-grammar name is unrepresentable at this point and the branch
        // was provably dead. The rejection happens where the operator's text is still available —
        // `ClusterIdentity.clusterIdentity`, which reports `InvalidName` naming the offending value.
        validateClusterVersion(config.cluster().version(),
                               errors);
        validateDerivedCoreCount(config.derivedCoreCount(), errors);
        validateAtLeastOneCoreSubTable(config, errors);
        validateSourceNamesNonEmpty(config, errors);
        validateRuntimeReferences(config, errors);
    }

    private static void validateClusterVersion(String version, List<String> errors) {
        if (!SEMVER_PATTERN.matcher(version).matches()) {
            errors.add("CL-02: Cluster version '" + version + "' must be valid semver 2.0.0 —"
                      + " MAJOR.MINOR.PATCH (no leading zeros), with an optional -pre-release"
                      + " (e.g. '-rc3', '-alpha.1') and/or +build metadata (e.g. '+build.5')");
        }
    }

    private static void validateDerivedCoreCount(int coreCount, List<String> errors) {
        if (coreCount < 3) {
            errors.add("CL-04: Derived core count " + coreCount + " must be >= 3");
        }

        if (coreCount % 2 == 0) {
            errors.add("CL-04: Derived core count " + coreCount + " must be odd");
        }
    }

    private static void validateAtLeastOneCoreSubTable(ClusterBootstrapConfig config, List<String> errors) {
        var hasCoreSubTable = config.sources().values().stream().anyMatch(s -> s.roles()
                                                                                .containsKey(NodeRole.CORE));

        if (!hasCoreSubTable) {
            errors.add("CL-07: At least one source must define a core sub-table");
        }
    }

    private static void validateSourceNamesNonEmpty(ClusterBootstrapConfig config, List<String> errors) {
        if (config.sources().containsKey("")) {
            errors.add("CL-08: Source names must not be empty");
        }
    }

    private static void validateRuntimeReferences(ClusterBootstrapConfig config, List<String> errors) {
        var runtimes = config.runtimes();

        config.sources()
              .forEach((sourceName, source) -> source.roles()
                                                     .forEach((role, sub) -> checkRuntimeRef(sourceName,
                                                                                             role,
                                                                                             sub.runtimeRef(),
                                                                                             source.type(),
                                                                                             runtimes,
                                                                                             errors)));
    }

    private static void checkRuntimeRef(String sourceName,
                                        NodeRole role,
                                        String runtimeRef,
                                        SourceType sourceType,
                                        Map<String, RuntimeProfile> runtimes,
                                        List<String> errors) {
        if (isImplicitRuntime(runtimeRef, sourceType)) {
            return;
        }

        if (!runtimes.containsKey(runtimeRef)) {
            errors.add("CL-06: Source '" + sourceName
                      + "' role '" + role.value()
                      + "' references unknown runtime '" + runtimeRef
                      + "'");
        }
    }

    private static boolean isImplicitRuntime(String runtimeRef, SourceType sourceType) {
        if ("default".equals(runtimeRef) && (sourceType == SourceType.FORGE || sourceType == SourceType.DOCKER)) {
            return true;
        }

        if ("docker".equals(runtimeRef) && sourceType == SourceType.DOCKER) {
            return true;
        }

        return "ember".equals(runtimeRef) && sourceType == SourceType.FORGE;
    }

    private static void validateCoreTopology(ClusterBootstrapConfig config, List<String> errors) {
        var topology = config.coreTopology();
        var derivedCount = config.derivedCoreCount();

        topology.min().onPresent(min -> validateCoreMin(min, derivedCount, errors));
        topology.max().onPresent(max -> validateCoreMax(max, derivedCount, errors));
        validateMaxUnavailable(topology.maxUnavailable(), derivedCount, errors);
    }

    private static void validateCoreMin(int min, int derivedCount, List<String> errors) {
        if (min < 3) {
            errors.add("REQ-3.3.2: core_topology.min " + min + " must be >= 3");
        }

        if (min % 2 == 0) {
            errors.add("REQ-3.3.2: core_topology.min " + min + " must be odd");
        }

        if (min > derivedCount) {
            errors.add("REQ-3.3.2: core_topology.min " + min + " must be <= derived core count " + derivedCount);
        }
    }

    private static void validateCoreMax(int max, int derivedCount, List<String> errors) {
        if (max % 2 == 0) {
            errors.add("REQ-3.3.3: core_topology.max " + max + " must be odd");
        }

        if (max < derivedCount) {
            errors.add("REQ-3.3.3: core_topology.max " + max + " must be >= derived core count " + derivedCount);
        }
    }

    private static void validateMaxUnavailable(int maxUnavailable, int derivedCount, List<String> errors) {
        if (maxUnavailable < 1) {
            errors.add("REQ-3.3.7: maxUnavailable " + maxUnavailable + " must be >= 1");
        }

        var limit = (derivedCount - 1) / 2;

        if (maxUnavailable > limit) {
            errors.add("REQ-3.3.7: maxUnavailable " + maxUnavailable
                      + " must be <= (derivedCoreCount - 1) / 2 = " + limit);
        }
    }

    private static void validateSources(ClusterBootstrapConfig config, List<String> errors) {
        config.sources()
              .forEach((name, source) -> validateSource(name,
                                                        source,
                                                        config.runtimes(),
                                                        config.operations().ports().management(),
                                                        errors));
    }

    private static void validateSource(String name,
                                       SourceProfile source,
                                       Map<String, RuntimeProfile> runtimes,
                                       int managementPort,
                                       List<String> errors) {
        validateRoleConstraints(name, source, errors);
        validateSpotRestriction(name, source, errors);
        validateElectedLbRestriction(name, source, errors);
        validateElectedLbHasNonSpot(name, source, errors);
        validateFirewallRules(name, source, managementPort, errors);
        validateRuntimeTypeCompatibility(name, source, runtimes, errors);
        validatePortConflictsOnSameHost(name, source, errors);
    }

    private static void validateRoleConstraints(String name, SourceProfile source, List<String> errors) {
        source.roles().forEach((role, sub) -> validateSingleRoleConstraint(name, source.type(), role, sub, errors));
    }

    private static void validateSingleRoleConstraint(String name,
                                                     SourceType type,
                                                     NodeRole role,
                                                     RoleSubTable sub,
                                                     List<String> errors) {
        switch (type) {
            case SSH -> validateSshRole(name, role, sub, errors);
            case CLOUD, FORGE, DOCKER -> validateCloudOrForgeRole(name, role, sub, errors);
        }
    }

    private static void validateSshRole(String sourceName, NodeRole role, RoleSubTable sub, List<String> errors) {
        if (sub.hosts().equals(Option.none()) && sub.count().equals(Option.none())) {
            errors.add("PF-10: SSH source '" + sourceName + "' role '" + role.value() + "' must have hosts");
        } else if (sub.count().map(c -> c > 0).or(false) && sub.hosts().equals(Option.none())) {
            errors.add("PF-10: SSH source '" + sourceName + "' role '" + role.value() + "' must use hosts (not count)");
        }
    }

    private static void validateCloudOrForgeRole(String sourceName,
                                                 NodeRole role,
                                                 RoleSubTable sub,
                                                 List<String> errors) {
        if (sub.count().equals(Option.none()) && sub.hosts().equals(Option.none())) {
            errors.add("PF-11: Source '" + sourceName + "' role '" + role.value() + "' must have count");
        } else if (sub.hosts().map(h -> !h.isEmpty()).or(false) && sub.count().equals(Option.none())) {
            errors.add("PF-11: Source '" + sourceName + "' role '" + role.value() + "' must use count (not hosts)");
        }
    }

    private static void validateSpotRestriction(String name, SourceProfile source, List<String> errors) {
        if (!source.roles().containsKey(NodeRole.SPOT)) {
            return;
        }

        if (source.type() != SourceType.CLOUD) {
            errors.add("PF-15: Spot nodes only allowed on cloud sources, found on '" + name
                      + "' (type: " + source.type().value()
                      + ")");
        }

        checkSpotProviderSupport(name, source, errors);
    }

    /// Providers WITHOUT an implemented spot/preemptible arm reject a `[source.<provider>.spot]`
    /// sub-table loudly (W10). Post-W1 ground truth: only AWS has a real spot arm (its `createFrom`
    /// attaches EC2 `InstanceMarketOptions`); the others reject SPOT at `createFrom`. AWS is therefore
    /// ABSENT from this map (a spot sub-table is allowed only on aws sources today). This map is the
    /// single place to extend: when a GCP/Azure spot arm lands, remove that provider's entry.
    private static final Map<CloudProviderName, String> SPOT_UNSUPPORTED_REASONS = Map.of(CloudProviderName.HETZNER,
                                                                                          "Provider 'hetzner' does not support spot/preemptible",
                                                                                          CloudProviderName.GCP,
                                                                                          "Provider 'gcp' spot (provisioningModel=SPOT) provisioning is not yet implemented on this client",
                                                                                          CloudProviderName.AZURE,
                                                                                          "Provider 'azure' spot (priority=Spot) provisioning is not yet implemented on this client");

    private static void checkSpotProviderSupport(String name, SourceProfile source, List<String> errors) {
        source.provider()
              .flatMap(ClusterBootstrapConfigValidator::spotUnsupportedReason)
              .onPresent(reason -> errors.add("PF-16: " + reason + " on source '" + name + "'"));
    }

    private static Option<String> spotUnsupportedReason(CloudProviderName provider) {
        return Option.option(SPOT_UNSUPPORTED_REASONS.get(provider));
    }

    private static void validateElectedLbRestriction(String name, SourceProfile source, List<String> errors) {
        if (source.loadBalancer() == LoadBalancerMode.ELECTED && source.type() == SourceType.SSH) {
            errors.add("PF-17: Elected load balancer not supported on SSH source '" + name + "'");
        }
    }

    private static void validateElectedLbHasNonSpot(String name, SourceProfile source, List<String> errors) {
        if (source.loadBalancer() != LoadBalancerMode.ELECTED) {
            return;
        }

        var hasNonSpot = source.roles().keySet().stream().anyMatch(role -> role != NodeRole.SPOT);

        if (!hasNonSpot) {
            errors.add("PF-14: Source '" + name + "' with elected LB must have at least one non-spot sub-table");
        }
    }

    private static void validateFirewallRules(String name,
                                              SourceProfile source,
                                              int managementPort,
                                              List<String> errors) {
        checkIngressProviderSupport(name, source, errors);
        checkPublicManagementWithoutAuth(name, source, managementPort, errors);
        source.firewallRules().forEach(rule -> validateSingleFirewallRule(name, rule, errors));
    }

    /// Providers with no implemented ingress arm reject `allow_ingress` loudly rather than parsing it
    /// and doing nothing (#574). Mirrors PF-16's per-provider shape.
    ///
    /// Only Hetzner is absent from this map — it is the one provider where the rules are actually
    /// applied, and the one where the gap was DANGEROUS rather than merely inert: per §6.2 a Hetzner
    /// server created with no firewall association accepts ALL inbound traffic, so unapplied rules
    /// fail OPEN. On AWS/GCP/Azure the default security groups deny inbound, so the same gap fails
    /// closed — unreachable, not exposed — and operators are directed to their own security groups.
    /// Remove a provider's entry when its `openIngress` lands (#463).
    /// AWS is absent because its `openIngress` LANDED (#463): security groups are created, tagged
    /// `(aether-cluster, aether-source)`, attached at instance-create and reclaimed by `cluster destroy`.
    /// GCP and Azure remain — remove each entry when its provider's `openIngress` lands, not before, or
    /// pre-flight will accept `allow_ingress` that nothing enforces.
    private static final Map<CloudProviderName, String> INGRESS_UNSUPPORTED_REASONS = Map.of(CloudProviderName.GCP,
                                                                                             "Provider 'gcp' ingress management (firewall rules) is not yet implemented on this client",
                                                                                             CloudProviderName.AZURE,
                                                                                             "Provider 'azure' ingress management (network security groups) is not yet implemented on this client");

    /// PF-24 — the management API reachable from the whole internet with authentication disabled is
    /// unauthenticated remote control of the cluster: deploy, scale, config, secrets-adjacent surface.
    /// Either half alone is a defensible operator choice; the PAIR is not, so it is an error rather
    /// than a warning.
    ///
    /// `security_mode` lives in the per-source `node_config` overlay (`[app-http] security_mode`),
    /// which is the same place the documented cloud example sets `"NONE"` to get past bootstrap's own
    /// config write — so this combination is reachable by following the docs, not only by mistake.
    private static void checkPublicManagementWithoutAuth(String name,
                                                         SourceProfile source,
                                                         int managementPort,
                                                         List<String> errors) {
        var publiclyOpen = source.firewallRules()
                                 .stream()
                                 .anyMatch(rule -> rule.port() == managementPort && ANY_CIDR.equals(rule.sourceCidr()));

        if (!publiclyOpen || !securityDisabled(source)) {
            return;
        }

        errors.add("PF-24: Source '" + name
                  + "' opens the management port " + managementPort
                  + " to " + ANY_CIDR
                  + " while [app-http] security_mode = \"none\". That is an"
                  + " unauthenticated management API on the public internet — anyone who can reach it"
                  + " can deploy, scale and reconfigure the cluster. Scope source_cidr to your operator"
                  + " network, or enable authentication.");
    }

    private static boolean securityDisabled(SourceProfile source) {
        return source.nodeConfig()
                     .flatMap(doc -> doc.getString("app-http", "security_mode"))
                     .map(mode -> "none".equalsIgnoreCase(mode.trim()))
                     .or(false);
    }

    private static final String ANY_CIDR = "0.0.0.0/0";

    private static void checkIngressProviderSupport(String name, SourceProfile source, List<String> errors) {
        if (source.firewallRules().isEmpty()) {
            return;
        }

        if (source.type() != SourceType.CLOUD) {
            errors.add("PF-23: Source '" + name
                      + "' is type '" + source.type().value()
                      + "', which has no cloud ingress API — `allow_ingress` would be silently ignored."
                      + " Manage the host firewall yourself and remove `[source." + name
                      + ".firewall]`.");

            return;
        }

        source.provider()
              .flatMap(provider -> Option.option(INGRESS_UNSUPPORTED_REASONS.get(provider)))
              .onPresent(reason -> errors.add("PF-23: " + reason
                                             + " on source '" + name
                                             + "'. Manage ingress via your own security groups"
                                             + " and remove `[source." + name
                                             + ".firewall]`."));
    }

    private static void validateSingleFirewallRule(String sourceName, FirewallRule rule, List<String> errors) {
        if (rule.port() < 1 || rule.port() > 65535) {
            errors.add("PF-18: Firewall rule on source '" + sourceName + "' has invalid port " + rule.port());
        }

        if (!VALID_PROTOCOLS.contains(rule.protocol())) {
            errors.add("PF-18: Firewall rule on source '" + sourceName
                      + "' has invalid protocol '" + rule.protocol()
                      + "'");
        }

        if (!CIDR_PATTERN.matcher(rule.sourceCidr()).matches()) {
            errors.add("PF-18: Firewall rule on source '" + sourceName
                      + "' has invalid CIDR '" + rule.sourceCidr()
                      + "'");
        }
    }

    private static void validateRuntimeTypeCompatibility(String name,
                                                         SourceProfile source,
                                                         Map<String, RuntimeProfile> runtimes,
                                                         List<String> errors) {
        source.roles()
              .forEach((role, sub) -> checkRoleRuntimeType(name,
                                                           role,
                                                           sub.runtimeRef(),
                                                           source.type(),
                                                           runtimes,
                                                           errors));
    }

    private static void checkRoleRuntimeType(String sourceName,
                                             NodeRole role,
                                             String runtimeRef,
                                             SourceType sourceType,
                                             Map<String, RuntimeProfile> runtimes,
                                             List<String> errors) {
        var resolved = resolveRuntimeType(runtimeRef, sourceType, runtimes);

        resolved.onPresent(runtimeType -> validateRuntimeForSource(sourceName,
                                                                   role,
                                                                   runtimeRef,
                                                                   sourceType,
                                                                   runtimeType,
                                                                   errors));
    }

    private static Option<RuntimeType> resolveRuntimeType(String runtimeRef,
                                                          SourceType sourceType,
                                                          Map<String, RuntimeProfile> runtimes) {
        return Option.option(runtimes.get(runtimeRef))
                     .map(RuntimeProfile::type)
                     .orElse(() -> resolveImplicitRuntimeType(runtimeRef, sourceType));
    }

    private static Option<RuntimeType> resolveImplicitRuntimeType(String runtimeRef, SourceType sourceType) {
        if ("ember".equals(runtimeRef) && sourceType == SourceType.FORGE) {
            return Option.some(RuntimeType.EMBER);
        }

        if ("default".equals(runtimeRef) && sourceType == SourceType.FORGE) {
            return Option.some(RuntimeType.EMBER);
        }

        if ("default".equals(runtimeRef) && sourceType == SourceType.DOCKER) {
            return Option.some(RuntimeType.DOCKER);
        }

        return Option.none();
    }

    private static void validateRuntimeForSource(String sourceName,
                                                 NodeRole role,
                                                 String runtimeRef,
                                                 SourceType sourceType,
                                                 RuntimeType runtimeType,
                                                 List<String> errors) {
        switch (sourceType) {
            case FORGE -> checkForgeRuntime(sourceName, role, runtimeRef, runtimeType, errors);
            case DOCKER -> checkDockerRuntime(sourceName, role, runtimeRef, runtimeType, errors);
            case CLOUD -> checkCloudRuntime(sourceName, role, runtimeRef, runtimeType, errors);
            case SSH -> checkSshRuntime(sourceName, role, runtimeRef, runtimeType, errors);
        }
    }

    private static void checkForgeRuntime(String sourceName,
                                          NodeRole role,
                                          String runtimeRef,
                                          RuntimeType runtimeType,
                                          List<String> errors) {
        if (runtimeType != RuntimeType.EMBER) {
            errors.add("PF-19: Forge source '" + sourceName
                      + "' role '" + role.value()
                      + "' runtime '" + runtimeRef
                      + "' must be EMBER type, got " + runtimeType.value());
        }
    }

    private static void checkDockerRuntime(String sourceName,
                                           NodeRole role,
                                           String runtimeRef,
                                           RuntimeType runtimeType,
                                           List<String> errors) {
        if (runtimeType != RuntimeType.DOCKER) {
            errors.add("PF-20: Docker source '" + sourceName
                      + "' role '" + role.value()
                      + "' runtime '" + runtimeRef
                      + "' must be DOCKER type, got " + runtimeType.value());
        }
    }

    private static void checkCloudRuntime(String sourceName,
                                          NodeRole role,
                                          String runtimeRef,
                                          RuntimeType runtimeType,
                                          List<String> errors) {
        if (!CLOUD_RUNTIME_TYPES.contains(runtimeType)) {
            errors.add("PF-21: Cloud source '" + sourceName
                      + "' role '" + role.value()
                      + "' runtime '" + runtimeRef
                      + "' must be CONTAINER or JVM, got " + runtimeType.value());
        }
    }

    private static void checkSshRuntime(String sourceName,
                                        NodeRole role,
                                        String runtimeRef,
                                        RuntimeType runtimeType,
                                        List<String> errors) {
        if (!SSH_RUNTIME_TYPES.contains(runtimeType)) {
            errors.add("PF-22: SSH source '" + sourceName
                      + "' role '" + role.value()
                      + "' runtime '" + runtimeRef
                      + "' must be CONTAINER, JVM, or EMBER, got " + runtimeType.value());
        }
    }

    private static void validatePortConflictsOnSameHost(String name, SourceProfile source, List<String> errors) {
        if (source.type() != SourceType.SSH) {
            return;
        }

        var hostsSeen = new HashSet<String>();
        var duplicates = new HashSet<String>();

        source.roles()
              .values()
              .forEach(sub -> sub.hosts()
                                 .onPresent(hosts -> collectDuplicateHosts(hosts, hostsSeen, duplicates)));
        duplicates.forEach(host -> errors.add("PF-09: SSH source '" + name
                                             + "' has host '" + host
                                             + "' in multiple role sub-tables (port conflict risk)"));
    }

    private static void collectDuplicateHosts(List<String> hosts, Set<String> seen, Set<String> duplicates) {
        hosts.stream().filter(host -> !seen.add(host)).forEach(duplicates::add);
    }

    private static void validatePortDistinctness(ClusterBootstrapConfig config, List<String> errors) {
        var ports = config.operations().ports();
        var portValues = new HashSet<Integer>();
        var portNames = new ArrayList<String>();

        addPort(ports.cluster(), "cluster", portValues, portNames, errors);
        addPort(ports.management(), "management", portValues, portNames, errors);
        addPort(ports.appHttp(), "appHttp", portValues, portNames, errors);
        addPort(ports.swim(), "swim", portValues, portNames, errors);
    }

    private static void addPort(int port, String name, Set<Integer> seen, List<String> names, List<String> errors) {
        if (port < 1 || port > 65535) {
            errors.add("CL-11: Port '" + name + "' value " + port + " is out of range 1-65535");
        }

        if (!seen.add(port)) {
            errors.add("CL-11: Port '" + name + "' value " + port + " conflicts with another port");
        }
    }

    /// A declared `allow_ingress` is DENY-BY-DEFAULT for everything it does not list, while bootstrap
    /// reaches each node TWICE over its public address: DEPLOY_RUNTIME installs over **SSH (22)**, then
    /// the readiness gate polls the **management API (default 8080)**. Omitting either locks bootstrap
    /// out of the machines it just created.
    ///
    /// Both observed live on 2026-08-05, on correctly-provisioned nodes, purely because the firewall
    /// was doing its job:
    ///   - no 22   → `SSH preflight failed: 3 host(s) unreachable after 300s`
    ///   - no 8080 → `Cloud-init did not finish on 3 node(s)` — the readiness gate, misattributed
    ///
    /// The management port is deliberately NOT auto-opened: REQ-5.1.8.3 makes it operator-managed.
    /// A WARNING rather than an error: a pre-baked image or an out-of-band agent may need neither.
    private static void checkFirewallAllowsBootstrapPorts(String name,
                                                          SourceProfile source,
                                                          int managementPort,
                                                          List<String> warnings) {
        if (source.type() != SourceType.CLOUD || source.firewallRules().isEmpty()) {
            return;
        }

        warnIfPortClosed(name,
                         source,
                         SSH_PORT,
                         warnings,
                         "bootstrap deploys the runtime over SSH, so DEPLOY_RUNTIME will fail with"
                        + " 'SSH preflight failed: host(s) unreachable'");
        warnIfPortClosed(name,
                         source,
                         managementPort,
                         warnings,
                         "the bootstrap readiness gate polls the management API on each node's PUBLIC"
                        + " address, so DEPLOY_RUNTIME will fail with 'Cloud-init did not finish on N"
                        + " node(s)' even though the nodes booted correctly");
    }

    private static void warnIfPortClosed(String name,
                                         SourceProfile source,
                                         int port,
                                         List<String> warnings,
                                         String consequence) {
        var open = source.firewallRules()
                         .stream()
                         .anyMatch(rule -> rule.port() == port && !"udp".equals(rule.protocol()));

        if (!open) {
            warnings.add("Source '" + name
                        + "' declares [source." + name
                        + ".firewall] but no rule opens port " + port
                        + "/tcp. Ingress is deny-by-default, and " + consequence
                        + ". Add a rule for port " + port
                        + " (scope source_cidr to your operator network).");
        }
    }

    private static final int SSH_PORT = 22;

    private static void checkCoreMajorityWarning(ClusterBootstrapConfig config, List<String> warnings) {
        var totalCores = config.derivedCoreCount();

        if (totalCores == 0) {
            return;
        }

        var majority = totalCores / 2;

        config.sources()
              .forEach((name, source) -> checkSourceCoreMajority(name, source, majority, totalCores, warnings));
    }

    private static void checkSourceCoreMajority(String name,
                                                SourceProfile source,
                                                int majority,
                                                int totalCores,
                                                List<String> warnings) {
        Option.option(source.roles().get(NodeRole.CORE)).onPresent(coreRole -> checkCoreCount(name,
                                                                                              coreRole,
                                                                                              majority,
                                                                                              totalCores,
                                                                                              warnings));
    }

    private static void checkCoreCount(String name,
                                       RoleSubTable coreRole,
                                       int majority,
                                       int totalCores,
                                       List<String> warnings) {
        var count = coreRole.count().or(0) + coreRole.hosts().map(List::size).or(0);

        if (count > majority) {
            warnings.add("CL-13: Source '" + name + "' holds " + count + " of " + totalCores + " cores (majority risk)");
        }
    }

    private static void checkCapacityMajorityWarning(ClusterBootstrapConfig config, List<String> warnings) {
        var totalCapacity = 0;
        var perSource = new ArrayList<Map.Entry<String, Integer>>();

        for (var entry : config.sources().entrySet()) {
            var capacity = countNonSpotCapacity(entry.getValue());

            totalCapacity += capacity;
            perSource.add(Map.entry(entry.getKey(), capacity));
        }

        if (totalCapacity == 0) {
            return;
        }

        var half = totalCapacity / 2;
        var finalTotal = totalCapacity;

        perSource.stream()
                 .filter(entry -> entry.getValue() > half)
                 .forEach(entry -> warnings.add("CL-14: Source '" + entry.getKey()
                                               + "' holds " + entry.getValue()
                                               + " of " + finalTotal
                                               + " non-spot nodes (capacity concentration risk)"));
    }

    private static int countNonSpotCapacity(SourceProfile source) {
        return source.roles()
                     .entrySet()
                     .stream()
                     .filter(e -> e.getKey() != NodeRole.SPOT)
                     .mapToInt(e -> e.getValue()
                                     .count()
                                     .or(0) + e.getValue()
                                               .hosts()
                                               .map(List::size)
                                               .or(0))
                     .sum();
    }
}
