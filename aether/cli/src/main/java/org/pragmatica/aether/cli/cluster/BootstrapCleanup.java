// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Functions.Fn1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
sealed interface BootstrapCleanup {
    record unused() implements BootstrapCleanup {}

    static Result<Unit> cleanup(BootstrapState state) {
        return cleanup(state, ProviderResolver::resolveCloudComputeForCleanup, BootstrapCleanup::defaultHetznerClient);
    }

    static Result<Unit> cleanup(BootstrapState state, Fn1<Result<ComputeProvider>, String> cloudComputeResolver) {
        return cleanup(state, cloudComputeResolver, BootstrapCleanup::defaultHetznerClient);
    }

    static Result<Unit> cleanup(BootstrapState state,
                                Fn1<Result<ComputeProvider>, String> cloudComputeResolver,
                                Fn1<Result<HetznerClient>, String> hetznerClientResolver) {
        System.out.println("Cleaning up resources for cluster '" + state.clusterName() + "'...");
        var resources = new ArrayList<>(state.createdResources());
        Collections.reverse(resources);
        var failures = collectCleanupFailures(state, resources, cloudComputeResolver, hetznerClientResolver);

        return finishCleanup(state, failures);
    }

    private static List<String> collectCleanupFailures(BootstrapState state,
                                                       List<CreatedResource> resources,
                                                       Fn1<Result<ComputeProvider>, String> cloudComputeResolver,
                                                       Fn1<Result<HetznerClient>, String> hetznerClientResolver) {
        var failures = new ArrayList<String>();

        for (var resource : resources) {
            var result = destroyResource(state, resource, cloudComputeResolver, hetznerClientResolver);
            logResourceResult(result, resource);
            var _ = result.onFailure(cause -> failures.add(resource.description() + ": " + cause.message()));
        }

        return List.copyOf(failures);
    }

    @Contract
    private static void logResourceResult(Result<Unit> result, CreatedResource resource) {
        var _ = result.onSuccess(_ -> System.out.println("  Cleaned up " + resource.description())).onFailure(cause -> System.err.println("  WARN: Failed to cleanup " + resource.description()
                                                                                                                                         + ": " + cause.message()));
    }

    private static Result<Unit> finishCleanup(BootstrapState state, List<String> failures) {
        if (!failures.isEmpty()) {return new CleanupError(String.join("; ", failures)).result();}
        return BootstrapStatePersistence.delete(state.clusterName());
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<Unit> destroyResource(BootstrapState state,
                                                CreatedResource resource,
                                                Fn1<Result<ComputeProvider>, String> cloudComputeResolver,
                                                Fn1<Result<HetznerClient>, String> hetznerClientResolver) {
        return switch (resource) {
            case CreatedResource.ProvisionedVm vm -> destroyVm(state, vm, cloudComputeResolver);
            case CreatedResource.FirewallRule rule -> deleteFirewallRule(rule);
            case CreatedResource.FloatingIpAssignment ip -> detachFloatingIp(ip);
            case CreatedResource.DockerContainer container -> removeContainer(container);
            case CreatedResource.SshDeployedConfig config -> removeRemoteConfig(config);
            case CreatedResource.SshKeyResource key -> deleteSshKey(key, hetznerClientResolver);
        };
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> deleteSshKey(CreatedResource.SshKeyResource key,
                                             Fn1<Result<HetznerClient>, String> hetznerClientResolver) {
        System.out.printf("  Deleting SSH key %d (%s) from %s...%n", key.sshKeyId(), key.name(), key.provider());

        if (!"hetzner".equals(key.provider())) {return new UnsupportedSshKeyProvider(key.provider()).result();}

        return hetznerClientResolver.apply(key.provider())
                                    .flatMap(client -> client.deleteSshKey(key.sshKeyId())
                                                             .await());
    }

    private static Result<HetznerClient> defaultHetznerClient(String providerName) {
        if (!"hetzner".equals(providerName)) {return new UnsupportedSshKeyProvider(providerName).result();}

        var token = System.getenv("HCLOUD_TOKEN");

        if (token == null || token.isBlank()) {return new HetznerCredentialsMissing().result();}

        return Result.success(HetznerClient.hetznerClient(HetznerConfig.hetznerConfig(token)));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> destroyVm(BootstrapState state,
                                          CreatedResource.ProvisionedVm vm,
                                          Fn1<Result<ComputeProvider>, String> cloudComputeResolver) {
        System.out.printf("  Destroying VM %s (provider: %s)...%n", vm.resourceId(), vm.provider());

        return resolveComputeForVm(state, vm, cloudComputeResolver).flatMap(compute -> terminateInstance(compute,
                                                                                                         vm.resourceId()));
    }

    private static Result<ComputeProvider> resolveComputeForVm(BootstrapState state,
                                                               CreatedResource.ProvisionedVm vm,
                                                               Fn1<Result<ComputeProvider>, String> cloudComputeResolver) {
        var handle = state.sources().get(vm.sourceName());

        if (handle == null) {return cloudComputeResolver.apply(vm.provider());}

        return ProviderResolver.resolveCloudComputeFromHandle(handle);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> terminateInstance(ComputeProvider compute, String resourceId) {
        return InstanceId.instanceId(resourceId).flatMap(id -> compute.terminate(id)
                                                                      .await());
    }

    private static Result<Unit> deleteFirewallRule(CreatedResource.FirewallRule rule) {
        System.out.printf("  Deleting firewall rule %s...%n", rule.resourceId());

        return Result.unitResult();
    }

    private static Result<Unit> detachFloatingIp(CreatedResource.FloatingIpAssignment ip) {
        System.out.printf("  Detaching floating IP %s from %s...%n", ip.floatingIp(), ip.targetNodeId());

        return Result.unitResult();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> removeContainer(CreatedResource.DockerContainer container) {
        System.out.printf("  Removing container %s...%n", container.containerId());

        return ProviderResolver.resolveDockerCompute().flatMap(compute -> terminateInstance(compute,
                                                                                            container.containerId()));
    }

    private static Result<Unit> removeRemoteConfig(CreatedResource.SshDeployedConfig config) {
        System.out.printf("  Removing config %s from %s...%n", config.remotePath(), config.host());

        return Result.unitResult();
    }

    record CleanupError(String detail) implements Cause {
        @Override
        public String message() {
            return "Cleanup completed with failures: " + detail;
        }
    }

    record UnsupportedSshKeyProvider(String provider) implements Cause {
        @Override
        public String message() {
            return "Unsupported SSH key provider for cleanup: '" + provider + "'";
        }
    }

    record HetznerCredentialsMissing() implements Cause {
        @Override
        public String message() {
            return "Hetzner credentials missing for SSH key cleanup: set HCLOUD_TOKEN env var";
        }
    }
}
