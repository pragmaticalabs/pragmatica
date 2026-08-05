// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.CloudCredentials;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.environment.FloatingIpProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public final class ProviderResolver {
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
        return resolveCloudCompute(source, sshKeyIds, userData, "", List.of());
    }

    /// Cluster-aware resolution. `clusterName` is REQUIRED for ingress management (the provider
    /// refuses to create a firewall it could not later find), and `firewallIds` carry the ids the
    /// firewall phase created into server-create.
    public static Result<ComputeProvider> resolveCloudCompute(SourceProfile source,
                                                              List<Long> sshKeyIds,
                                                              String userData,
                                                              String clusterName,
                                                              List<Long> firewallIds) {
        return source.provider()
                     .toResult(NO_PROVIDER)
                     .flatMap(provider -> lookupAndCreateCloud(provider.value(),
                                                               source,
                                                               sshKeyIds,
                                                               userData,
                                                               clusterName,
                                                               firewallIds))
                     .flatMap(ProviderResolver::extractCompute);
    }

    /// Seam shape for [BootstrapPhaseFirewall]: ingress needs the cluster but never pre-existing
    /// firewall ids (it is what creates them).
    static Result<ComputeProvider> resolveCloudCompute(SourceProfile source,
                                                       List<Long> sshKeyIds,
                                                       String userData,
                                                       String clusterName) {
        return resolveCloudCompute(source, sshKeyIds, userData, clusterName, List.of());
    }

    public static Result<FloatingIpProvider> resolveFloatingIpProvider(SourceProfile source) {
        return source.provider()
                     .toResult(NO_PROVIDER)
                     .flatMap(provider -> lookupAndCreateCloud(provider.value(),
                                                               source))
                     .flatMap(ProviderResolver::extractFloatingIp);
    }

    public static Result<ComputeProvider> resolveCloudComputeForCleanup(String providerName) {
        return CloudCredentials.fromEnvironment(providerName)
                               .flatMap(cloudConfig -> lookupFactory(providerName).flatMap(factory -> factory.create(cloudConfig)))
                               .flatMap(ProviderResolver::extractCompute);
    }

    public static Result<ComputeProvider> resolveCloudComputeFromHandle(SourceCleanupHandle handle) {
        return buildHandleConfig(handle).flatMap(config -> lookupFactory(handle.provider()).flatMap(factory -> factory.create(config)))
                                .flatMap(ProviderResolver::extractCompute);
    }

    /// #521 — an EMPTY `credentialEnvVars` used to report success here: the loop never ran, so `missing`
    /// stayed empty and a `CloudConfig` with no credentials at all was handed to the provider factory,
    /// which then failed with the misleading "Cloud credentials missing for provider 'hetzner': set
    /// HCLOUD_TOKEN" — pointing the operator at an env var that WAS set. A cloud cleanup handle that names
    /// no credential cannot produce one; say that, at the point where it is knowable.
    private static Result<CloudConfig> buildHandleConfig(SourceCleanupHandle handle) {
        if (handle.credentialEnvVars().isEmpty()) {
            return new BootstrapError.ProvisionFailed(handle.provider(),
                                                      "Persisted cleanup handle names no credential env var, so no"
                                                     + " credentials can be re-derived from it. Callers must fall back"
                                                     + " to raw provider env credentials rather than resolving from"
                                                     + " this handle.").result();
        }

        var creds = new HashMap<String, String>();
        var missing = new ArrayList<String>();

        for (var entry : handle.credentialEnvVars().entrySet()) {
            var value = System.getenv(entry.getValue());

            if (value == null || value.isBlank()) {
                missing.add(entry.getValue());
            } else {
                creds.put(entry.getKey(), value);
            }
        }

        if (!missing.isEmpty()) {
            return new BootstrapError.ProvisionFailed(handle.provider(),
                                                      "Missing credential env vars for cleanup: " + String.join(", ",
                                                                                                                missing)).result();
        }

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
        return lookupAndCreateCloud(providerName, source, List.of(), "", "", List.of());
    }

    private static Result<EnvironmentIntegration> lookupAndCreateCloud(String providerName,
                                                                       SourceProfile source,
                                                                       List<Long> sshKeyIds,
                                                                       String userData,
                                                                       String clusterName,
                                                                       List<Long> firewallIds) {
        return lookupFactory(providerName).flatMap(factory -> factory.create(buildCloudConfig(providerName,
                                                                                              source,
                                                                                              sshKeyIds,
                                                                                              userData,
                                                                                              clusterName,
                                                                                              firewallIds)));
    }

    private static Result<EnvironmentIntegrationFactory> lookupFactory(String providerName) {
        return EnvironmentIntegrationFactory.forProvider(providerName).toResult(factoryNotFound(providerName));
    }

    private static Result<ComputeProvider> extractCompute(EnvironmentIntegration integration) {
        return integration.compute()
                          .toResult(NO_COMPUTE);
    }

    private static Result<FloatingIpProvider> extractFloatingIp(EnvironmentIntegration integration) {
        return integration.floatingIp()
                          .toResult(NO_FLOATING_IP);
    }

    private static CloudConfig buildCloudConfig(String providerName, SourceProfile source) {
        return buildCloudConfig(providerName, source, List.of(), "");
    }

    /// Package-private (not private) so `ProviderResolverTest` can assert the resolved compute map
    /// directly — the public `resolveCloudCompute` returns an opaque [ComputeProvider], hiding the
    /// `[cloud.compute]` map this method threads `server_type`/`ssh_key_ids` into.
    ///
    /// RFC-0016 W2 — no `image` is stamped here: the per-role VM image is threaded as tier-1
    /// `ProvisionSpec.imageId` (see `BootstrapPhaseProvision.buildCloudProvisionSpec`), so a role
    /// without its own image never inherits the core role's (which core-stamps-all did).
    static CloudConfig buildCloudConfig(String providerName,
                                        SourceProfile source,
                                        List<Long> sshKeyIds,
                                        String userData) {
        return buildCloudConfig(providerName, source, sshKeyIds, userData, "", List.of());
    }

    /// `clusterName` lands in the `discovery` map (the key `HetznerEnvironmentIntegrationFactory`
    /// already reads) so the resolved provider knows its own cluster. Ingress management REQUIRES
    /// it: a firewall created without the `aether-cluster` label is invisible to
    /// `tools/cloud-reaper.sh` and leaks as a paid resource, so `openIngress` refuses without it.
    ///
    /// `firewallIds` are the ids [BootstrapPhaseFirewall] just created, threaded into
    /// `[cloud.compute] firewall_ids` — the SAME key an operator uses for a pre-existing firewall,
    /// consumed at `HetznerComputeProvider.buildCreateRequest`. Passing them at server-CREATE is
    /// what closes the window in which a node would be up and unfirewalled (§6.2).
    static CloudConfig buildCloudConfig(String providerName,
                                        SourceProfile source,
                                        List<Long> sshKeyIds,
                                        String userData,
                                        String clusterName,
                                        List<Long> firewallIds) {
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
        coreInstanceType(source).onPresent(t -> compute.put("server_type", t));
        if (!sshKeyIds.isEmpty()) {
            compute.put("ssh_key_ids", joinLongs(sshKeyIds));
        }

        if (!userData.isEmpty()) {
            compute.put("user_data", userData);
        }

        if (!firewallIds.isEmpty()) {
            compute.put("firewall_ids", joinLongs(firewallIds));
        }

        var discovery = clusterName.isEmpty()
                        ? Map.<String, String> of()
                        : Map.of("cluster_name", clusterName);

        return new CloudConfig(providerName,
                               Map.copyOf(credentials),
                               Map.copyOf(compute),
                               Map.of(),
                               discovery,
                               Map.of(),
                               Map.of());
    }

    private static String joinLongs(List<Long> ids) {
        return ids.stream()
                  .map(String::valueOf)
                  .collect(Collectors.joining(","));
    }

    /// #442 — the source's core-role `instance_type`, threaded into the provider's `[cloud.compute]
    /// server_type` so `config.serverType()` is a meaningful fallback when a [ProvisionSpec] carries
    /// no concrete type. Mirrors `BootstrapOverlayGenerator.coreInstanceType` (the value rendered into
    /// each node's runtime config), so the bootstrap-time provider and a running leader agree.
    private static Option<String> coreInstanceType(SourceProfile source) {
        return Option.option(source.roles().get(NodeRole.CORE)).flatMap(RoleSubTable::instanceType);
    }

    private static CloudConfig dockerCloudConfig() {
        return new CloudConfig("docker", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static BootstrapError.ProvisionFailed factoryNotFound(String providerName) {
        return new BootstrapError.ProvisionFailed(providerName,
                                                  "No EnvironmentIntegrationFactory found for provider '" + providerName
                                                 + "'");
    }
}
