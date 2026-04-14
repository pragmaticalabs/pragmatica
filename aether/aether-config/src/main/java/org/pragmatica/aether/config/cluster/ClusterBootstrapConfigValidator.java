package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.pragmatica.lang.Result.success;


/// Validates ClusterBootstrapConfig against spec section 12 rules.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) public final class ClusterBootstrapConfigValidator {
    private static final Pattern CLUSTER_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");

    private static final Pattern SEMVER_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

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
        if (errors.isEmpty()) {return success(config);}
        return new ClusterConfigError.ParseFailed(String.join("; ", errors)).result();
    }

    public static List<String> warnings(ClusterBootstrapConfig config) {
        var warnings = new ArrayList<String>();
        checkCoreMajorityWarning(config, warnings);
        checkCapacityMajorityWarning(config, warnings);
        return List.copyOf(warnings);
    }

    private static void validateClusterLevel(ClusterBootstrapConfig config, List<String> errors) {
        validateClusterName(config.cluster().name(),
                            errors);
        validateClusterVersion(config.cluster().version(),
                               errors);
        validateDerivedCoreCount(config.derivedCoreCount(), errors);
        validateAtLeastOneCoreSubTable(config, errors);
        validateSourceNamesNonEmpty(config, errors);
        validateRuntimeReferences(config, errors);
    }

    private static void validateClusterName(String name, List<String> errors) {
        if (!CLUSTER_NAME_PATTERN.matcher(name).matches()) {errors.add("CL-01: Cluster name '" + name + "' must match ^[a-z][a-z0-9-]{0,62}$");}
    }

    private static void validateClusterVersion(String version, List<String> errors) {
        if (!SEMVER_PATTERN.matcher(version).matches()) {errors.add("CL-02: Cluster version '" + version + "' must be valid semver X.Y.Z");}
    }

    private static void validateDerivedCoreCount(int coreCount, List<String> errors) {
        if (coreCount <3) {errors.add("CL-04: Derived core count " + coreCount + " must be >= 3");}
        if (coreCount % 2 == 0) {errors.add("CL-04: Derived core count " + coreCount + " must be odd");}
    }

    private static void validateAtLeastOneCoreSubTable(ClusterBootstrapConfig config, List<String> errors) {
        var hasCoreSubTable = config.sources().values()
                                            .stream()
                                            .anyMatch(s -> s.roles().containsKey(NodeRole.CORE));
        if (!hasCoreSubTable) {errors.add("CL-07: At least one source must define a core sub-table");}
    }

    private static void validateSourceNamesNonEmpty(ClusterBootstrapConfig config, List<String> errors) {
        if (config.sources().containsKey("")) {errors.add("CL-08: Source names must not be empty");}
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
        if (isImplicitRuntime(runtimeRef, sourceType)) {return;}
        if (!runtimes.containsKey(runtimeRef)) {errors.add("CL-06: Source '" + sourceName + "' role '" + role.value() + "' references unknown runtime '" + runtimeRef + "'");}
    }

    private static boolean isImplicitRuntime(String runtimeRef, SourceType sourceType) {
        if ("default".equals(runtimeRef) && (sourceType == SourceType.FORGE || sourceType == SourceType.DOCKER)) {return true;}
        if ("docker".equals(runtimeRef) && sourceType == SourceType.DOCKER) {return true;}
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
        if (min <3) {errors.add("REQ-3.3.2: core_topology.min " + min + " must be >= 3");}
        if (min % 2 == 0) {errors.add("REQ-3.3.2: core_topology.min " + min + " must be odd");}
        if (min > derivedCount) {errors.add("REQ-3.3.2: core_topology.min " + min + " must be <= derived core count " + derivedCount);}
    }

    private static void validateCoreMax(int max, int derivedCount, List<String> errors) {
        if (max % 2 == 0) {errors.add("REQ-3.3.3: core_topology.max " + max + " must be odd");}
        if (max <derivedCount) {errors.add("REQ-3.3.3: core_topology.max " + max + " must be >= derived core count " + derivedCount);}
    }

    private static void validateMaxUnavailable(int maxUnavailable, int derivedCount, List<String> errors) {
        if (maxUnavailable <1) {errors.add("REQ-3.3.7: maxUnavailable " + maxUnavailable + " must be >= 1");}
        var limit = (derivedCount - 1) / 2;
        if (maxUnavailable > limit) {errors.add("REQ-3.3.7: maxUnavailable " + maxUnavailable + " must be <= (derivedCoreCount - 1) / 2 = " + limit);}
    }

    private static void validateSources(ClusterBootstrapConfig config, List<String> errors) {
        config.sources().forEach((name, source) -> validateSource(name, source, config.runtimes(), errors));
    }

    private static void validateSource(String name,
                                       SourceProfile source,
                                       Map<String, RuntimeProfile> runtimes,
                                       List<String> errors) {
        validateRoleConstraints(name, source, errors);
        validateSpotRestriction(name, source, errors);
        validateElectedLbRestriction(name, source, errors);
        validateElectedLbHasNonSpot(name, source, errors);
        validateFirewallRules(name, source, errors);
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
        switch (type){
            case SSH -> validateSshRole(name, role, sub, errors);
            case CLOUD, FORGE, DOCKER -> validateCloudOrForgeRole(name, role, sub, errors);
        }
    }

    private static void validateSshRole(String sourceName, NodeRole role, RoleSubTable sub, List<String> errors) {
        if (sub.hosts().equals(Option.none()) && sub.count().equals(Option.none())) {errors.add("PF-10: SSH source '" + sourceName + "' role '" + role.value() + "' must have hosts");} else if (sub.count().map(c -> c > 0)
                                                                                                                                                                                                          .or(false) && sub.hosts()
                                                                                                                                                                                                                                 .equals(Option.none())) {errors.add("PF-10: SSH source '" + sourceName + "' role '" + role.value() + "' must use hosts (not count)");}
    }

    private static void validateCloudOrForgeRole(String sourceName,
                                                 NodeRole role,
                                                 RoleSubTable sub,
                                                 List<String> errors) {
        if (sub.count().equals(Option.none()) && sub.hosts().equals(Option.none())) {errors.add("PF-11: Source '" + sourceName + "' role '" + role.value() + "' must have count");} else if (sub.hosts().map(h -> !h.isEmpty())
                                                                                                                                                                                                      .or(false) && sub.count()
                                                                                                                                                                                                                             .equals(Option.none())) {errors.add("PF-11: Source '" + sourceName + "' role '" + role.value() + "' must use count (not hosts)");}
    }

    private static void validateSpotRestriction(String name, SourceProfile source, List<String> errors) {
        if (!source.roles().containsKey(NodeRole.SPOT)) {return;}
        if (source.type() != SourceType.CLOUD) {errors.add("PF-15: Spot nodes only allowed on cloud sources, found on '" + name + "' (type: " + source.type()
                                                                                                                                                           .value() + ")");}
        checkSpotProviderSupport(name, source, errors);
    }

    private static void checkSpotProviderSupport(String name, SourceProfile source, List<String> errors) {
        source.provider().filter(provider -> provider == CloudProviderName.HETZNER)
                       .onPresent(provider -> errors.add("PF-16: Provider 'hetzner' does not support spot/preemptible on source '" + name + "'"));
    }

    private static void validateElectedLbRestriction(String name, SourceProfile source, List<String> errors) {
        if (source.loadBalancer() == LoadBalancerMode.ELECTED && source.type() == SourceType.SSH) {errors.add("PF-17: Elected load balancer not supported on SSH source '" + name + "'");}
    }

    private static void validateElectedLbHasNonSpot(String name, SourceProfile source, List<String> errors) {
        if (source.loadBalancer() != LoadBalancerMode.ELECTED) {return;}
        var hasNonSpot = source.roles().keySet()
                                     .stream()
                                     .anyMatch(role -> role != NodeRole.SPOT);
        if (!hasNonSpot) {errors.add("PF-14: Source '" + name + "' with elected LB must have at least one non-spot sub-table");}
    }

    private static void validateFirewallRules(String name, SourceProfile source, List<String> errors) {
        source.firewallRules().forEach(rule -> validateSingleFirewallRule(name, rule, errors));
    }

    private static void validateSingleFirewallRule(String sourceName, FirewallRule rule, List<String> errors) {
        if (rule.port() <1 || rule.port() > 65535) {errors.add("PF-18: Firewall rule on source '" + sourceName + "' has invalid port " + rule.port());}
        if (!VALID_PROTOCOLS.contains(rule.protocol())) {errors.add("PF-18: Firewall rule on source '" + sourceName + "' has invalid protocol '" + rule.protocol() + "'");}
        if (!CIDR_PATTERN.matcher(rule.sourceCidr()).matches()) {errors.add("PF-18: Firewall rule on source '" + sourceName + "' has invalid CIDR '" + rule.sourceCidr() + "'");}
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
        return Option.option(runtimes.get(runtimeRef)).map(RuntimeProfile::type)
                            .orElse(() -> resolveImplicitRuntimeType(runtimeRef, sourceType));
    }

    private static Option<RuntimeType> resolveImplicitRuntimeType(String runtimeRef, SourceType sourceType) {
        if ("ember".equals(runtimeRef) && sourceType == SourceType.FORGE) {return Option.some(RuntimeType.EMBER);}
        if ("default".equals(runtimeRef) && sourceType == SourceType.FORGE) {return Option.some(RuntimeType.EMBER);}
        if ("default".equals(runtimeRef) && sourceType == SourceType.DOCKER) {return Option.some(RuntimeType.DOCKER);}
        return Option.none();
    }

    private static void validateRuntimeForSource(String sourceName,
                                                 NodeRole role,
                                                 String runtimeRef,
                                                 SourceType sourceType,
                                                 RuntimeType runtimeType,
                                                 List<String> errors) {
        switch (sourceType){
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
        if (runtimeType != RuntimeType.EMBER) {errors.add("PF-19: Forge source '" + sourceName + "' role '" + role.value() + "' runtime '" + runtimeRef + "' must be EMBER type, got " + runtimeType.value());}
    }

    private static void checkDockerRuntime(String sourceName,
                                           NodeRole role,
                                           String runtimeRef,
                                           RuntimeType runtimeType,
                                           List<String> errors) {
        if (runtimeType != RuntimeType.DOCKER) {errors.add("PF-20: Docker source '" + sourceName + "' role '" + role.value() + "' runtime '" + runtimeRef + "' must be DOCKER type, got " + runtimeType.value());}
    }

    private static void checkCloudRuntime(String sourceName,
                                          NodeRole role,
                                          String runtimeRef,
                                          RuntimeType runtimeType,
                                          List<String> errors) {
        if (!CLOUD_RUNTIME_TYPES.contains(runtimeType)) {errors.add("PF-21: Cloud source '" + sourceName + "' role '" + role.value() + "' runtime '" + runtimeRef + "' must be CONTAINER or JVM, got " + runtimeType.value());}
    }

    private static void checkSshRuntime(String sourceName,
                                        NodeRole role,
                                        String runtimeRef,
                                        RuntimeType runtimeType,
                                        List<String> errors) {
        if (!SSH_RUNTIME_TYPES.contains(runtimeType)) {errors.add("PF-22: SSH source '" + sourceName + "' role '" + role.value() + "' runtime '" + runtimeRef + "' must be CONTAINER, JVM, or EMBER, got " + runtimeType.value());}
    }

    private static void validatePortConflictsOnSameHost(String name, SourceProfile source, List<String> errors) {
        if (source.type() != SourceType.SSH) {return;}
        var hostsSeen = new HashSet<String>();
        var duplicates = new HashSet<String>();
        source.roles().values()
                    .forEach(sub -> sub.hosts().onPresent(hosts -> collectDuplicateHosts(hosts, hostsSeen, duplicates)));
        duplicates.forEach(host -> errors.add("PF-09: SSH source '" + name + "' has host '" + host + "' in multiple role sub-tables (port conflict risk)"));
    }

    private static void collectDuplicateHosts(List<String> hosts, Set<String> seen, Set<String> duplicates) {
        hosts.stream().filter(host -> !seen.add(host))
                    .forEach(duplicates::add);
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
        if (port <1 || port > 65535) {errors.add("CL-11: Port '" + name + "' value " + port + " is out of range 1-65535");}
        if (!seen.add(port)) {errors.add("CL-11: Port '" + name + "' value " + port + " conflicts with another port");}
    }

    private static void checkCoreMajorityWarning(ClusterBootstrapConfig config, List<String> warnings) {
        var totalCores = config.derivedCoreCount();
        if (totalCores == 0) {return;}
        var majority = totalCores / 2;
        config.sources()
                      .forEach((name, source) -> checkSourceCoreMajority(name, source, majority, totalCores, warnings));
    }

    private static void checkSourceCoreMajority(String name,
                                                SourceProfile source,
                                                int majority,
                                                int totalCores,
                                                List<String> warnings) {
        Option.option(source.roles().get(NodeRole.CORE))
                     .onPresent(coreRole -> checkCoreCount(name, coreRole, majority, totalCores, warnings));
    }

    private static void checkCoreCount(String name,
                                       RoleSubTable coreRole,
                                       int majority,
                                       int totalCores,
                                       List<String> warnings) {
        var count = coreRole.count().or(0) + coreRole.hosts().map(List::size)
                                                           .or(0);
        if (count > majority) {warnings.add("CL-13: Source '" + name + "' holds " + count + " of " + totalCores + " cores (majority risk)");}
    }

    private static void checkCapacityMajorityWarning(ClusterBootstrapConfig config, List<String> warnings) {
        var totalCapacity = 0;
        var perSource = new ArrayList<Map.Entry<String, Integer>>();
        for (var entry : config.sources().entrySet()) {
            var capacity = countNonSpotCapacity(entry.getValue());
            totalCapacity += capacity;
            perSource.add(Map.entry(entry.getKey(), capacity));
        }
        if (totalCapacity == 0) {return;}
        var half = totalCapacity / 2;
        var finalTotal = totalCapacity;
        perSource.stream().filter(entry -> entry.getValue() > half)
                        .forEach(entry -> warnings.add("CL-14: Source '" + entry.getKey() + "' holds " + entry.getValue() + " of " + finalTotal + " non-spot nodes (capacity concentration risk)"));
    }

    private static int countNonSpotCapacity(SourceProfile source) {
        return source.roles().entrySet()
                           .stream()
                           .filter(e -> e.getKey() != NodeRole.SPOT)
                           .mapToInt(e -> e.getValue().count()
                                                    .or(0) + e.getValue().hosts()
                                                                       .map(List::size)
                                                                       .or(0))
                           .sum();
    }
}
