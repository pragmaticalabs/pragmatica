// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_KEYS_CREATE;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_KEYS_LIST;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_KEYS_REVOKE;
import static org.pragmatica.lang.Option.option;


@Command(name = "rotate-key", description = "Rotate the cluster API key")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterRotateKeyCommand implements Callable<Integer> {
    private static final int KEY_BYTES = 32;
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String DEFAULT_ROLE = "VIEWER";
    private static final JsonMapper KEYS_MAPPER = JsonMapper.defaultJsonMapper();

    private static final Cause NOT_A_KEY_LISTING = new RotateKeyError.KeyListUnreadable("the key listing was not a JSON array of key records");

    @Option(names = "--grace-period", description = "Grace period for old key (e.g., 5m, 1h)", defaultValue = "5m")
    private String gracePeriod;

    @Option(names = "--role", description = "Authorization role for the new key: ADMIN, OPERATOR, or VIEWER (default: VIEWER)", defaultValue = "VIEWER")
    private String role;

    @Option(names = "--key-id", description = "Key ID to retire; required when the cluster has more than one ACTIVE key")
    private String targetKeyId;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> findKeyToRetire())
                            .flatMap(this::rotateFrom)
                            .fold(ClusterRotateKeyCommand::onFailure, this::onSuccess);
    }

    private Result<RotationOutcome> rotateFrom(String oldKeyId) {
        var newKey = generateApiKey();
        var newKeyHash = KvStoreApiKeyHasher.hashKey(newKey);
        var newKeyId = "ak_" + newKeyHash.substring(0, 8);
        var gracePeriodMs = parseDurationMs(gracePeriod);

        return createNewKey(newKeyId,
                            newKeyHash,
                            gracePeriodMs,
                            normalizedRole()).flatMap(_ -> revokeOldKey(oldKeyId, gracePeriodMs))
                           .flatMap(_ -> persistLocalKey(newKey))
                           .map(_ -> new RotationOutcome(buildSuccessJson(newKeyId, oldKeyId, gracePeriodMs),
                                                         newKeyId,
                                                         oldKeyId));
    }

    private String normalizedRole() {
        return option(role).filter(Verify.Is::present)
                     .map(String::trim)
                     .map(String::toUpperCase)
                     .or(DEFAULT_ROLE);
    }

    private Result<String> findKeyToRetire() {
        return ClusterHttpClient.fetch(CLUSTER_KEYS_LIST).flatMap(json -> resolveKeyToRetire(targetKeyId, json));
    }

    /// Selects the key to retire by reading each record's OWN `status` field (#528).
    ///
    /// The previous reading asked whether the document contained the token `"ACTIVE"` anywhere and
    /// then took whichever `keyId` appeared first in the payload — two checks with no association
    /// between them. A listing whose first record was revoked or expired therefore rotated *that*
    /// key: it revoked a credential possibly still in use and left the one the operator meant to
    /// retire valid, reporting success either way.
    ///
    /// An unreadable or non-array body never resolves to a key; it fails, so the rotation cannot
    /// proceed against a guess. More than one ACTIVE key is refused unless `--key-id` names the
    /// one to retire.
    static Result<String> resolveKeyToRetire(String requestedKeyId, String keysJson) {
        return parseActiveKeyIds(keysJson).flatMap(activeKeyIds -> selectKeyToRetire(requestedKeyId, activeKeyIds));
    }

    static Result<List<String>> parseActiveKeyIds(String keysJson) {
        return KEYS_MAPPER.readTree(keysJson)
                          .mapError(ClusterRotateKeyCommand::unreadableListing)
                          .flatMap(ClusterRotateKeyCommand::readKeyEntries)
                          .map(ClusterRotateKeyCommand::activeKeyIds);
    }

    static Result<String> selectKeyToRetire(String requestedKeyId, List<String> activeKeyIds) {
        return option(requestedKeyId).filter(Verify.Is::present)
                     .map(requested -> selectRequestedKey(requested, activeKeyIds))
                     .or(() -> selectSoleActiveKey(activeKeyIds));
    }

    private static Result<String> selectSoleActiveKey(List<String> activeKeyIds) {
        return switch (activeKeyIds.size()) {
            case 0 -> RotateKeyError.NoActiveKey.INSTANCE.result();
            case 1 -> Result.success(activeKeyIds.getFirst());
            default -> new RotateKeyError.AmbiguousActiveKeys(activeKeyIds).result();
        };
    }

    private static Result<String> selectRequestedKey(String requestedKeyId, List<String> activeKeyIds) {
        return activeKeyIds.contains(requestedKeyId)
               ? Result.success(requestedKeyId)
               : new RotateKeyError.RequestedKeyNotActive(requestedKeyId, activeKeyIds).result();
    }

    private static Cause unreadableListing(Cause cause) {
        return new RotateKeyError.KeyListUnreadable(cause.message());
    }

    private static Result<List<KeyEntry>> readKeyEntries(JsonNode root) {
        return root.isArray()
               ? readKeyRecords(root)
               : NOT_A_KEY_LISTING.result();
    }

    private static Result<List<KeyEntry>> readKeyRecords(JsonNode root) {
        return Result.allOf(IntStream.range(0,
                                            root.size())
                                     .mapToObj(root::get)
                                     .map(ClusterRotateKeyCommand::readKeyEntry)
                                     .toList());
    }

    private static Result<KeyEntry> readKeyEntry(JsonNode keyRecord) {
        var keyId = keyRecord.path("keyId").asText("");
        var status = keyRecord.path("status").asText("");

        return Verify.Is.present(keyId) && Verify.Is.present(status)
               ? Result.success(new KeyEntry(keyId, status))
               : new RotateKeyError.KeyListUnreadable("a key record carries no keyId/status pair").result();
    }

    private static List<String> activeKeyIds(List<KeyEntry> entries) {
        return entries.stream()
                      .filter(KeyEntry::isActive)
                      .map(KeyEntry::keyId)
                      .toList();
    }

    private static Result<String> createNewKey(String keyId,
                                               String keyHash,
                                               long gracePeriodMs,
                                               String authorizationRole) {
        var json = "{\"keyId\":\"" + keyId
                 + "\",\"keyHash\":\"" + keyHash
                 + "\",\"gracePeriodMs\":" + gracePeriodMs
                 + ",\"auditAction\":\"ROTATED\""
                 + ",\"operatorHint\":\"cli-rotate-key\""
                 + ",\"authorizationRole\":\"" + authorizationRole
                 + "\"}";

        return ClusterHttpClient.post(CLUSTER_KEYS_CREATE, json);
    }

    private static Result<String> revokeOldKey(String oldKeyId, long gracePeriodMs) {
        var json = "{\"immediate\":false,\"gracePeriodMs\":" + gracePeriodMs + ",\"operatorHint\":\"cli-rotate-key\"}";

        return ClusterHttpClient.post(CLUSTER_KEYS_REVOKE, List.of(oldKeyId), json);
    }

    private static Result<String> persistLocalKey(String newKey) {
        return ClusterRegistry.load()
                              .flatMap(ClusterRotateKeyCommand::currentClusterName)
                              .flatMap(clusterName -> writeKeyFile(clusterName, newKey));
    }

    /// The registry keeps section-key cluster names as `String`; parse at this boundary so the
    /// `~/.aether/clusters/<cluster>/api-key` path is built from a name the rest of the CLI would
    /// also accept, rather than from whatever text the registry file happens to hold.
    private static Result<ClusterName> currentClusterName(ClusterRegistry registry) {
        return registry.current()
                       .toResult(ClusterHttpClient.HttpError.NO_ACTIVE_CLUSTER)
                       .flatMap(entry -> ClusterName.clusterName(entry.name()));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<String> writeKeyFile(ClusterName clusterName, String newKey) {
        var keyFile = Path.of(System.getProperty("user.home"), ".aether", "clusters", clusterName.value(), "api-key");

        try {
            Files.createDirectories(keyFile.getParent());
            // #287: rotated admin api-key file must be owner-only (0600).
            return SecureFiles.writeSecure(keyFile, newKey)
                              .map(_ -> "ok")
                              .mapError(cause -> new RotateKeyError.FilePersistFailed(keyFile.toString(),
                                                                                      cause.message()));
        } catch (IOException e) {
            return new RotateKeyError.FilePersistFailed(keyFile.toString(), e.getMessage()).result();
        }
    }

    private static String buildSuccessJson(String newKeyId, String oldKeyId, long gracePeriodMs) {
        return "{\"newKeyId\":\"" + newKeyId
             + "\",\"oldKeyId\":\"" + oldKeyId
             + "\",\"gracePeriodMs\":" + gracePeriodMs
             + ",\"status\":\"rotated\"}";
    }

    private int onSuccess(RotationOutcome outcome) {
        return OutputFormatter.printAction(outcome.json(),
                                           parent.outputOptions(),
                                           "Key rotated: " + outcome.oldKeyId()
                                          + " retired, " + outcome.newKeyId()
                                          + " is now active. Old key valid for " + gracePeriod
                                          + " grace period.");
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }

    private static String generateApiKey() {
        var bytes = new byte[KEY_BYTES];

        new SecureRandom().nextBytes(bytes);

        return Base64.getUrlEncoder()
                     .withoutPadding()
                     .encodeToString(bytes);
    }

    // RET-06: `duration` is the picocli `--grace-period` option value; the null/empty coalesce to a
    // default is parse-boundary handling of framework-supplied input.
    @SuppressWarnings("JBCT-RET-06")
    private static long parseDurationMs(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 300_000;
        }

        var value = duration.substring(0, duration.length() - 1);
        var unit = duration.charAt(duration.length() - 1);

        return switch (unit) {
            case 's' -> Long.parseLong(value) * 1000;
            case 'm' -> Long.parseLong(value) * 60_000;
            case 'h' -> Long.parseLong(value) * 3_600_000;
            default -> Long.parseLong(duration);
        };
    }

    private record RotationOutcome(String json, String newKeyId, String oldKeyId) {}

    private record KeyEntry(String keyId, String status) {
        boolean isActive() {
            return ACTIVE_STATUS.equals(status);
        }
    }

    sealed interface RotateKeyError extends Cause {
        enum NoActiveKey implements RotateKeyError {
            INSTANCE;
            @Override
            public String message() {
                return "No active API key found in cluster. Bootstrap or create a key first.";
            }
        }

        record KeyListUnreadable(String reason) implements RotateKeyError {
            @Override
            public String message() {
                return "Cannot determine which API key to retire: " + reason;
            }
        }

        record AmbiguousActiveKeys(List<String> keyIds) implements RotateKeyError {
            @Override
            public String message() {
                return "Cluster has " + keyIds.size()
                     + " ACTIVE API keys (" + String.join(", ", keyIds)
                     + "). Re-run with --key-id <keyId> naming the one to retire.";
            }
        }

        record RequestedKeyNotActive(String keyId, List<String> activeKeyIds) implements RotateKeyError {
            @Override
            public String message() {
                return "API key " + keyId
                     + " is not among the cluster's ACTIVE keys (" + String.join(", ", activeKeyIds)
                     + "). Run 'aether cluster list-keys' to see current key states.";
            }
        }

        record FilePersistFailed(String path, String reason) implements RotateKeyError {
            @Override
            public String message() {
                return "Failed to write key file " + path + ": " + reason;
            }
        }
    }
}
