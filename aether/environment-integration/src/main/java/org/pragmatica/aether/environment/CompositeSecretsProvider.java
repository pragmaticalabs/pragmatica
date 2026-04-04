package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.List;


/// SecretsProvider that chains multiple providers, returning the first successful resolution.
/// If all providers fail, the last failure is returned.
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
