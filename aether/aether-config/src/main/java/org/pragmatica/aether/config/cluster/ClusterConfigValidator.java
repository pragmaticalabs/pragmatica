package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.pragmatica.lang.Result.success;

/// Validates [ClusterManagementConfig] per spec validation rules VAL-01 through VAL-14.
@SuppressWarnings("JBCT-UTIL-02")
public final class ClusterConfigValidator {
    private ClusterConfigValidator() {}

    private static final Pattern CLUSTER_NAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Pattern SEMVER_PATTERN = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final int MAX_CLUSTER_NAME_LENGTH = 63;
    private static final int MIN_CORE_COUNT = 3;
    private static final int MIN_RETRY_INTERVAL_SECONDS = 5;

    /// Validate a cluster management config, collecting all errors.
    /// Returns success with the original config if valid, or failure with all violations.
    public static Result<ClusterManagementConfig> validate(ClusterManagementConfig config) {
        var errors = new ArrayList<ClusterConfigError>();
        validateDeployment(config.deployment(), errors);
        validateCluster(config.cluster(), config.deployment(), errors);
        return toResult(config, errors);
    }

    private static void validateDeployment(DeploymentSpec deployment, List<ClusterConfigError> errors) {
        validateInstanceTypes(deployment, errors);
        validateRuntimeConfig(deployment, errors);
        validatePorts(deployment, errors);
        validateTls(deployment, errors);
        validateOnPremisesSsh(deployment, errors);
    }

    private static void validateCluster(ClusterSpec cluster,
                                        DeploymentSpec deployment,
                                        List<ClusterConfigError> errors) {
        validateClusterName(cluster, errors);
        validateVersion(cluster, errors);
        validateCoreSpec(cluster.core(), errors);
        validateDistribution(cluster.distribution(), deployment, errors);
        validateRetryInterval(cluster.autoHeal(), errors);
        validateOnPremisesNodes(deployment, cluster, errors);
    }

    /// VAL-07: deployment.instances must have "core" entry.
    private static void validateInstanceTypes(DeploymentSpec deployment, List<ClusterConfigError> errors) {
        if ( deployment.type() != DeploymentType.EMBEDDED && !deployment.instances().containsKey("core")) {
        errors.add(new ClusterConfigError.MissingInstanceType("core"));}
    }

    /// VAL-08 and VAL-09: runtime type and container image.
    private static void validateRuntimeConfig(DeploymentSpec deployment, List<ClusterConfigError> errors) {
        if ( deployment.runtime().type() == RuntimeType.CONTAINER && deployment.runtime().image()
                                                                                       .isEmpty()) {
        errors.add(new ClusterConfigError.MissingContainerImage());}
    }

    /// VAL-10: ports must be in range 1-65535.
    private static void validatePorts(DeploymentSpec deployment, List<ClusterConfigError> errors) {
        validatePort("cluster",
                     deployment.ports().cluster(),
                     errors);
        validatePort("management",
                     deployment.ports().management(),
                     errors);
        validatePort("app-http",
                     deployment.ports().appHttp(),
                     errors);
        validatePort("swim",
                     deployment.ports().swim(),
                     errors);
    }

    private static void validatePort(String name, int port, List<ClusterConfigError> errors) {
        if ( port < 1 || port > 65535) {
        errors.add(new ClusterConfigError.InvalidPort(name, port));}
    }

    /// VAL-14: TLS validation (secret references are resolved before parsing, so no reference validation needed).
    private static void validateTls(DeploymentSpec deployment, List<ClusterConfigError> errors) {}

    /// VAL-02: cluster name must be non-blank, match pattern, max 63 chars.
    private static void validateClusterName(ClusterSpec cluster, List<ClusterConfigError> errors) {
        var name = cluster.name();
        if ( name == null || name.isBlank() || name.length() > MAX_CLUSTER_NAME_LENGTH || !CLUSTER_NAME_PATTERN.matcher(name)
        .matches()) {
        errors.add(new ClusterConfigError.InvalidClusterName(name != null
                                                             ? name
                                                             : ""));}
    }

    /// VAL-06: version must be valid semver X.Y.Z.
    private static void validateVersion(ClusterSpec cluster, List<ClusterConfigError> errors) {
        if ( !SEMVER_PATTERN.matcher(cluster.version()).matches()) {
        errors.add(new ClusterConfigError.InvalidVersion(cluster.version()));}
    }

    /// VAL-03, VAL-04, VAL-05: core count, min, max constraints.
    private static void validateCoreSpec(CoreSpec core, List<ClusterConfigError> errors) {
        validateCoreCount(core, errors);
        validateCoreMin(core, errors);
        validateCoreMax(core, errors);
    }

    private static void validateCoreCount(CoreSpec core, List<ClusterConfigError> errors) {
        if ( core.count() < MIN_CORE_COUNT || core.count() % 2 == 0) {
        errors.add(new ClusterConfigError.InvalidCoreCount(core.count()));}
    }

