package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// Six-phase cluster bootstrap orchestrator. Section 8.
///
/// Phases:
/// 1. Validate -- static config validation (already done before entry, re-confirmed here)
/// 2. Provision -- create infrastructure per source type
/// 3. Collect Addresses -- gather node addresses from all sources
/// 4. Deploy Runtime -- install and start Aether on each node
/// 5. Cluster Formation -- wait for quorum, generate API key, store config
/// 6. Post-Bootstrap -- activate LBs, register locally, print info
///
/// This is the orchestration skeleton. Actual I/O calls (cloud APIs, SSH, Docker)
/// are deferred -- method bodies log intent and return placeholder results.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) public sealed interface ClusterBootstrapOrchestrator permits ClusterBootstrapOrchestrator.unused {
    record unused() implements ClusterBootstrapOrchestrator{}

    int API_KEY_BYTES = 32;

    int POLL_INTERVAL_MS = 5000;

    long DEFAULT_TIMEOUT_MS = 300_000;

    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config) {
        return bootstrap(config, false);
    }

    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config, boolean resume) {
        return BootstrapPhaseValidate.execute(config).flatMap(BootstrapPhaseProvision::execute)
                                             .flatMap(BootstrapPhaseCollect::execute)
                                             .flatMap(BootstrapPhaseDeploy::execute)
                                             .flatMap(BootstrapPhaseFormation::execute)
                                             .flatMap(BootstrapPhasePost::execute);
    }

    static String generateApiKey() {
        var bytes = new byte[API_KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                                   .encodeToString(bytes);
    }

    static String generateClusterSecret() {
        var bytes = new byte[API_KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                                   .encodeToString(bytes);
    }

    static String deriveApiKeyEnvName(String clusterName) {
        return "AETHER_" + clusterName.toUpperCase().replace('-', '_') + "_API_KEY";
    }

    @Contract static void logPhase(BootstrapPhase phase, String message) {
        System.out.printf("[Phase %d/%d: %s] %s%n",
                          phase.ordinal() + 1,
                          BootstrapPhase.values().length,
                          phase.name(),
                          message);
    }

    @Contract static void logPhase(BootstrapPhase phase, String format, Object arg) {
        logPhase(phase, String.format(format, arg));
    }

    static Result<String> httpPost(String url, String body) {
        return ClusterHttpClient.postDirect(url, body);
    }

    static Result<String> httpGet(String url) {
        return ClusterHttpClient.getDirect(url);
    }

    @SuppressWarnings("JBCT-EX-01") @Contract static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static long parseDurationMs(String duration) {
        if (duration.endsWith("s")) {return parseNumericPrefix(duration) * 1000;}
        if (duration.endsWith("m")) {return parseNumericPrefix(duration) * 60_000;}
        if (duration.endsWith("h")) {return parseNumericPrefix(duration) * 3_600_000;}
        return DEFAULT_TIMEOUT_MS;
    }

    private static long parseNumericPrefix(String duration) {
        return Result.lift(() -> Long.parseLong(duration.substring(0,
                                                                   duration.length() - 1)))
        .or(DEFAULT_TIMEOUT_MS / 1000);
    }

    record BootstrapResult(String clusterName,
                           String endpoint,
                           String apiKey,
                           List<ProvisionedNode> nodes,
                           String apiKeyEnvName) {
        static BootstrapResult bootstrapResult(String clusterName,
                                               String endpoint,
                                               String apiKey,
                                               List<ProvisionedNode> nodes,
                                               String apiKeyEnvName) {
            return new BootstrapResult(clusterName, endpoint, apiKey, List.copyOf(nodes), apiKeyEnvName);
        }
    }

    record BootstrapContext(ClusterBootstrapConfig config,
                            BootstrapState state,
                            List<ProvisionedNode> nodes,
                            List<NodeAddress> addresses,
                            Option<String> apiKey) {
        static BootstrapContext bootstrapContext(ClusterBootstrapConfig config,
                                                 BootstrapState state,
                                                 List<ProvisionedNode> nodes,
                                                 List<NodeAddress> addresses) {
            return new BootstrapContext(config, state, List.copyOf(nodes), List.copyOf(addresses), none());
        }

        BootstrapContext withNodes(List<ProvisionedNode> newNodes) {
            return new BootstrapContext(config, state, List.copyOf(newNodes), addresses, apiKey);
        }

        BootstrapContext withAddresses(List<NodeAddress> newAddresses) {
            return new BootstrapContext(config, state, nodes, List.copyOf(newAddresses), apiKey);
        }

        BootstrapContext withApiKey(String key) {
            return new BootstrapContext(config, state, nodes, addresses, Option.some(key));
        }
    }

    sealed interface BootstrapError extends Cause {
        record ProvisionFailed(String sourceName, String detail) implements BootstrapError {
            @Override public String message() {
                return "Provisioning failed for source '" + sourceName + "': " + detail;
            }
        }

        record AddressCollectionFailed(String sourceName, String detail) implements BootstrapError {
            @Override public String message() {
                return "Address collection failed for source '" + sourceName + "': " + detail;
            }
        }

        record DeploymentFailed(String nodeId, String detail) implements BootstrapError {
            @Override public String message() {
                return "Runtime deployment failed for node '" + nodeId + "': " + detail;
            }
        }

        record QuorumNotEstablished(int healthy, int required) implements BootstrapError {
            @Override public String message() {
                return "Quorum not established: " + healthy + "/" + required + " nodes healthy";
            }
        }
    }
}
