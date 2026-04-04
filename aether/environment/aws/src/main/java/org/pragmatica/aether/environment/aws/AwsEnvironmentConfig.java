package org.pragmatica.aether.environment.aws;

import org.pragmatica.cloud.aws.AwsConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Configuration for the AWS environment integration.
/// Contains AWS API credentials and default EC2 provisioning parameters.
public record AwsEnvironmentConfig(AwsConfig awsConfig,
                                   String amiId,
                                   String instanceType,
                                   Option<String> keyName,
                                   List<String> securityGroupIds,
                                   String subnetId,
                                   String userData,
                                   Option<AwsLbConfig> loadBalancer,
                                   Option<String> clusterName,
                                   long discoveryPollIntervalMs,
                                   Option<String> certificateSecretPrefix) {
    private static final long DEFAULT_POLL_INTERVAL_MS = 30_000L;

    public record AwsLbConfig(String targetGroupArn) {
        public static Result<AwsLbConfig> awsLbConfig(String targetGroupArn) {
            return success(new AwsLbConfig(targetGroupArn));
        }
    }

    public static Result<AwsEnvironmentConfig> awsEnvironmentConfig(AwsConfig awsConfig,
                                                                    String amiId,
                                                                    String instanceType,
                                                                    Option<String> keyName,
                                                                    List<String> securityGroupIds,
                                                                    String subnetId,
                                                                    String userData) {
        return success(new AwsEnvironmentConfig(awsConfig,
                                                amiId,
                                                instanceType,
                                                keyName,
                                                List.copyOf(securityGroupIds),
                                                subnetId,
                                                userData,
                                                Option.empty(),
                                                Option.empty(),
                                                DEFAULT_POLL_INTERVAL_MS,
                                                Option.empty()));
    }

    public static Result<AwsEnvironmentConfig> awsEnvironmentConfig(AwsConfig awsConfig,
                                                                    String amiId,
                                                                    String instanceType,
                                                                    Option<String> keyName,
                                                                    List<String> securityGroupIds,
                                                                    String subnetId,
                                                                    String userData,
                                                                    AwsLbConfig loadBalancer) {
        return success(new AwsEnvironmentConfig(awsConfig,
                                                amiId,
                                                instanceType,
                                                keyName,
                                                List.copyOf(securityGroupIds),
                                                subnetId,
                                                userData,
                                                some(loadBalancer),
                                                Option.empty(),
                                                DEFAULT_POLL_INTERVAL_MS,
                                                Option.empty()));
    }

    @SuppressWarnings("JBCT-VO-02") public AwsEnvironmentConfig withDiscovery(String clusterLabel) {
        return new AwsEnvironmentConfig(awsConfig,
                                        amiId,
                                        instanceType,
                                        keyName,
                                        securityGroupIds,
                                        subnetId,
                                        userData,
                                        loadBalancer,
                                        some(clusterLabel),
                                        discoveryPollIntervalMs,
                                        certificateSecretPrefix);
    }

    @SuppressWarnings("JBCT-VO-02") public AwsEnvironmentConfig withDiscoveryPollInterval(long intervalMs) {
        return new AwsEnvironmentConfig(awsConfig,
                                        amiId,
                                        instanceType,
                                        keyName,
                                        securityGroupIds,
                                        subnetId,
                                        userData,
                                        loadBalancer,
                                        clusterName,
                                        intervalMs,
                                        certificateSecretPrefix);
    }

    @SuppressWarnings("JBCT-VO-02") public AwsEnvironmentConfig withCertificateSecretPrefix(String prefix) {
        return new AwsEnvironmentConfig(awsConfig,
                                        amiId,
                                        instanceType,
                                        keyName,
                                        securityGroupIds,
                                        subnetId,
                                        userData,
                                        loadBalancer,
                                        clusterName,
                                        discoveryPollIntervalMs,
                                        some(prefix));
    }
}
