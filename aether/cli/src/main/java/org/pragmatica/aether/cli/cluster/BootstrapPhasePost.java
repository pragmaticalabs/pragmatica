package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapResult;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.POST_BOOTSTRAP;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) sealed interface BootstrapPhasePost {
    record unused() implements BootstrapPhasePost{}

    static Result<BootstrapResult> execute(BootstrapContext ctx) {
        ClusterBootstrapOrchestrator.logPhase(POST_BOOTSTRAP, "Finalizing cluster setup");
        activateElectedLoadBalancers(ctx);
        registerClusterLocally(ctx);
        printConnectionInfo(ctx);
        return success(buildResult(ctx));
    }

    @Contract private static void activateElectedLoadBalancers(BootstrapContext ctx) {
        for (var source : ctx.config().sources()
                                    .values()) {if (source.loadBalancer() == LoadBalancerMode.ELECTED) {System.out.printf("  Activating elected load balancer for source '%s'%n",
                                                                                                                          source.name());}}
    }

    @Contract private static void registerClusterLocally(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster()
                                    .name();
        var apiKeyEnvName = ClusterBootstrapOrchestrator.deriveApiKeyEnvName(clusterName);
        var endpoint = ctx.addresses().isEmpty()
                      ? "http://localhost:9090"
                      : "http://" + ctx.addresses().getFirst()
                                                 .publicIp();
        ClusterRegistry.load().map(registry -> registry.add(clusterName,
                                                            endpoint,
                                                            Option.some(apiKeyEnvName)))
                            .flatMap(ClusterRegistry::save)
                            .onFailure(cause -> System.err.println("Warning: failed to register cluster locally: " + cause.message()));
    }

    @Contract private static void printConnectionInfo(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster()
                                    .name();
        System.out.println();
        System.out.printf("Cluster \"%s\" bootstrapped successfully.%n", clusterName);
        System.out.printf("Nodes: %d address(es) collected%n",
                          ctx.addresses().size());
        ctx.apiKey()
                  .onPresent(key -> System.out.printf("API Key Env: %s%n",
                                                      ClusterBootstrapOrchestrator.deriveApiKeyEnvName(clusterName)));
    }

    private static BootstrapResult buildResult(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster()
                                    .name();
        var endpoint = ctx.addresses().isEmpty()
                      ? "http://localhost:9090"
                      : "http://" + ctx.addresses().getFirst()
                                                 .publicIp();
        var apiKey = ctx.apiKey().or("");
        var apiKeyEnvName = ClusterBootstrapOrchestrator.deriveApiKeyEnvName(clusterName);
        return BootstrapResult.bootstrapResult(clusterName, endpoint, apiKey, ctx.nodes(), apiKeyEnvName);
    }
}
