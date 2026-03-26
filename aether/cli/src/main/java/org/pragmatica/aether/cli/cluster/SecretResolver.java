package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.ClusterManagementConfig;
import org.pragmatica.aether.config.cluster.DeploymentSpec;
import org.pragmatica.aether.config.cluster.TlsDeploymentConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Resolves secret and environment variable references in cluster configuration.
///
/// Supported reference formats:
/// - `${secrets:xxx}` resolves to env var `AETHER_XXX` (uppercased, hyphens to underscores)
/// - `${env:VARIABLE_NAME}` resolves to env var directly
/// - Plain string values pass through unchanged
sealed interface SecretResolver {
    record unused() implements SecretResolver {}

    Pattern SECRETS_PATTERN = Pattern.compile("\\$\\{secrets:([^}]+)}");

    Pattern ENV_PATTERN = Pattern.compile("\\$\\{env:([^}]+)}");

    /// Resolve all secret references in the config, producing a config with plain values.
    static Result<ClusterManagementConfig> resolve(ClusterManagementConfig config) {
        return resolveDeploymentSecrets(config.deployment())
        .map(deployment -> ClusterManagementConfig.clusterManagementConfig(deployment, config.cluster()));
    }

    /// Resolve secrets in the deployment spec (currently only TLS cluster secret).
    private static Result<DeploymentSpec> resolveDeploymentSecrets(DeploymentSpec deployment) {
        return deployment.tls()
                         .fold(() -> success(deployment),
                               tls -> resolveTlsSecrets(tls).map(resolved -> withResolvedTls(deployment, resolved)));
    }

    private static DeploymentSpec withResolvedTls(DeploymentSpec deployment, TlsDeploymentConfig tls) {
        return DeploymentSpec.deploymentSpec(deployment.type(),
                                             deployment.instances(),
                                             deployment.runtime(),
                                             deployment.zones(),
                                             deployment.ports(),
                                             Option.some(tls),
                                             deployment.nodes(),
                                             deployment.ssh());
    }

    private static Result<TlsDeploymentConfig> resolveTlsSecrets(TlsDeploymentConfig tls) {
        return tls.clusterSecret()
                  .fold(() -> success(tls),
                        secret -> resolveValue(secret).map(resolved -> withResolvedSecret(tls, resolved)));
    }

    private static TlsDeploymentConfig withResolvedSecret(TlsDeploymentConfig tls, String resolvedSecret) {
        return TlsDeploymentConfig.tlsDeploymentConfig(tls.autoGenerate(), Option.some(resolvedSecret), tls.certTtl());
    }

    /// Resolve a single value that may contain a secret or env reference.
    static Result<String> resolveValue(String value) {
        var secretsMatcher = SECRETS_PATTERN.matcher(value);
        if (secretsMatcher.matches()) {
            return resolveSecretsReference(secretsMatcher.group(1));
        }
        var envMatcher = ENV_PATTERN.matcher(value);
        if (envMatcher.matches()) {
            return resolveEnvReference(envMatcher.group(1));
        }
        return success(value);
    }

    /// Resolve `${secrets:xxx}` by looking up `AETHER_XXX` env var.
    private static Result<String> resolveSecretsReference(String secretName) {
        var envVarName = toEnvVarName(secretName);
        return option(System.getenv(envVarName))
        .toResult(new ClusterConfigError.SecretResolutionFailed("${secrets:" + secretName + "} -> env var " + envVarName
                                                                + " not set"));
    }

    /// Resolve `${env:VARIABLE_NAME}` by direct env var lookup.
    private static Result<String> resolveEnvReference(String envVarName) {
        return option(System.getenv(envVarName))
        .toResult(new ClusterConfigError.SecretResolutionFailed("${env:" + envVarName + "} -> env var not set"));
    }

    /// Convert a secret name like "cluster-secret" to "AETHER_CLUSTER_SECRET".
    private static String toEnvVarName(String secretName) {
        return "AETHER_" + secretName.toUpperCase()
                                    .replace('-', '_');
    }
}
