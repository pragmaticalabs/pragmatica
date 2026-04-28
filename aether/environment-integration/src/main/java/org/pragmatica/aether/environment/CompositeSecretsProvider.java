// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.List;


public record CompositeSecretsProvider(List<SecretsProvider> providers) implements SecretsProvider {
    public static CompositeSecretsProvider compositeSecretsProvider(SecretsProvider... providers) {
        return new CompositeSecretsProvider(List.of(providers));
    }

    @Override public Promise<String> resolveSecret(String secretPath) {
        var result = initialFailure(secretPath);
        for (var provider : providers) {result = chainNextProvider(result, provider, secretPath);}
        return result;
    }

    private static Promise<String> chainNextProvider(Promise<String> current, SecretsProvider next, String secretPath) {
        return current.fold(result -> tryNextOnFailure(result, next, secretPath));
    }

    private static Promise<String> tryNextOnFailure(Result<String> result, SecretsProvider next, String secretPath) {
        return result.fold(_ -> next.resolveSecret(secretPath), Promise::success);
    }

    private static Promise<String> initialFailure(String secretPath) {
        return EnvironmentError.secretResolutionFailed(secretPath,
                                                       new IllegalStateException("No providers configured"))
        .promise();
    }
}
