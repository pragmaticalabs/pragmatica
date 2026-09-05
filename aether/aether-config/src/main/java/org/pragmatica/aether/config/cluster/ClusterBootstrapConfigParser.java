// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public final class ClusterBootstrapConfigParser {
    private ClusterBootstrapConfigParser() {}

    private static final String REQUIRED_CONFIG_VERSION = "1.0.0";
    private static final String CLUSTER_SECTION = "cluster";
    private static final String CLUSTER_CORE_SECTION = "cluster.core";
    private static final String INFRA_NETWORKING_SECTION = "infrastructure.networking";
    private static final String INFRA_SSH_SECTION = "infrastructure.ssh";
    private static final String OPERATIONS_SECTION = "operations";
    private static final String OPERATIONS_AUTO_HEAL_SECTION = "operations.auto_heal";
    private static final String OPERATIONS_TLS_SECTION = "operations.tls";
    private static final String OPERATIONS_TIMEOUTS_SECTION = "operations.timeouts";
    private static final String OPERATIONS_PORTS_SECTION = "operations.ports";
    /// The one true spelling of a source stanza's section prefix: `[source.<name>]`, SINGULAR. Public
    /// because readers outside the parser also address source stanzas by name — notably the CLI's
    /// bootstrap-time credential mining (`BootstrapPhaseProvision.extractStanza`), which duplicated this
    /// literal as a plural `"sources."` and consequently mined nothing from every real config (#521).
    /// Anything that needs to name a source section MUST derive it from here rather than re-spell it.
    public static final String SOURCE_PREFIX = "source.";
    private static final String RUNTIME_PREFIX = "runtime.";
    private static final String FIREWALL_SUFFIX = ".firewall";
    private static final String DATABASES_PREFIX = "databases.";
    private static final Set<String> ROLE_NAMES = Set.of("core", "worker", "spot");

    /// The single parse boundary both readers share: the CLI bootstrap AND the KV-persisted re-parse
    /// a leader/CTM does (`ClusterConfigRoutes`/`ClusterTopologyManagerRecord` call this). #480 — the
    /// W6 `config_version` gate ([#validateConfigVersion]) runs FIRST, before template inheritance
    /// resolution, so an old/newer-format config surfaces the Q1 named error rather than a
    /// template-resolution error. (A former `parseFile` overload existed but had zero callers, #479 —
    /// deleted; this is the only entry point.)
    public static Result<ClusterBootstrapConfig> parse(String content) {
        return TomlParser.parse(content)
                         .mapError(ClusterBootstrapConfigParser::wrapError)
                         .flatMap(ClusterBootstrapConfigParser::validateConfigVersion)
                         .flatMap(resolved -> TemplateInheritanceResolver.resolve(resolved).mapError(ClusterBootstrapConfigParser::wrapError))
                         .flatMap(ClusterBootstrapConfigParser::fromDocument);
    }

    public static Result<ClusterBootstrapConfig> fromDocument(TomlDocument doc) {
        return parseConfigVersion(doc).flatMap(version -> parseClusterIdentity(doc).flatMap(cluster -> buildConfig(doc,
                                                                                                                   version,
                                                                                                                   cluster)));
    }

    private static Result<ClusterBootstrapConfig> buildConfig(TomlDocument doc,
                                                              String version,
                                                              ClusterIdentity cluster) {
        var coreTopology = parseCoreTopology(doc);
        var sources = parseSources(doc);
        var runtimes = parseRuntimes(doc);
        var infrastructure = parseInfrastructure(doc);
        var operations = parseOperations(doc);

        return sources.map(s -> ClusterBootstrapConfig.clusterBootstrapConfig(version,
                                                                              cluster,
                                                                              coreTopology,
                                                                              s,
                                                                              runtimes,
                                                                              infrastructure,
                                                                              operations));
    }

    /// W6 — document-level format gate (RFC-0016 §3.5). `config_version` is the version of the whole
    /// persisted cluster-TOML format; every read (bootstrap parse AND the KV-persisted re-parse the
    /// leader/CTM does) requires an EXACT match with this build's [#REQUIRED_CONFIG_VERSION]. Absent or
    /// older → re-bootstrap; NEWER → refuse loudly (binary-rollback-after-migration: restore the
    /// pre-upgrade persisted state). There is no `absent = legacy-baseline` and no migration ladder in
    /// rc3 (designed in RFC-0016 §3.5, built only when the first real rung exists); a persisted config
    /// whose format changes (e.g. W3's SourceProfile) bumps this constant.
    private static Result<String> parseConfigVersion(TomlDocument doc) {
        return doc.getString("", "config_version")
                  .toResult(parseFailed("Persisted config has no config_version (document format version); this build requires " + REQUIRED_CONFIG_VERSION
                                       + " — re-bootstrap the cluster."))
                  .flatMap(ClusterBootstrapConfigParser::validateVersionValue);
    }

    private static Result<String> validateVersionValue(String version) {
        if (REQUIRED_CONFIG_VERSION.equals(version)) {
            return success(version);
        }

        return isNewerThanRequired(version)
               ? parseFailed("Persisted config_version '" + version
                            + "' is NEWER than this build supports (" + REQUIRED_CONFIG_VERSION
                            + "); restore the pre-upgrade persisted state — this build "
                            + "cannot read a forward-versioned config.").result()
               : parseFailed("Persisted config_version '" + version
                            + "' is not supported by this build (requires " + REQUIRED_CONFIG_VERSION
                            + ") — re-bootstrap the cluster.").result();
    }

    private static boolean isNewerThanRequired(String version) {
        return compareConfigVersion(version, REQUIRED_CONFIG_VERSION) > 0;
    }

    private static int compareConfigVersion(String left, String right) {
        var leftParts = versionParts(left);
        var rightParts = versionParts(right);

        return IntStream.range(0,
                               Math.max(leftParts.size(),
                                        rightParts.size()))
                        .map(i -> Integer.compare(partOrZero(leftParts, i),
                                                  partOrZero(rightParts, i)))
                        .filter(cmp -> cmp != 0)
                        .findFirst()
                        .orElse(0);
    }

    private static List<Integer> versionParts(String version) {
        return Arrays.stream(version.split("\\."))
                     .map(part -> Number.parseInt(part).or(0))
                     .toList();
    }

    private static int partOrZero(List<Integer> parts, int index) {
        return index < parts.size()
               ? parts.get(index)
               : 0;
    }

    private static Result<TomlDocument> validateConfigVersion(TomlDocument doc) {
        return parseConfigVersion(doc).map(_ -> doc);
    }

    private static Result<ClusterIdentity> parseClusterIdentity(TomlDocument doc) {
        var name = doc.getString(CLUSTER_SECTION, "name").toResult(parseFailed("Missing required field: cluster.name"));
        var version = doc.getString(CLUSTER_SECTION, "version")
                         .toResult(parseFailed("Missing required field: cluster.version"));

        return Result.all(name, version).flatMap(ClusterIdentity::clusterIdentity);
    }

    private static CoreTopology parseCoreTopology(TomlDocument doc) {
        if (!doc.hasSection(CLUSTER_CORE_SECTION)) {
            return CoreTopology.defaultCoreTopology();
        }

        var min = doc.getInt(CLUSTER_CORE_SECTION, "min");
        var max = doc.getInt(CLUSTER_CORE_SECTION, "max");
        var maxUnavailable = doc.getInt(CLUSTER_CORE_SECTION, "max_unavailable").or(1);

        return CoreTopology.coreTopology(min, max, maxUnavailable);
    }

    private static Result<Map<String, SourceProfile>> parseSources(TomlDocument doc) {
        var sourceNames = collectSourceNames(doc.sectionNames());
        var sources = new LinkedHashMap<String, SourceProfile>();

        for (var name : sourceNames) {
            var result = parseOneSource(doc, name);

            if (result.isFailure()) {
                return result.map(_ -> sources);
            }

            result.onSuccess(profile -> sources.put(name, profile));
        }

        return success(sources);
    }

    private static Set<String> collectSourceNames(Set<String> sectionNames) {
        var names = new LinkedHashMap<String, Boolean>();

        for (var section : sectionNames) {
            if (!section.startsWith(SOURCE_PREFIX)) {
                continue;
            }

            var rest = section.substring(SOURCE_PREFIX.length());
            var dotIndex = rest.indexOf('.');

            if (dotIndex < 0) {
                names.put(rest, true);
            } else {
                names.put(rest.substring(0, dotIndex), true);
            }
        }

        return names.keySet();
    }

    /// The parse boundary for a source's NAME. A section key is raw input — `[source.]` yields a blank
    /// one — and a blank name makes the `aether-source` label selector match everything or nothing, so
    /// it is rejected here rather than carried as a `String` to the provider edge.
    private static Result<SourceProfile> parseOneSource(TomlDocument doc, String name) {
        var parentSection = SOURCE_PREFIX + name;

        return Result.all(parseSourceName(name, parentSection), parseSourceType(doc, parentSection)).flatMap((sourceName, type) -> buildSourceProfile(doc,
                                                                                                                                                      sourceName,
                                                                                                                                                      parentSection,
                                                                                                                                                      type));
    }

    private static Result<SourceName> parseSourceName(String name, String section) {
        return SourceName.sourceName(name).mapError(cause -> parseFailed("Invalid source name in [" + section
                                                                        + "]: " + cause.message()));
    }

    private static Result<SourceType> parseSourceType(TomlDocument doc, String section) {
        return doc.getString(section, "type")
                  .toResult(parseFailed("Missing required field: " + section + ".type"))
                  .flatMap(SourceType::sourceType)
                  .mapError(cause -> parseFailed(cause.message()));
    }

    private static Result<SourceProfile> buildSourceProfile(TomlDocument doc,
                                                            SourceName name,
                                                            String section,
                                                            SourceType type) {
        return parseProvider(doc, section).map(provider -> assembleSourceProfile(doc, name, section, type, provider));
    }

    /// `provider` is optional (SSH / forge / docker sources have none) but must NOT be silently
    /// dropped on a typo. Absent → `Success(None)`; present + valid → `Success(Some)`; present +
    /// invalid → a loud `ParseFailed` naming the bad value and the valid provider names, matching
    /// how `type` fails loudly at [#parseSourceType]. Before this, an invalid provider resolved to
    /// `Option.empty()`, dropping cloud-provider identity from the source profile without a word.
    private static Result<Option<CloudProviderName>> parseProvider(TomlDocument doc, String section) {
        return doc.getString(section, "provider")
                  .fold(() -> success(none()),
                        raw -> resolveProvider(section, raw));
    }

    private static Result<Option<CloudProviderName>> resolveProvider(String section, String raw) {
        return CloudProviderName.cloudProviderName(raw)
                                .map(Option::some)
                                .mapError(cause -> parseFailed(section
                                                              + ".provider: " + cause.message()
                                                              + " (was '" + raw
                                                              + "')"));
    }

    private static SourceProfile assembleSourceProfile(TomlDocument doc,
                                                       SourceName name,
                                                       String section,
                                                       SourceType type,
                                                       Option<CloudProviderName> provider) {
        var credentials = doc.getString(section, "credentials");
        var region = doc.getString(section, "region");
        var zone = doc.getString(section, "zone");
        var zones = doc.getStringList(section, "zones").or(List.of());
        var user = doc.getString(section, "user");
        var key = doc.getString(section, "key");
        var sshPort = doc.getInt(section, "ssh_port");
        var loadBalancer = parseLoadBalancerMode(doc, section, type);
        var loadBalancerIps = doc.getStringList(section, "load_balancer_ips").or(List.of());
        var loadBalancerEndpoint = doc.getString(section, "load_balancer_endpoint");
        var databases = parseDatabases(doc, section);
        var roles = parseRoles(doc, name.value(), type);
        var firewallRules = parseFirewallRules(doc, name.value());
        var nodeConfig = parseNodeConfig(doc, name.value());

        return SourceProfile.sourceProfile(name,
                                           type,
                                           provider,
                                           credentials,
                                           region,
                                           zone,
                                           zones,
                                           user,
                                           key,
                                           sshPort,
                                           loadBalancer,
                                           loadBalancerIps,
                                           loadBalancerEndpoint,
                                           databases,
                                           roles,
                                           firewallRules,
                                           nodeConfig);
    }

    private static Option<TomlDocument> parseNodeConfig(TomlDocument doc, String sourceName) {
        var prefix = SOURCE_PREFIX + sourceName + ".node_config";
        var prefixWithDot = prefix + ".";
        var sections = new LinkedHashMap<String, Map<String, Object>>();

        for (var section : doc.sectionNames()) {
            if (section.equals(prefix)) {
                sections.put("",
                             Map.copyOf(doc.sections().get(section)));
                continue;
            }

            if (section.startsWith(prefixWithDot)) {
                var innerName = section.substring(prefixWithDot.length());

                sections.put(innerName,
                             Map.copyOf(doc.sections().get(section)));
            }
        }

        if (sections.isEmpty()) {
            return Option.empty();
        }

        return Option.some(new TomlDocument(Map.copyOf(sections), Map.of()));
    }

    private static LoadBalancerMode parseLoadBalancerMode(TomlDocument doc, String section, SourceType type) {
        return doc.getString(section, "load_balancer")
                  .flatMap(raw -> LoadBalancerMode.loadBalancerMode(raw).option())
                  .or(defaultLoadBalancerMode(type));
    }

    private static LoadBalancerMode defaultLoadBalancerMode(SourceType type) {
        return type == SourceType.FORGE
               ? LoadBalancerMode.ELECTED
               : LoadBalancerMode.NONE;
    }

    private static Map<String, String> parseDatabases(TomlDocument doc, String section) {
        var databases = new LinkedHashMap<String, String>();
        var dbSection = section + ".databases";

        if (doc.hasSection(dbSection)) {
            doc.getSection(dbSection).forEach(databases::put);

            return databases;
        }

        option(doc.sections().get(section)).onPresent(data -> extractInlineDatabases(data, databases));

        return databases;
    }

    private static void extractInlineDatabases(Map<String, Object> sectionData, Map<String, String> databases) {
        sectionData.entrySet()
                   .stream()
                   .filter(entry -> entry.getKey()
                                         .startsWith(DATABASES_PREFIX))
                   .forEach(entry -> databases.put(entry.getKey().substring(DATABASES_PREFIX.length()),
                                                   entry.getValue().toString()));
    }

    private static Map<NodeRole, RoleSubTable> parseRoles(TomlDocument doc, String sourceName, SourceType type) {
        var roles = new LinkedHashMap<NodeRole, RoleSubTable>();

        for (var roleName : ROLE_NAMES) {
            var roleSection = SOURCE_PREFIX + sourceName + "." + roleName;

            if (!doc.hasSection(roleSection)) {
                continue;
            }

            NodeRole.nodeRole(roleName).onSuccess(role -> roles.put(role,
                                                                    parseRoleSubTable(doc, roleSection, role, type)));
        }

        return roles;
    }

    private static RoleSubTable parseRoleSubTable(TomlDocument doc, String section, NodeRole role, SourceType type) {
        var count = doc.getInt(section, "count");
        var hosts = doc.getStringList(section, "hosts");
        var instanceType = doc.getString(section, "instance_type");
        var image = doc.getString(section, "image");
        var runtimeRef = doc.getString(section, "runtime").or(defaultRuntimeRef(type));

        return RoleSubTable.roleSubTable(role, count, hosts, instanceType, image, runtimeRef);
    }

    private static String defaultRuntimeRef(SourceType type) {
        return switch (type) {
            case FORGE -> "ember";
            case DOCKER -> "docker";
            default -> "default";
        };
    }

    /// Both TOML spellings of `allow_ingress` must parse:
    ///
    /// - inline array under an explicit `[source.X.firewall]` section, and
    /// - a bare array-of-tables `[[source.X.firewall.allow_ingress]]`, which needs NO
    ///   `[source.X.firewall]` header — and is exactly what `aether cluster init` writes.
    ///
    /// Gating the whole lookup on `hasSection(source.X.firewall)` dropped the second shape silently,
    /// so every wizard-generated firewall block parsed to zero rules while the file plainly contained
    /// them. That is a second inertness layer beneath #574: even once the rules were applied, the
    /// ones the wizard produced never reached [SourceProfile].
    private static List<FirewallRule> parseFirewallRules(TomlDocument doc, String sourceName) {
        var firewallSection = SOURCE_PREFIX + sourceName + FIREWALL_SUFFIX;
        var inline = inlineFirewallRules(doc, firewallSection);

        if (!inline.isEmpty()) {
            return inline;
        }

        return doc.getTableArray(firewallSection + ".allow_ingress")
                  .map(ClusterBootstrapConfigParser::parseFirewallList)
                  .or(List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<FirewallRule> inlineFirewallRules(TomlDocument doc, String firewallSection) {
        if (!doc.hasSection(firewallSection)) {
            return List.of();
        }

        var sectionData = doc.sections().get(firewallSection);
        var rawIngress = sectionData.get("allow_ingress");

        if (rawIngress instanceof List<?> list) {
            return parseFirewallList((List<Map<String, Object>>) list);
        }

        return List.of();
    }

    private static List<FirewallRule> parseFirewallList(List<Map<String, Object>> tables) {
        var rules = new ArrayList<FirewallRule>();

        for (var table : tables) {
            rules.add(parseOneFirewallRule(table));
        }

        return rules;
    }

    private static FirewallRule parseOneFirewallRule(Map<String, Object> table) {
        var port = toIntValue(table.get("port"));
        var protocol = String.valueOf(table.getOrDefault("protocol", "tcp"));
        var sourceCidr = String.valueOf(table.getOrDefault("source_cidr", "0.0.0.0/0"));
        var description = option(table.get("description")).map(Object::toString);

        return FirewallRule.firewallRule(port, protocol, sourceCidr, description);
    }

    private static int toIntValue(Object value) {
        if (value instanceof Integer i) {
            return i;
        }

        if (value instanceof Long l) {
            return l.intValue();
        }

        if (value instanceof String s) {
            return Integer.parseInt(s);
        }

        return 0;
    }

    private static Map<String, RuntimeProfile> parseRuntimes(TomlDocument doc) {
        var runtimes = new LinkedHashMap<String, RuntimeProfile>();

        for (var section : doc.sectionNames()) {
            if (!section.startsWith(RUNTIME_PREFIX)) {
                continue;
            }

            var name = section.substring(RUNTIME_PREFIX.length());

            if (name.contains(".")) {
                continue;
            }

            parseOneRuntime(doc, section, name).onSuccess(profile -> runtimes.put(name, profile));
        }

        return runtimes;
    }

    private static Result<RuntimeProfile> parseOneRuntime(TomlDocument doc, String section, String name) {
        return doc.getString(section, "type")
                  .toResult(parseFailed("Missing required field: " + section + ".type"))
                  .flatMap(RuntimeType::runtimeType)
                  .mapError(cause -> parseFailed(cause.message()))
                  .map(type -> buildRuntimeProfile(doc, section, name, type));
    }

    private static RuntimeProfile buildRuntimeProfile(TomlDocument doc, String section, String name, RuntimeType type) {
        var image = doc.getString(section, "image");
        var jvmArgs = doc.getString(section, "jvm_args");
        var jarUrl = doc.getString(section, "jar_url");

        return RuntimeProfile.runtimeProfile(name, type, image, jvmArgs, jarUrl);
    }

    private static InfrastructureConfig parseInfrastructure(TomlDocument doc) {
        var networkingType = doc.getString(INFRA_NETWORKING_SECTION, "type")
                                .flatMap(raw -> NetworkingType.networkingType(raw).option())
                                .or(NetworkingType.MANUAL);

        return InfrastructureConfig.infrastructureConfig(networkingType, parseSshConfig(doc));
    }

    private static Option<SshDeploymentConfig> parseSshConfig(TomlDocument doc) {
        if (!doc.hasSection(INFRA_SSH_SECTION)) {
            return Option.empty();
        }

        var keyFiles = collectSshKeyFiles(doc);
        var authorizedKeys = doc.getStringList(INFRA_SSH_SECTION, "authorized_keys").or(List.of());

        return Option.some(SshDeploymentConfig.sshDeploymentConfig(keyFiles, authorizedKeys));
    }

    private static List<String> collectSshKeyFiles(TomlDocument doc) {
        var single = doc.getString(INFRA_SSH_SECTION, "public_key_file").map(s -> List.of(s)).or(List.of());
        var multiple = doc.getStringList(INFRA_SSH_SECTION, "public_key_files").or(List.of());

        if (multiple.isEmpty()) {
            return single;
        }

        return multiple;
    }

    private static OperationsConfig parseOperations(TomlDocument doc) {
        if (!doc.hasSection(OPERATIONS_SECTION) && !doc.hasSection(OPERATIONS_AUTO_HEAL_SECTION) && !doc.hasSection(OPERATIONS_TLS_SECTION) && !doc.hasSection(OPERATIONS_TIMEOUTS_SECTION) && !doc.hasSection(OPERATIONS_PORTS_SECTION)) {
            return OperationsConfig.defaultOperationsConfig();
        }

        return OperationsConfig.operationsConfig(parseAutoHealSpec(doc),
                                                 parseTlsConfig(doc),
                                                 parseTimeoutsConfig(doc),
                                                 parsePortMapping(doc));
    }

    private static AutoHealSpec parseAutoHealSpec(TomlDocument doc) {
        if (doc.hasSection(OPERATIONS_AUTO_HEAL_SECTION)) {
            return parseAutoHealFromSection(doc);
        }

        return doc.getBoolean(OPERATIONS_SECTION, "auto_heal")
                  .map(ClusterBootstrapConfigParser::autoHealFromShortcut)
                  .or(AutoHealSpec.defaultAutoHealSpec());
    }

    private static AutoHealSpec autoHealFromShortcut(boolean enabled) {
        var defaults = AutoHealSpec.defaultAutoHealSpec();

        return AutoHealSpec.autoHealSpec(enabled, defaults.retryInterval(), defaults.startupCooldown());
    }

    private static AutoHealSpec parseAutoHealFromSection(TomlDocument doc) {
        var enabled = doc.getBoolean(OPERATIONS_AUTO_HEAL_SECTION, "enabled").or(true);
        var retryInterval = doc.getString(OPERATIONS_AUTO_HEAL_SECTION, "retry_interval").or("60s");
        var startupCooldown = doc.getString(OPERATIONS_AUTO_HEAL_SECTION, "startup_cooldown").or("15s");
        var staleObservationTtl = doc.getString(OPERATIONS_AUTO_HEAL_SECTION, "stale_observation_ttl")
                                     .or(AutoHealSpec.DEFAULT_STALE_OBSERVATION_TTL);
        var quicMissPromotionThreshold = doc.getInt(OPERATIONS_AUTO_HEAL_SECTION, "quic_miss_promotion_threshold")
                                            .or(AutoHealSpec.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD);
        var provisioningTimeout = doc.getString(OPERATIONS_AUTO_HEAL_SECTION, "provisioning_timeout")
                                     .or(AutoHealSpec.DEFAULT_PROVISIONING_TIMEOUT);
        var provisionStabilityWindow = doc.getString(OPERATIONS_AUTO_HEAL_SECTION, "provision_stability_window")
                                          .or(AutoHealSpec.DEFAULT_PROVISION_STABILITY_WINDOW);
        var decommissionedRetention = doc.getString(OPERATIONS_AUTO_HEAL_SECTION, "decommissioned_retention")
                                         .or(AutoHealSpec.DEFAULT_DECOMMISSIONED_RETENTION);
        var swimHintsTtl = doc.getString(OPERATIONS_AUTO_HEAL_SECTION, "swim_hints_ttl")
                              .or(AutoHealSpec.DEFAULT_SWIM_HINTS_TTL);

        return AutoHealSpec.autoHealSpec(enabled,
                                         retryInterval,
                                         startupCooldown,
                                         staleObservationTtl,
                                         quicMissPromotionThreshold,
                                         provisioningTimeout,
                                         provisionStabilityWindow,
                                         decommissionedRetention,
                                         swimHintsTtl);
    }

    private static TlsDeploymentConfig parseTlsConfig(TomlDocument doc) {
        if (!doc.hasSection(OPERATIONS_TLS_SECTION)) {
            return TlsDeploymentConfig.defaultTlsConfig();
        }

        var autoGenerate = doc.getBoolean(OPERATIONS_TLS_SECTION, "auto_generate").or(true);
        var certTtl = doc.getString(OPERATIONS_TLS_SECTION, "cert_ttl").or("720h");

        return TlsDeploymentConfig.tlsDeploymentConfig(autoGenerate, certTtl);
    }

    private static TimeoutsConfig parseTimeoutsConfig(TomlDocument doc) {
        if (!doc.hasSection(OPERATIONS_TIMEOUTS_SECTION)) {
            return TimeoutsConfig.defaultTimeoutsConfig();
        }

        var healthCheck = doc.getString(OPERATIONS_TIMEOUTS_SECTION, "health_check").or("300s");
        var quorumFormation = doc.getString(OPERATIONS_TIMEOUTS_SECTION, "quorum_formation").or("600s");
        var drain = doc.getString(OPERATIONS_TIMEOUTS_SECTION, "drain").or("120s");

        return TimeoutsConfig.timeoutsConfig(healthCheck, quorumFormation, drain);
    }

    private static PortMapping parsePortMapping(TomlDocument doc) {
        if (!doc.hasSection(OPERATIONS_PORTS_SECTION)) {
            return PortMapping.defaultPortMapping();
        }

        var cluster = doc.getInt(OPERATIONS_PORTS_SECTION, "cluster").or(8090);
        var management = doc.getInt(OPERATIONS_PORTS_SECTION, "management").or(8080);
        var appHttp = doc.getInt(OPERATIONS_PORTS_SECTION, "app_http").or(8070);
        var swim = doc.getInt(OPERATIONS_PORTS_SECTION, "swim").or(8190);

        return PortMapping.portMapping(cluster, management, appHttp, swim);
    }

    private static ClusterConfigError.ParseFailed parseFailed(String detail) {
        return new ClusterConfigError.ParseFailed(detail);
    }

    private static ClusterConfigError.ParseFailed wrapError(Cause cause) {
        return new ClusterConfigError.ParseFailed(cause.message());
    }
}
