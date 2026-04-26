// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Parses cluster bootstrap TOML config into [ClusterBootstrapConfig]. Section 3, Section 4, Section 5
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) public final class ClusterBootstrapConfigParser {
    private ClusterBootstrapConfigParser() {}

    private static final String REQUIRED_CONFIG_VERSION = "1.0.0";

    private static final String CLUSTER_SECTION = "cluster";

    private static final String CLUSTER_CORE_SECTION = "cluster.core";

    private static final String INFRA_NETWORKING_SECTION = "infrastructure.networking";

    private static final String OPERATIONS_SECTION = "operations";

    private static final String OPERATIONS_AUTO_HEAL_SECTION = "operations.auto_heal";

    private static final String OPERATIONS_TLS_SECTION = "operations.tls";

    private static final String OPERATIONS_TIMEOUTS_SECTION = "operations.timeouts";

    private static final String OPERATIONS_PORTS_SECTION = "operations.ports";

    private static final String SOURCE_PREFIX = "source.";

    private static final String RUNTIME_PREFIX = "runtime.";

    private static final String FIREWALL_SUFFIX = ".firewall";

    private static final String DATABASES_PREFIX = "databases.";

    private static final Set<String> ROLE_NAMES = Set.of("core", "worker", "spot");

    public static Result<ClusterBootstrapConfig> parseFile(Path path) {
        return TomlParser.parseFile(path).mapError(ClusterBootstrapConfigParser::wrapError)
                                   .flatMap(ClusterBootstrapConfigParser::validateConfigVersion)
                                   .flatMap(doc -> resolveIncludes(doc, path))
                                   .flatMap(resolved -> TemplateInheritanceResolver.resolve(resolved)
                                                                                           .mapError(ClusterBootstrapConfigParser::wrapError))
                                   .flatMap(ClusterBootstrapConfigParser::fromDocument);
    }

    private static Result<TomlDocument> resolveIncludes(TomlDocument doc, Path path) {
        return IncludeResolver.resolve(doc, path.getParent()).mapError(ClusterBootstrapConfigParser::wrapError);
    }

    public static Result<ClusterBootstrapConfig> parse(String content) {
        return TomlParser.parse(content).mapError(ClusterBootstrapConfigParser::wrapError)
                               .flatMap(resolved -> TemplateInheritanceResolver.resolve(resolved)
                                                                                       .mapError(ClusterBootstrapConfigParser::wrapError))
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

    private static Result<String> parseConfigVersion(TomlDocument doc) {
        return doc.getString("", "config_version").toResult(parseFailed("Missing required field: config_version"))
                            .flatMap(ClusterBootstrapConfigParser::validateVersionValue);
    }

    private static Result<String> validateVersionValue(String version) {
        return REQUIRED_CONFIG_VERSION.equals(version)
              ? success(version)
              : parseFailed("Unsupported config_version: '" + version + "'. Expected '" + REQUIRED_CONFIG_VERSION + "'").result();
    }

    private static Result<TomlDocument> validateConfigVersion(TomlDocument doc) {
        return parseConfigVersion(doc).map(_ -> doc);
    }

    private static Result<ClusterIdentity> parseClusterIdentity(TomlDocument doc) {
        var name = doc.getString(CLUSTER_SECTION, "name").toResult(parseFailed("Missing required field: cluster.name"));
        var version = doc.getString(CLUSTER_SECTION, "version")
                                   .toResult(parseFailed("Missing required field: cluster.version"));
        return Result.all(name, version).map(ClusterIdentity::clusterIdentity);
    }

    private static CoreTopology parseCoreTopology(TomlDocument doc) {
        if (!doc.hasSection(CLUSTER_CORE_SECTION)) {return CoreTopology.defaultCoreTopology();}
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
            if (result.isFailure()) {return result.map(_ -> sources);}
            result.onSuccess(profile -> sources.put(name, profile));
        }
        return success(sources);
    }

    private static Set<String> collectSourceNames(Set<String> sectionNames) {
        var names = new LinkedHashMap<String, Boolean>();
        for (var section : sectionNames) {
            if (!section.startsWith(SOURCE_PREFIX)) {continue;}
            var rest = section.substring(SOURCE_PREFIX.length());
            var dotIndex = rest.indexOf('.');
            if (dotIndex <0) {names.put(rest, true);} else {names.put(rest.substring(0, dotIndex), true);}
        }
        return names.keySet();
    }

    private static Result<SourceProfile> parseOneSource(TomlDocument doc, String name) {
        var parentSection = SOURCE_PREFIX + name;
        return parseSourceType(doc, parentSection).map(type -> buildSourceProfile(doc, name, parentSection, type));
    }

    private static Result<SourceType> parseSourceType(TomlDocument doc, String section) {
        return doc.getString(section, "type").toResult(parseFailed("Missing required field: " + section + ".type"))
                            .flatMap(SourceType::sourceType)
                            .mapError(cause -> parseFailed(cause.message()));
    }

    private static SourceProfile buildSourceProfile(TomlDocument doc, String name, String section, SourceType type) {
        var provider = doc.getString(section, "provider")
                                    .flatMap(raw -> CloudProviderName.cloudProviderName(raw).option());
        var credentials = doc.getString(section, "credentials");
        var region = doc.getString(section, "region");
        var zone = doc.getString(section, "zone");
        var user = doc.getString(section, "user");
        var key = doc.getString(section, "key");
        var sshPort = doc.getInt(section, "ssh_port");
        var loadBalancer = parseLoadBalancerMode(doc, section, type);
        var loadBalancerIps = doc.getStringList(section, "load_balancer_ips").or(List.of());
        var loadBalancerEndpoint = doc.getString(section, "load_balancer_endpoint");
        var databases = parseDatabases(doc, section);
        var roles = parseRoles(doc, name, type);
        var firewallRules = parseFirewallRules(doc, name);
        var nodeConfig = parseNodeConfig(doc, name);
        return SourceProfile.sourceProfile(name,
                                           type,
                                           provider,
                                           credentials,
                                           region,
                                           zone,
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
        if (sections.isEmpty()) {return Option.empty();}
        return Option.some(new TomlDocument(Map.copyOf(sections), Map.of()));
    }

    private static LoadBalancerMode parseLoadBalancerMode(TomlDocument doc, String section, SourceType type) {
        return doc.getString(section, "load_balancer").flatMap(raw -> LoadBalancerMode.loadBalancerMode(raw).option())
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
        sectionData.entrySet().stream()
                            .filter(entry -> entry.getKey().startsWith(DATABASES_PREFIX))
                            .forEach(entry -> databases.put(entry.getKey().substring(DATABASES_PREFIX.length()),
                                                            entry.getValue().toString()));
    }

    private static Map<NodeRole, RoleSubTable> parseRoles(TomlDocument doc, String sourceName, SourceType type) {
        var roles = new LinkedHashMap<NodeRole, RoleSubTable>();
        for (var roleName : ROLE_NAMES) {
            var roleSection = SOURCE_PREFIX + sourceName + "." + roleName;
            if (!doc.hasSection(roleSection)) {continue;}
            NodeRole.nodeRole(roleName)
                             .onSuccess(role -> roles.put(role,
                                                          parseRoleSubTable(doc, roleSection, role, type)));
        }
        return roles;
    }

    private static RoleSubTable parseRoleSubTable(TomlDocument doc, String section, NodeRole role, SourceType type) {
        var count = doc.getInt(section, "count");
        var hosts = doc.getStringList(section, "hosts");
        var instanceType = doc.getString(section, "instance_type");
        var runtimeRef = doc.getString(section, "runtime").or(defaultRuntimeRef(type));
        return RoleSubTable.roleSubTable(role, count, hosts, instanceType, runtimeRef);
    }

    private static String defaultRuntimeRef(SourceType type) {
        return switch (type){
            case FORGE -> "ember";
            case DOCKER -> "docker";
            default -> "default";
        };
    }

    @SuppressWarnings("unchecked") private static List<FirewallRule> parseFirewallRules(TomlDocument doc,
                                                                                        String sourceName) {
        var firewallSection = SOURCE_PREFIX + sourceName + FIREWALL_SUFFIX;
        if (!doc.hasSection(firewallSection)) {return List.of();}
        var sectionData = doc.sections().get(firewallSection);
        var rawIngress = sectionData.get("allow_ingress");
        if (rawIngress instanceof List<?> list) {return parseFirewallList((List<Map<String, Object>>) list);}
        return doc.getTableArray(firewallSection + ".allow_ingress").map(ClusterBootstrapConfigParser::parseFirewallList)
                                .or(List.of());
    }

    private static List<FirewallRule> parseFirewallList(List<Map<String, Object>> tables) {
        var rules = new ArrayList<FirewallRule>();
        for (var table : tables) {rules.add(parseOneFirewallRule(table));}
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
        if (value instanceof Integer i) {return i;}
        if (value instanceof Long l) {return l.intValue();}
        if (value instanceof String s) {return Integer.parseInt(s);}
        return 0;
    }

    private static Map<String, RuntimeProfile> parseRuntimes(TomlDocument doc) {
        var runtimes = new LinkedHashMap<String, RuntimeProfile>();
        for (var section : doc.sectionNames()) {
            if (!section.startsWith(RUNTIME_PREFIX)) {continue;}
            var name = section.substring(RUNTIME_PREFIX.length());
            if (name.contains(".")) {continue;}
            parseOneRuntime(doc, section, name).onSuccess(profile -> runtimes.put(name, profile));
        }
        return runtimes;
    }

    private static Result<RuntimeProfile> parseOneRuntime(TomlDocument doc, String section, String name) {
        return doc.getString(section, "type").toResult(parseFailed("Missing required field: " + section + ".type"))
                            .flatMap(RuntimeType::runtimeType)
                            .mapError(cause -> parseFailed(cause.message()))
                            .map(type -> buildRuntimeProfile(doc, section, name, type));
    }

    private static RuntimeProfile buildRuntimeProfile(TomlDocument doc, String section, String name, RuntimeType type) {
        var image = doc.getString(section, "image");
        var jvmArgs = doc.getString(section, "jvm_args");
        return RuntimeProfile.runtimeProfile(name, type, image, jvmArgs);
    }

    private static InfrastructureConfig parseInfrastructure(TomlDocument doc) {
        var networkingType = doc.getString(INFRA_NETWORKING_SECTION, "type").flatMap(raw -> NetworkingType.networkingType(raw)
                                                                                                                         .option())
                                          .or(NetworkingType.MANUAL);
        return InfrastructureConfig.infrastructureConfig(networkingType);
    }

    private static OperationsConfig parseOperations(TomlDocument doc) {
        if (!doc.hasSection(OPERATIONS_SECTION) && !doc.hasSection(OPERATIONS_AUTO_HEAL_SECTION) && !doc.hasSection(OPERATIONS_TLS_SECTION) && !doc.hasSection(OPERATIONS_TIMEOUTS_SECTION) && !doc.hasSection(OPERATIONS_PORTS_SECTION)) {return OperationsConfig.defaultOperationsConfig();}
        return OperationsConfig.operationsConfig(parseAutoHealSpec(doc),
                                                 parseTlsConfig(doc),
                                                 parseTimeoutsConfig(doc),
                                                 parsePortMapping(doc));
    }

    private static AutoHealSpec parseAutoHealSpec(TomlDocument doc) {
        if (doc.hasSection(OPERATIONS_AUTO_HEAL_SECTION)) {return parseAutoHealFromSection(doc);}
        return doc.getBoolean(OPERATIONS_SECTION, "auto_heal").map(ClusterBootstrapConfigParser::autoHealFromShortcut)
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
        if (!doc.hasSection(OPERATIONS_TLS_SECTION)) {return TlsDeploymentConfig.defaultTlsConfig();}
        var autoGenerate = doc.getBoolean(OPERATIONS_TLS_SECTION, "auto_generate").or(true);
        var clusterSecret = doc.getString(OPERATIONS_TLS_SECTION, "cluster_secret");
        var certTtl = doc.getString(OPERATIONS_TLS_SECTION, "cert_ttl").or("720h");
        return TlsDeploymentConfig.tlsDeploymentConfig(autoGenerate, clusterSecret, certTtl);
    }

    private static TimeoutsConfig parseTimeoutsConfig(TomlDocument doc) {
        if (!doc.hasSection(OPERATIONS_TIMEOUTS_SECTION)) {return TimeoutsConfig.defaultTimeoutsConfig();}
        var healthCheck = doc.getString(OPERATIONS_TIMEOUTS_SECTION, "health_check").or("300s");
        var quorumFormation = doc.getString(OPERATIONS_TIMEOUTS_SECTION, "quorum_formation").or("600s");
        var drain = doc.getString(OPERATIONS_TIMEOUTS_SECTION, "drain").or("120s");
        return TimeoutsConfig.timeoutsConfig(healthCheck, quorumFormation, drain);
    }

    private static PortMapping parsePortMapping(TomlDocument doc) {
        if (!doc.hasSection(OPERATIONS_PORTS_SECTION)) {return PortMapping.defaultPortMapping();}
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
