// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.CloudCredentials;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.environment.FloatingIpProvider;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) public final class ProviderResolver {
    private static final BootstrapError.ProvisionFailed NO_PROVIDER = new BootstrapError.ProvisionFailed("cloud",
                                                                                                         "No cloud provider specified in source profile");

    private static final BootstrapError.ProvisionFailed NO_COMPUTE = new BootstrapError.ProvisionFailed("cloud",
                                                                                                        "Provider does not support compute operations");

    private static final BootstrapError.ProvisionFailed NO_FLOATING_IP = new BootstrapError.ProvisionFailed("cloud",
                                                                                                            "Provider does not support floating IP operations");

    private ProviderResolver() {}

    public static Result<ComputeProvider> resolveCloudCompute(SourceProfile source) {
        return resolveCloudCompute(source, List.of(), "");
    }

    public static Result<ComputeProvider> resolveCloudCompute(SourceProfile source,
                                                              List<Long> sshKeyIds,
                                                              String userData) {
        return source.provider().toResult(NO_PROVIDER)
                              .flatMap(provider -> lookupAndCreateCloud(provider.value(),
                                                                        source,
                                                                        sshKeyIds,
                                                                        userData))
                              .flatMap(ProviderResolver::extractCompute);
    }

    public static Result<FloatingIpProvider> resolveFloatingIpProvider(SourceProfile source) {
        return source.provider().toResult(NO_PROVIDER)
                              .flatMap(provider -> lookupAndCreateCloud(provider.value(),
                                                                        source))
                              .flatMap(ProviderResolver::extractFloatingIp);
    }

    public static Result<ComputeProvider> resolveCloudComputeForCleanup(String providerName) {
        return CloudCredentials.fromEnvironment(providerName).flatMap(cloudConfig -> lookupFactory(providerName).flatMap(factory -> factory.create(cloudConfig)))
                                               .flatMap(ProviderResolver::extractCompute);
    }

    public static Result<ComputeProvider> resolveCloudComputeFromHandle(SourceCleanupHandle handle) {
        return buildHandleConfig(handle).flatMap(config -> lookupFactory(handle.provider()).flatMap(factory -> factory.create(config)))
                                .flatMap(ProviderResolver::extractCompute);
    }

    private static Result<CloudConfig> buildHandleConfig(SourceCleanupHandle handle) {
        var creds = new HashMap<String, String>();
        var missing = new ArrayList<String>();
        for (var entry : handle.credentialEnvVars().entrySet()) {
            var value = System.getenv(entry.getValue());
            if (value == null || value.isBlank()) {missing.add(entry.getValue());} else {creds.put(entry.getKey(), value);}
        }
        if (!missing.isEmpty()) {return new BootstrapError.ProvisionFailed(handle.provider(),
                                                                           "Missing credential env vars for cleanup: " + String.join(", ",
                                                                                                                                     missing)).result();}
        var compute = new HashMap<String, String>();
        handle.region().onPresent(r -> compute.put("region", r));
        return Result.success(new CloudConfig(handle.provider(),
                                              Map.copyOf(creds),
                                              Map.copyOf(compute),
                                              Map.of(),
                                              Map.of(),
                                              Map.of(),
                                              Map.of()));
    }

    public static Result<ComputeProvider> resolveDockerCompute() {
        return lookupFactory("docker").flatMap(factory -> factory.create(dockerCloudConfig()))
                            .flatMap(ProviderResolver::extractCompute);
    }

    private static Result<EnvironmentIntegration> lookupAndCreateCloud(String providerName, SourceProfile source) {
        return lookupAndCreateCloud(providerName, source, List.of(), "");
    }

    private static Result<EnvironmentIntegration> lookupAndCreateCloud(String providerName,
                                                                       SourceProfile source,
                                                                       List<Long> sshKeyIds,
                                                                       String userData) {
        return lookupFactory(providerName).flatMap(factory -> factory.create(buildCloudConfig(providerName,
                                                                                              source,
                                                                                              sshKeyIds,
                                                                                              userData)));
    }

    private static Result<EnvironmentIntegrationFactory> lookupFactory(String providerName) {
        return EnvironmentIntegrationFactory.forProvider(providerName).toResult(factoryNotFound(providerName));
    }

    private static Result<ComputeProvider> extractCompute(EnvironmentIntegration integration) {
        return integration.compute().toResult(NO_COMPUTE);
    }

    private static Result<FloatingIpProvider> extractFloatingIp(EnvironmentIntegration integration) {
        return integration.floatingIp().toResult(NO_FLOATING_IP);
    }

    private static CloudConfig buildCloudConfig(String providerName, SourceProfile source) {
        return buildCloudConfig(providerName, source, List.of(), "");
    }

    private static CloudConfig buildCloudConfig(String providerName,
                                                SourceProfile source,
                                                List<Long> sshKeyIds,
                                                String userData) {
        var credentials = new HashMap<String, String>();
        source.credentials()
                          .onPresent(c -> {
                                         credentials.put("credentials_file", c);
                                         credentials.put("api_token", c);
                                         credentials.put("access_key", c);
                                     });
        var compute = new HashMap<String, String>();
        source.region().onPresent(r -> compute.put("region", r));
        source.zone().onPresent(z -> compute.put("zone", z));
        if (!sshKeyIds.isEmpty()) {compute.put("ssh_key_ids", joinLongs(sshKeyIds));}
        if (!userData.isEmpty()) {compute.put("user_data", userData);}
        return new CloudConfig(providerName,
                               Map.copyOf(credentials),
                               Map.copyOf(compute),
                               Map.of(),
                               Map.of(),
                               Map.of(),
                               Map.of());
    }

    private static String joinLongs(List<Long> ids) {
        return ids.stream().map(String::valueOf)
                         .collect(Collectors.joining(","));
    }

    private static CloudConfig dockerCloudConfig() {
        return new CloudConfig("docker", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static BootstrapError.ProvisionFailed factoryNotFound(String providerName) {
        return new BootstrapError.ProvisionFailed(providerName,
                                                  "No EnvironmentIntegrationFactory found for provider '" + providerName + "'");
    }
}
