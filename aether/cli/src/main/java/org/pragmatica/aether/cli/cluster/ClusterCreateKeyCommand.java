// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_KEYS_CREATE;


@Command(name = "create-key", description = "Create a new cluster API key with an explicit authorization role")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01"})
class ClusterCreateKeyCommand implements Callable<Integer> {
    private static final int KEY_BYTES = 32;
    private static final long DEFAULT_GRACE_PERIOD_MS = 5 * 60 * 1000L;
    private static final Set<String> VALID_ROLES = Set.of("ADMIN", "OPERATOR", "VIEWER");

    @Option(names = "--role", description = "Authorization role: ADMIN, OPERATOR, or VIEWER (default: VIEWER)", defaultValue = "VIEWER")
    private String role;

    @Option(names = "--name", description = "Human-readable name for the key (used in audit logs)", defaultValue = "")
    private String name;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> generateAndCreate())
                            .fold(ClusterCreateKeyCommand::onFailure, this::onSuccess);
    }

    private Result<String> generateAndCreate() {
        var normalizedRole = role == null
                             ? "VIEWER"
                             : role.trim().toUpperCase();

        if (!VALID_ROLES.contains(normalizedRole)) {
            return new CreateKeyError.InvalidRole(role).result();
        }

        var newKey = generateApiKey();
        var newKeyHash = KvStoreApiKeyHasher.hashKey(newKey);
        var newKeyId = "ak_" + newKeyHash.substring(0, 8);

        return postCreateKey(newKeyId, newKeyHash, normalizedRole).map(_ -> buildSuccessJson(newKeyId,
                                                                                             newKey,
                                                                                             normalizedRole));
    }

    private Result<String> postCreateKey(String keyId, String keyHash, String authorizationRole) {
        var hint = name == null || name.isBlank()
                   ? "cli-create-key"
                   : "cli-create-key:" + name.trim();
        var json = "{\"keyId\":\"" + keyId
                 + "\",\"keyHash\":\"" + keyHash
                 + "\",\"gracePeriodMs\":" + DEFAULT_GRACE_PERIOD_MS
                 + ",\"auditAction\":\"CREATED\""
                 + ",\"operatorHint\":\"" + hint
                 + "\""
                 + ",\"authorizationRole\":\"" + authorizationRole
                 + "\"}";

        return ClusterHttpClient.post(CLUSTER_KEYS_CREATE, json);
    }

    private static String buildSuccessJson(String keyId, String keyValue, String role) {
        return "{\"keyId\":\"" + keyId
             + "\",\"keyValue\":\"" + keyValue
             + "\",\"authorizationRole\":\"" + role
             + "\",\"status\":\"created\"}";
    }

    private int onSuccess(String json) {
        return OutputFormatter.printAction(json,
                                           parent.outputOptions(),
                                           "Key created with role " + role.toUpperCase()
                                          + ". Save the keyValue — the cluster only stores the hash.");
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

    sealed interface CreateKeyError extends Cause {
        record InvalidRole(String got) implements CreateKeyError {
            @Override
            public String message() {
                return "Invalid --role '" + got + "'. Must be ADMIN, OPERATOR, or VIEWER.";
            }
        }
    }
}
