// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


public interface SecretsProvider {
    Promise<String> resolveSecret(String secretPath);

    default Promise<SecretValue> resolveSecretWithMetadata(String secretPath) {
        return resolveSecret(secretPath).map(value -> new SecretValue(value, Option.empty(), Option.empty()));
    }

    default Promise<Map<String, String>> resolveSecrets(List<String> secretPaths) {
        var futures = secretPaths.stream()
                                 .map(path -> resolveSecret(path).map(value -> Map.entry(path, value)))
                                 .toList();

        return Promise.allOf(futures).map(SecretsProvider::collectEntries);
    }

    default Promise<Unit> watchRotation(String secretPath, SecretRotationCallback callback) {
        return Promise.success(Unit.unit());
    }

    private static Map<String, String> collectEntries(List<Result<Map.Entry<String, String>>> results) {
        var map = new HashMap<String, String>();

        for (var result : results) {
            result.onSuccess(entry -> map.put(entry.getKey(), entry.getValue()));
        }

        return Map.copyOf(map);
    }
}