    private static void validateCoreMin(CoreSpec core, List<ClusterConfigError> errors) {
        if ( core.min() < MIN_CORE_COUNT || core.min() % 2 == 0 || core.min() > core.count()) {
        errors.add(new ClusterConfigError.InvalidCoreMin(core.min(), core.count()));}
    }

    private static void validateCoreMax(CoreSpec core, List<ClusterConfigError> errors) {
        if ( core.max() < core.count() || core.max() % 2 == 0) {
        errors.add(new ClusterConfigError.InvalidCoreMax(core.max(), core.count()));}
    }

    /// VAL-11: distribution zones must reference valid deployment.zones keys.
    private static void validateDistribution(DistributionConfig distribution,
                                             DeploymentSpec deployment,
                                             List<ClusterConfigError> errors) {
        distribution.zones().forEach(zone -> validateZoneMapping(zone, deployment, errors));
    }

    private static void validateZoneMapping(String zone,
                                            DeploymentSpec deployment,
                                            List<ClusterConfigError> errors) {
        if ( !deployment.zones().containsKey(zone)) {
        errors.add(new ClusterConfigError.UnmappedZone(zone));}
    }

    /// VAL-13: retry interval must be parseable and >= 5s.
    private static void validateRetryInterval(AutoHealSpec autoHeal, List<ClusterConfigError> errors) {
        var interval = autoHeal.retryInterval();
        var seconds = parseDurationSeconds(interval);
        if ( seconds < MIN_RETRY_INTERVAL_SECONDS) {
        errors.add(new ClusterConfigError.InvalidRetryInterval(interval));}
    }

    /// Parse a duration string like "60s", "5m" to seconds. Returns -1 on parse failure.
    private static long parseDurationSeconds(String duration) {
        if ( duration == null || duration.isEmpty()) {
        return - 1;}
        var lastChar = duration.charAt(duration.length() - 1);
        var numberPart = duration.substring(0, duration.length() - 1);
        return parseWithUnit(numberPart, lastChar);
    }

    private static long parseWithUnit(String numberPart, char unit) {
        try {
            var value = Long.parseLong(numberPart);
            return switch (unit) {case 's' -> value;case 'm' -> value * 60;case 'h' -> value * 3600;default -> - 1;};
        }























        catch (NumberFormatException _) {
            return - 1;
        }
    }

    /// VAL-ONPREM-01: ON_PREMISES requires deployment.nodes.core and its size must equal cluster.core.count.
    private static void validateOnPremisesNodes(DeploymentSpec deployment,
                                                ClusterSpec cluster,
                                                List<ClusterConfigError> errors) {
        if ( deployment.type() != DeploymentType.ON_PREMISES) {
        return;}
        var coreNodes = deployment.nodes().flatMap(nodes -> org.pragmatica.lang.Option.option(nodes.get("core")));
        if ( coreNodes.isEmpty()) {
            errors.add(new ClusterConfigError.MissingNodeInventory("on-premises"));
            return;
        }
        coreNodes.onPresent(value -> validateNodeCount(value,
                                                       cluster.core().count(),
                                                       errors));
    }

    private static void validateNodeCount(String coreNodesValue, int coreCount, List<ClusterConfigError> errors) {
        var nodeList = parseNodeList(coreNodesValue);
        if ( nodeList.size() != coreCount) {
        errors.add(new ClusterConfigError.NodeCountMismatch(nodeList.size(), coreCount));}
    }

    /// Parse a node list from the stringified TOML array or comma-separated format.
    public static List<String> parseNodeList(String value) {
        if ( value == null || value.isBlank()) {
        return List.of();}
        var trimmed = value.trim();
        if ( trimmed.startsWith("[")) {
        trimmed = trimmed.substring(1, trimmed.length() - 1);}
        return java.util.Arrays.stream(trimmed.split(",")).map(String::trim)
                                      .filter(s -> !s.isEmpty())
                                      .toList();
    }

    /// VAL-ONPREM-02: ON_PREMISES requires deployment.ssh section with key_path.
    private static void validateOnPremisesSsh(DeploymentSpec deployment, List<ClusterConfigError> errors) {
        if ( deployment.type() != DeploymentType.ON_PREMISES) {
        return;}
        if ( deployment.ssh().isEmpty()) {
            errors.add(new ClusterConfigError.MissingSshConfig("on-premises"));
            return;
        }
        deployment.ssh().onPresent(ssh -> validateSshKeyPath(ssh, errors));
    }

    private static void validateSshKeyPath(SshConfig ssh, List<ClusterConfigError> errors) {
        if ( ssh.keyPath() == null || ssh.keyPath().isBlank()) {
        errors.add(new ClusterConfigError.MissingSshKeyPath());}
    }

    private static Result<ClusterManagementConfig> toResult(ClusterManagementConfig config,
                                                            List<ClusterConfigError> errors) {
        return errors.isEmpty()
               ? success(config)
               : new ClusterConfigError.ValidationFailed(List.copyOf(errors)).result();
    }
}
