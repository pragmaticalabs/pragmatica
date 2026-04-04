package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Parses `aether-cluster.toml` files into structured [ClusterManagementConfig] records.
///
/// Uses the project's zero-dependency TOML parser and maps sections to typed records.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) public final class ClusterConfigParser {
    private ClusterConfigParser() {}

    private static final String DEPLOYMENT = "deployment";

    private static final String DEPLOYMENT_INSTANCES = "deployment.instances";

    private static final String DEPLOYMENT_RUNTIME = "deployment.runtime";

    private static final String DEPLOYMENT_ZONES = "deployment.zones";

    private static final String DEPLOYMENT_PORTS = "deployment.ports";

    private static final String DEPLOYMENT_TLS = "deployment.tls";

    private static final String DEPLOYMENT_NODES = "deployment.nodes";

    private static final String DEPLOYMENT_SSH = "deployment.ssh";

    private static final String CLUSTER = "cluster";

    private static final String CLUSTER_CORE = "cluster.core";

    private static final String CLUSTER_WORKERS = "cluster.workers";

    private static final String CLUSTER_DISTRIBUTION = "cluster.distribution";

    private static final String CLUSTER_AUTO_HEAL = "cluster.auto_heal";

    private static final String CLUSTER_UPGRADE = "cluster.upgrade";

    public static Result<ClusterManagementConfig> parseFile(Path path) {
        return TomlParser.parseFile(path).mapError(ClusterConfigParser::wrapParseError)
                                   .flatMap(ClusterConfigParser::fromDocument);
    }

    public static Result<ClusterManagementConfig> parse(String content) {
        return TomlParser.parse(content).mapError(ClusterConfigParser::wrapParseError)
                               .flatMap(ClusterConfigParser::fromDocument);
    }

    public static Result<ClusterManagementConfig> fromDocument(TomlDocument doc) {
        return Result.all(parseDeploymentSpec(doc), parseClusterSpec(doc)).map(ClusterManagementConfig::new);
    }

    private static Result<DeploymentSpec> parseDeploymentSpec(TomlDocument doc) {
        return parseDeploymentType(doc).map(type -> buildDeploymentSpec(doc, type));
    }

    private static Result<DeploymentType> parseDeploymentType(TomlDocument doc) {
        return doc.getString(DEPLOYMENT, "type").toResult(new ClusterConfigError.ParseFailed("Missing required field: deployment.type"))
                            .flatMap(DeploymentType::deploymentType)
                            .mapError(cause -> new ClusterConfigError.InvalidDeploymentType(doc.getString(DEPLOYMENT,
                                                                                                          "type")
        .or("(missing)")));
    }

    private static DeploymentSpec buildDeploymentSpec(TomlDocument doc, DeploymentType type) {
        return DeploymentSpec.deploymentSpec(type,
                                             parseInstances(doc),
                                             parseRuntimeConfig(doc),
                                             parseZones(doc),
                                             parsePorts(doc),
                                             parseTlsConfig(doc),
                                             parseNodes(doc),
                                             parseSshConfig(doc));
    }

    private static Map<String, String> parseInstances(TomlDocument doc) {
        return doc.hasSection(DEPLOYMENT_INSTANCES)
              ? doc.getSection(DEPLOYMENT_INSTANCES)
              : Map.of();
    }

    private static RuntimeConfig parseRuntimeConfig(TomlDocument doc) {
        var type = doc.getString(DEPLOYMENT_RUNTIME, "type").map(RuntimeType::runtimeType)
                                .flatMap(r -> r.option())
                                .or(RuntimeType.CONTAINER);
        var image = doc.getString(DEPLOYMENT_RUNTIME, "image");
        var jvmArgs = doc.getString(DEPLOYMENT_RUNTIME, "jvm_args").filter(s -> !s.isEmpty());
        return RuntimeConfig.runtimeConfig(type, image, jvmArgs);
    }

    private static Map<String, String> parseZones(TomlDocument doc) {
        return doc.hasSection(DEPLOYMENT_ZONES)
              ? doc.getSection(DEPLOYMENT_ZONES)
              : Map.of();
    }

    private static PortMapping parsePorts(TomlDocument doc) {
        var clusterPort = doc.getInt(DEPLOYMENT_PORTS, "cluster").or(8090);
        var managementPort = doc.getInt(DEPLOYMENT_PORTS, "management").or(8080);
        var appHttpPort = doc.getInt(DEPLOYMENT_PORTS, "app-http").or(8070);
        var swimPort = doc.getInt(DEPLOYMENT_PORTS, "swim").or(clusterPort + 100);
        return PortMapping.portMapping(clusterPort, managementPort, appHttpPort, swimPort);
    }

    private static Option<TlsDeploymentConfig> parseTlsConfig(TomlDocument doc) {
        if (!doc.hasSection(DEPLOYMENT_TLS)) {return none();}
        var autoGenerate = doc.getBoolean(DEPLOYMENT_TLS, "auto_generate").or(true);
        var clusterSecret = doc.getString(DEPLOYMENT_TLS, "cluster_secret");
        var certTtl = doc.getString(DEPLOYMENT_TLS, "cert_ttl").or("720h");
        return Option.some(TlsDeploymentConfig.tlsDeploymentConfig(autoGenerate, clusterSecret, certTtl));
    }

    private static Option<Map<String, String>> parseNodes(TomlDocument doc) {
        if (!doc.hasSection(DEPLOYMENT_NODES)) {return none();}
        return Option.some(doc.getSection(DEPLOYMENT_NODES));
    }

    private static Option<SshConfig> parseSshConfig(TomlDocument doc) {
        if (!doc.hasSection(DEPLOYMENT_SSH)) {return none();}
        var user = doc.getString(DEPLOYMENT_SSH, "user").or("root");
        var keyPath = doc.getString(DEPLOYMENT_SSH, "key_path").or("~/.ssh/id_ed25519");
        var port = doc.getInt(DEPLOYMENT_SSH, "port").or(22);
        return Option.some(SshConfig.sshConfig(user, keyPath, port));
    }

    private static Result<ClusterSpec> parseClusterSpec(TomlDocument doc) {
        var name = doc.getString(CLUSTER, "name")
                                .toResult(new ClusterConfigError.ParseFailed("Missing required field: cluster.name"));
        var version = doc.getString(CLUSTER, "version")
                                   .toResult(new ClusterConfigError.ParseFailed("Missing required field: cluster.version"));
        return Result.all(name, version).map((n, v) -> buildClusterSpec(doc, n, v));
    }

    private static ClusterSpec buildClusterSpec(TomlDocument doc, String name, String version) {
        return ClusterSpec.clusterSpec(name,
                                       version,
                                       parseCoreSpec(doc),
                                       parseWorkerSpec(doc),
                                       parseDistributionConfig(doc),
                                       parseAutoHealSpec(doc),
                                       parseUpgradeSpec(doc));
    }

    private static CoreSpec parseCoreSpec(TomlDocument doc) {
        var count = doc.getInt(CLUSTER_CORE, "count").or(3);
        var min = doc.getInt(CLUSTER_CORE, "min").or(3);
        var max = doc.getInt(CLUSTER_CORE, "max").or(count);
        return CoreSpec.coreSpec(count, min, max);
    }

    private static WorkerSpec parseWorkerSpec(TomlDocument doc) {
        return doc.getInt(CLUSTER_WORKERS, "count").map(WorkerSpec::workerSpec)
                         .or(WorkerSpec.defaultWorkerSpec());
    }

    private static DistributionConfig parseDistributionConfig(TomlDocument doc) {
        var strategy = doc.getString(CLUSTER_DISTRIBUTION, "strategy").map(DistributionStrategy::distributionStrategy)
                                    .flatMap(r -> r.option())
                                    .or(DistributionStrategy.BALANCED);
        var zones = doc.getStringList(CLUSTER_DISTRIBUTION, "zones").or(List.of());
        return DistributionConfig.distributionConfig(strategy, zones);
    }

    private static AutoHealSpec parseAutoHealSpec(TomlDocument doc) {
        var enabled = doc.getBoolean(CLUSTER_AUTO_HEAL, "enabled").or(true);
        var retryInterval = doc.getString(CLUSTER_AUTO_HEAL, "retry_interval").or("60s");
        var startupCooldown = doc.getString(CLUSTER_AUTO_HEAL, "startup_cooldown").or("15s");
        return AutoHealSpec.autoHealSpec(enabled, retryInterval, startupCooldown);
    }

    private static UpgradeSpec parseUpgradeSpec(TomlDocument doc) {
        return doc.getString(CLUSTER_UPGRADE, "strategy").map(UpgradeStrategy::upgradeStrategy)
                            .flatMap(r -> r.option())
                            .map(UpgradeSpec::upgradeSpec)
                            .or(UpgradeSpec.defaultUpgradeSpec());
    }

    private static ClusterConfigError wrapParseError(org.pragmatica.lang.Cause cause) {
        return new ClusterConfigError.ParseFailed(cause.message());
    }
}
