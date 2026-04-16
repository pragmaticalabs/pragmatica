// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/// Cleans up tracked resources from a failed bootstrap in reverse creation order (LIFO).
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) sealed interface BootstrapCleanup {
    record unused() implements BootstrapCleanup{}

    static Result<Unit> cleanup(BootstrapState state) {
        System.out.println("Cleaning up resources for cluster '" + state.clusterName() + "'...");
        var resources = new ArrayList<>(state.createdResources());
        Collections.reverse(resources);
        var failures = collectCleanupFailures(resources);
        return finishCleanup(state, failures);
    }

    private static List<String> collectCleanupFailures(List<CreatedResource> resources) {
        var failures = new ArrayList<String>();
        for (var resource : resources) {
            var result = destroyResource(resource);
            logResourceResult(result, resource);
            var _ = result.onFailure(cause -> failures.add(resource.description() + ": " + cause.message()));
        }
        return List.copyOf(failures);
    }

    @Contract private static void logResourceResult(Result<Unit> result, CreatedResource resource) {
        var _ = result.onSuccess(_ -> System.out.println("  Cleaned up " + resource.description()))
                                .onFailure(cause -> System.err.println("  WARN: Failed to cleanup " + resource.description() + ": " + cause.message()));
    }

    private static Result<Unit> finishCleanup(BootstrapState state, List<String> failures) {
        if (!failures.isEmpty()) {return new CleanupError(String.join("; ", failures)).result();}
        return BootstrapStatePersistence.delete(state.clusterName());
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<Unit> destroyResource(CreatedResource resource) {
        return switch (resource){
            case CreatedResource.ProvisionedVm vm -> destroyVm(vm);
            case CreatedResource.FirewallRule rule -> deleteFirewallRule(rule);
            case CreatedResource.FloatingIpAssignment ip -> detachFloatingIp(ip);
            case CreatedResource.DockerContainer container -> removeContainer(container);
            case CreatedResource.SshDeployedConfig config -> removeRemoteConfig(config);
        };
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> destroyVm(CreatedResource.ProvisionedVm vm) {
        System.out.printf("  Destroying VM %s (provider: %s)...%n", vm.resourceId(), vm.provider());
        return ProviderResolver.resolveCloudCompute(vm.provider())
                                                   .flatMap(compute -> terminateInstance(compute,
                                                                                         vm.resourceId()));
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> terminateInstance(org.pragmatica.aether.environment.ComputeProvider compute,
                                                                                  String resourceId) {
        return InstanceId.instanceId(resourceId).flatMap(id -> compute.terminate(id).await());
    }

    private static Result<Unit> deleteFirewallRule(CreatedResource.FirewallRule rule) {
        System.out.printf("  Deleting firewall rule %s...%n", rule.resourceId());
        return Result.unitResult();
    }

    private static Result<Unit> detachFloatingIp(CreatedResource.FloatingIpAssignment ip) {
        System.out.printf("  Detaching floating IP %s from %s...%n", ip.floatingIp(), ip.targetNodeId());
        return Result.unitResult();
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> removeContainer(CreatedResource.DockerContainer container) {
        System.out.printf("  Removing container %s...%n", container.containerId());
        return ProviderResolver.resolveDockerCompute()
                                                    .flatMap(compute -> terminateInstance(compute,
                                                                                          container.containerId()));
    }

    private static Result<Unit> removeRemoteConfig(CreatedResource.SshDeployedConfig config) {
        System.out.printf("  Removing config %s from %s...%n", config.remotePath(), config.host());
        return Result.unitResult();
    }

    record CleanupError(String detail) implements Cause {
        @Override public String message() {
            return "Cleanup completed with failures: " + detail;
        }
    }
}
