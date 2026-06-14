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
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.POST_BOOTSTRAP;
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
                attachFloatingIp(entry.getKey(), source, ctx);
            }
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    @Contract
    private static void attachFloatingIp(String sourceName, SourceProfile source, BootstrapContext ctx) {
        var _ = ProviderResolver.resolveFloatingIpProvider(source).onSuccess(fip -> attachFirstCoreNode(fip,
                                                                                                        sourceName,
                                                                                                        source,
                                                                                                        ctx)).onFailure(cause -> System.out.printf("  WARN: %s: floating IP provider not available: %s%n",
                                                                                                                                                   sourceName,
                                                                                                                                                   cause.message()));
    }

    @Contract
    private static void attachFirstCoreNode(FloatingIpProvider fip,
                                            String sourceName,
                                            SourceProfile source,
                                            BootstrapContext ctx) {
        var targetNode = ctx.nodes().stream().filter(n -> n.nodeId()
                                                           .startsWith(sourceName + "-core-")).findFirst();

        targetNode.ifPresent(node -> attachLoadBalancerIps(fip, source, node.nodeId()));
    }

    @Contract
    private static void attachLoadBalancerIps(FloatingIpProvider fip, SourceProfile source, String nodeId) {
        for (var ip : source.loadBalancerIps()) {
            var _ = fip.attach(ip, nodeId).await().onSuccess(_ -> System.out.printf("  Floating IP %s attached to %s%n",
                                                                                    ip,
                                                                                    nodeId)).onFailure(c -> System.err.println("  Warning: floating IP attach failed: " + c.message()));
        }
    }

    @Contract
    private static void registerClusterLocally(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster().name();
        var apiKeyEnvName = ClusterBootstrapOrchestrator.deriveApiKeyEnvName(clusterName);
        // #209: register the endpoint with the scheme the cluster actually serves. When TLS is
        // auto-generated the management plane is HTTPS, so the persisted endpoint (the single source of
        // truth operational commands read) must be `https://`, not a hardcoded `http://`.
        var scheme = managementScheme(ctx);
        var endpoint = ctx.addresses().isEmpty()
                       ? scheme + "://localhost:9090"
                       : scheme + "://" + ctx.addresses().getFirst().publicIp();

        ClusterRegistry.load().map(registry -> registry.add(clusterName, endpoint, Option.some(apiKeyEnvName))).flatMap(ClusterRegistry::save).onFailure(cause -> System.err.println("Warning: failed to register cluster locally: " + cause.message()));
    }

    private static String managementScheme(BootstrapContext ctx) {
        return ctx.config().operations().tls().autoGenerate()
               ? "https"
               : "http";
    }

    @Contract
    private static void printConnectionInfo(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster().name();

        System.out.println();
        System.out.printf("Cluster \"%s\" bootstrapped successfully.%n", clusterName);
        System.out.printf("Nodes: %d address(es) collected%n",
                          ctx.addresses().size());
        ctx.apiKey().onPresent(key -> System.out.printf("API Key Env: %s%n",
                                                        ClusterBootstrapOrchestrator.deriveApiKeyEnvName(clusterName)));
    }

    private static BootstrapResult buildResult(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster().name();
        var mgmtPort = ctx.config().operations().ports().management();
        // #209: surface the actual scheme (HTTPS under auto-generated TLS) in the result endpoint too.
        var scheme = managementScheme(ctx);
        var endpoint = ctx.addresses().isEmpty()
                       ? scheme + "://localhost:" + mgmtPort
                       : scheme + "://" + ctx.addresses().getFirst().publicIp() + ":" + mgmtPort;
        var apiKey = ctx.apiKey().or("");
        var apiKeyEnvName = ClusterBootstrapOrchestrator.deriveApiKeyEnvName(clusterName);

        return BootstrapResult.bootstrapResult(clusterName, endpoint, apiKey, ctx.nodes(), apiKeyEnvName);
    }
}
