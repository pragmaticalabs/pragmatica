// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapResult;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.environment.FloatingIpProvider;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.POST_BOOTSTRAP;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
sealed interface BootstrapPhasePost {
    record unused() implements BootstrapPhasePost {}

    static Result<BootstrapResult> execute(BootstrapContext ctx) {
        ClusterBootstrapOrchestrator.logPhase(POST_BOOTSTRAP, "Finalizing cluster setup");
        activateElectedLoadBalancers(ctx);
        registerClusterLocally(ctx);
        printConnectionInfo(ctx);

        return success(buildResult(ctx));
    }

    @Contract
    private static void activateElectedLoadBalancers(BootstrapContext ctx) {
        for (var entry : ctx.config().sources().entrySet()) {
            var source = entry.getValue();

            if (source.loadBalancer() == LoadBalancerMode.ELECTED) {
                attachFloatingIp(sourceNameOrDefault(entry.getKey()), source, ctx);
            }
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    @Contract
    private static void attachFloatingIp(SourceName sourceName, SourceProfile source, BootstrapContext ctx) {
        var _ = ProviderResolver.resolveFloatingIpProvider(source)
                                .onSuccess(fip -> attachFirstCoreNode(fip, sourceName, source, ctx))
                                .onFailure(cause -> System.out.printf("  WARN: %s: floating IP provider not available: %s%n",
                                                                      sourceName,
                                                                      cause.message()));
    }

    @Contract
    private static void attachFirstCoreNode(FloatingIpProvider fip,
                                            SourceName sourceName,
                                            SourceProfile source,
                                            BootstrapContext ctx) {
        var targetNode = ctx.nodes()
                            .stream()
                            .filter(n -> n.nodeId()
                                          .startsWith(sourceName.value() + "-core-"))
                            .findFirst();

        targetNode.ifPresent(node -> attachLoadBalancerIps(fip, source, node.nodeId()));
    }

    @Contract
    private static void attachLoadBalancerIps(FloatingIpProvider fip, SourceProfile source, String nodeId) {
        for (var ip : source.loadBalancerIps()) {
            var _ = fip.attach(ip, nodeId)
                       .await()
                       .onSuccess(_ -> System.out.printf("  Floating IP %s attached to %s%n", ip, nodeId))
                       .onFailure(c -> System.err.println("  Warning: floating IP attach failed: " + c.message()));
        }
    }

    @Contract
    private static void registerClusterLocally(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster().name();
        var apiKeyEnvName = ClusterBootstrapOrchestrator.deriveApiKeyEnvName(clusterName);
        // #209: register the endpoint with the scheme the cluster actually serves. When TLS is
        // auto-generated the management plane is HTTPS, so the persisted endpoint (the single source of
        // truth operational commands read) must be `https://`, not a hardcoded `http://`.
        // #998: and with the PORT the management plane actually listens on — see [#managementEndpoint].
        var endpoint = managementEndpoint(ctx);

        ClusterRegistry.load()
                       .map(registry -> registry.add(clusterName.value(),
                                                     endpoint,
                                                     Option.some(apiKeyEnvName)))
                       .flatMap(ClusterRegistry::save)
                       .onFailure(cause -> System.err.println("Warning: failed to register cluster locally: " + cause.message()));
    }

    private static String managementScheme(BootstrapContext ctx) {
        return ctx.config()
                  .operations()
                  .tls()
                  .autoGenerate()
               ? "https"
               : "http";
    }

    /// #998 — ONE construction of the management endpoint, read by BOTH the persisted registry entry and
    /// the returned [BootstrapResult]. They used to be built separately and they disagreed:
    /// `buildResult` appended `operations.ports.management`, `registerClusterLocally` appended nothing.
    /// So every bootstrapped cluster was REGISTERED as `<scheme>://<ip>` with no port, and every later
    /// registry-based management call resolved that entry and targeted the scheme default — 443 under
    /// auto-generated TLS — while the management API listened on 8080. Observed on a live Hetzner cluster
    /// on 2026-09-11: `cluster destroy` could not enumerate nodes, so DRAIN_NODES and SHUTDOWN_NODES were
    /// skipped and the VMs were deleted without a graceful drain. `resolveEndpoint` is shared by
    /// `getPath`/`postPath`/`putPath`, so the same entry broke every other registry-based command too.
    ///
    /// The fix belongs HERE rather than in [ClusterHttpClient#resolveEndpoint]: the reader is shared by
    /// commands that never load a cluster config, so it cannot know this cluster's management port — and
    /// appending a default there would silently rewrite a deliberately port-less endpoint (a management
    /// plane behind a reverse proxy on 443 is a legitimate entry).
    ///
    /// The localhost branch carried a hardcoded `9090`, which is not the management port under any
    /// configuration — `PortMapping.defaultPortMapping()` is 8080 — and is now read from config like the
    /// other branch. Two constructions of one value is what let them drift; there is now one.
    static String managementEndpoint(BootstrapContext ctx) {
        var scheme = managementScheme(ctx);
        var port = ctx.config().operations().ports().management();

        return ctx.addresses()
                  .isEmpty()
               ? scheme + "://localhost:" + port
               : scheme + "://" + ctx.addresses()
                                     .getFirst()
                                     .publicIp() + ":" + port;
    }

    @Contract
    private static void printConnectionInfo(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster().name();

        System.out.println();
        System.out.printf("Cluster \"%s\" bootstrapped successfully.%n", clusterName);
        System.out.printf("Nodes: %d address(es) collected%n",
                          ctx.addresses().size());
        ctx.apiKey()
           .onPresent(key -> System.out.printf("API Key Env: %s%n",
                                               ClusterBootstrapOrchestrator.deriveApiKeyEnvName(clusterName)));
    }

    /// Package-visible so a test asserts the returned endpoint against [#managementEndpoint] — the
    /// symmetry #998 was the absence of. Nothing else calls it.
    static BootstrapResult buildResult(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster().name();
        var endpoint = managementEndpoint(ctx);
        var apiKey = ctx.apiKey().or("");
        var apiKeyEnvName = ClusterBootstrapOrchestrator.deriveApiKeyEnvName(clusterName);

        return BootstrapResult.bootstrapResult(clusterName, endpoint, apiKey, ctx.nodes(), apiKeyEnvName);
    }
}
