package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_KEYS_CREATE;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_KEYS_LIST;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_KEYS_REVOKE;


/// Rotates the cluster API key: generates a new key, stores it via the management API,
/// revokes the old key with a configurable grace period, and updates the local key file.
@Command(name = "rotate-key", description = "Rotate the cluster API key") @SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"}) class ClusterRotateKeyCommand implements Callable<Integer> {
    private static final int KEY_BYTES = 32;

    @Option(names = "--grace-period", description = "Grace period for old key (e.g., 5m, 1h)", defaultValue = "5m") private String gracePeriod;

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        return generateAndRotate().fold(ClusterRotateKeyCommand::onFailure, this::onSuccess);
    }

    private Result<String> generateAndRotate() {
        var newKey = generateApiKey();
        var newKeyHash = KvStoreApiKeyHasher.hashKey(newKey);
        var newKeyId = "ak_" + newKeyHash.substring(0, 8);
        var gracePeriodMs = parseDurationMs(gracePeriod);
        return findCurrentActiveKeyId().flatMap(oldKeyId -> createNewKey(newKeyId, newKeyHash, gracePeriodMs).flatMap(_ -> revokeOldKey(oldKeyId,
                                                                                                                                        gracePeriodMs))
                                                                        .flatMap(_ -> persistLocalKey(newKey))
                                                                        .map(_ -> buildSuccessJson(newKeyId,
                                                                                                   oldKeyId,
                                                                                                   gracePeriodMs)));
    }

    private static Result<String> findCurrentActiveKeyId() {
        return ClusterHttpClient.fetch(CLUSTER_KEYS_LIST).flatMap(ClusterRotateKeyCommand::extractFirstActiveKeyId);
    }

    private static Result<String> extractFirstActiveKeyId(String json) {
        if (json.contains("\"ACTIVE\"")) {
            var idx = json.indexOf("\"keyId\"");
            if (idx >= 0) {
                var start = json.indexOf("\"", idx + 7) + 1;
                var end = json.indexOf("\"", start);
                if (start > 0 && end > start) {return Result.success(json.substring(start, end));}
            }
        }
        return new RotateKeyError.NoActiveKey().result();
    }

    private static Result<String> createNewKey(String keyId, String keyHash, long gracePeriodMs) {
        var json = "{\"keyId\":\"" + keyId + "\",\"keyHash\":\"" + keyHash + "\",\"gracePeriodMs\":" + gracePeriodMs + ",\"auditAction\":\"ROTATED\",\"operatorHint\":\"cli-rotate-key\"}";
        return ClusterHttpClient.post(CLUSTER_KEYS_CREATE, json);
    }

    private static Result<String> revokeOldKey(String oldKeyId, long gracePeriodMs) {
        var json = "{\"immediate\":false,\"gracePeriodMs\":" + gracePeriodMs + ",\"operatorHint\":\"cli-rotate-key\"}";
        return ClusterHttpClient.post(CLUSTER_KEYS_REVOKE, List.of(oldKeyId), json);
    }

    private static Result<String> persistLocalKey(String newKey) {
        return ClusterRegistry.load()
                                   .flatMap(reg -> reg.current().toResult(ClusterHttpClient.HttpError.NO_ACTIVE_CLUSTER)
                                                              .flatMap(entry -> writeKeyFile(entry.name(),
                                                                                             newKey)));
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<String> writeKeyFile(String clusterName, String newKey) {
        var keyFile = Path.of(System.getProperty("user.home"), ".aether", "clusters", clusterName, "api-key");
        try {
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, newKey);
            return Result.success("ok");
        } catch (IOException e) {
            return new RotateKeyError.FilePersistFailed(keyFile.toString(), e.getMessage()).result();
        }
    }

    private static String buildSuccessJson(String newKeyId, String oldKeyId, long gracePeriodMs) {
        return "{\"newKeyId\":\"" + newKeyId + "\",\"oldKeyId\":\"" + oldKeyId + "\",\"gracePeriodMs\":" + gracePeriodMs + ",\"status\":\"rotated\"}";
    }

    private int onSuccess(String json) {
        return OutputFormatter.printAction(json,
                                           parent.outputOptions(),
                                           "Key rotated. Old key valid for " + gracePeriod + " grace period.");
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }

    private static String generateApiKey() {
        var bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                                   .encodeToString(bytes);
    }

    private static long parseDurationMs(String duration) {
        if (duration == null || duration.isEmpty()) {return 300_000;}
        var value = duration.substring(0, duration.length() - 1);
        var unit = duration.charAt(duration.length() - 1);
        return switch (unit){
            case 's' -> Long.parseLong(value) * 1000;
            case 'm' -> Long.parseLong(value) * 60_000;
            case 'h' -> Long.parseLong(value) * 3_600_000;
            default -> Long.parseLong(duration);
        };
    }

    sealed interface RotateKeyError extends Cause {
        record NoActiveKey() implements RotateKeyError {
            @Override public String message() {
                return "No active API key found in cluster. Bootstrap or create a key first.";
            }
        }

        record FilePersistFailed(String path, String reason) implements RotateKeyError {
            @Override public String message() {
                return "Failed to write key file " + path + ": " + reason;
            }
        }
    }
}
