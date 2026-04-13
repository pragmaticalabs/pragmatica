package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.CLUSTER_FORMATION;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) sealed interface BootstrapPhaseFormation {
    record unused() implements BootstrapPhaseFormation{}

    static Result<BootstrapContext> execute(BootstrapContext ctx) {
        ClusterBootstrapOrchestrator.logPhase(CLUSTER_FORMATION, "Establishing cluster quorum");
        var apiKey = ClusterBootstrapOrchestrator.generateApiKey();
        System.out.printf("  API key generated (%d bytes, Base64 URL-encoded)%n",
                          ClusterBootstrapOrchestrator.API_KEY_BYTES);
        var managementPort = ctx.config().operations()
                                       .ports()
                                       .management();
        var healthTimeoutMs = ClusterBootstrapOrchestrator.parseDurationMs(ctx.config().operations()
                                                                                     .timeouts()
                                                                                     .healthCheck());
        var quorumTimeoutMs = ClusterBootstrapOrchestrator.parseDurationMs(ctx.config().operations()
                                                                                     .timeouts()
                                                                                     .quorumFormation());
        var requiredCores = ctx.config().derivedCoreCount();
        return waitForHealth(ctx.addresses(),
                             managementPort,
                             healthTimeoutMs).flatMap(_ -> waitForQuorum(ctx.addresses(),
                                                                         managementPort,
                                                                         quorumTimeoutMs,
                                                                         requiredCores))
                            .map(_ -> finalizeClusterFormation(ctx, apiKey));
    }

    private static BootstrapContext finalizeClusterFormation(BootstrapContext ctx, String apiKey) {
        var updatedCtx = ctx.withApiKey(apiKey);
        storeClusterConfig(updatedCtx);
        storeApiKey(updatedCtx, apiKey);
        return updatedCtx;
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> waitForHealth(List<NodeAddress> addresses,
                                                                              int managementPort,
                                                                              long timeoutMs) {
        if (addresses.isEmpty()) {return Result.unitResult();}
        var endpoint = addresses.getFirst().publicIp();
        var url = "http://" + endpoint + ":" + managementPort + "/health/live";
        System.out.printf("  Waiting for health check at %s (timeout: %ds)%n", url, timeoutMs / 1000);
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() <deadline) {
            var response = ClusterBootstrapOrchestrator.httpGet(url);
            if (response.isSuccess()) {
                System.out.println("  Health check passed");
                return Result.unitResult();
            }
            ClusterBootstrapOrchestrator.sleepQuietly(ClusterBootstrapOrchestrator.POLL_INTERVAL_MS);
        }
        return new BootstrapError.QuorumNotEstablished(0, 1).result();
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> waitForQuorum(List<NodeAddress> addresses,
                                                                              int managementPort,
                                                                              long timeoutMs,
                                                                              int requiredCores) {
        if (addresses.isEmpty()) {return Result.unitResult();}
        var endpoint = addresses.getFirst().publicIp();
        var url = "http://" + endpoint + ":" + managementPort + "/health/ready";
        System.out.printf("  Waiting for quorum at %s (need %d core(s), timeout: %ds)%n",
                          url,
                          requiredCores,
                          timeoutMs / 1000);
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() <deadline) {
            var response = ClusterBootstrapOrchestrator.httpGet(url);
            if (response.isSuccess()) {
                System.out.printf("  Quorum established (%d core(s) required)%n", requiredCores);
                return Result.unitResult();
            }
            ClusterBootstrapOrchestrator.sleepQuietly(ClusterBootstrapOrchestrator.POLL_INTERVAL_MS);
        }
        return new BootstrapError.QuorumNotEstablished(0, requiredCores).result();
    }

    @Contract private static void storeClusterConfig(BootstrapContext ctx) {
        if (ctx.addresses().isEmpty()) {return;}
        var endpoint = buildManagementEndpoint(ctx);
        var configJson = buildConfigJson(ctx.config());
        var result = ClusterBootstrapOrchestrator.httpPost(endpoint + "/api/cluster/config", configJson);
        var _ = result.onSuccess(_ -> System.out.println("  Cluster config stored in KV-Store"))
                                .onFailure(cause -> System.err.println("  Warning: failed to store config: " + cause.message()));
    }

    @Contract private static void storeApiKey(BootstrapContext ctx, String apiKey) {
        if (ctx.addresses().isEmpty()) {return;}
        var endpoint = buildManagementEndpoint(ctx);
        var keyJson = "{\"apiKey\":\"" + apiKey + "\"}";
        var result = ClusterBootstrapOrchestrator.httpPost(endpoint + "/api/cluster/api-key", keyJson);
        var _ = result.onSuccess(_ -> System.out.println("  API key stored"))
                                .onFailure(cause -> System.err.println("  Warning: failed to store API key: " + cause.message()));
    }

    private static String buildManagementEndpoint(BootstrapContext ctx) {
        var port = ctx.config().operations()
                             .ports()
                             .management();
        var ip = ctx.addresses().getFirst()
                              .publicIp();
        return "http://" + ip + ":" + port;
    }

    private static String buildConfigJson(ClusterBootstrapConfig config) {
        return "{\"clusterName\":\"" + config.cluster().name() + "\",\"version\":\"" + config.cluster().version() + "\"}";
    }
}
