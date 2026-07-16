// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


sealed interface ConfigReferenceResolver {
    record unused() implements ConfigReferenceResolver {}

    Pattern REFERENCE_PATTERN = Pattern.compile("\\$\\{(env|secrets):([^}]+)}");

    static Result<String> resolveAll(String tomlContent) {
        return resolveAll(tomlContent, Option.none());
    }

    static Result<String> resolveAll(String tomlContent, Option<SecretsProvider> secretsProvider) {
        var matcher = REFERENCE_PATTERN.matcher(tomlContent);
        var unresolved = new LinkedHashSet<String>();
        var resolved = new ArrayList<ResolvedMatch>();

        collectMatches(matcher, unresolved, resolved, secretsProvider);
        if (!unresolved.isEmpty()) {
            return new ClusterConfigError.SecretResolutionFailed(formatUnresolved(unresolved)).result();
        }

        return success(applyReplacements(tomlContent, resolved));
    }

    private static void collectMatches(Matcher matcher,
                                       LinkedHashSet<String> unresolved,
                                       ArrayList<ResolvedMatch> resolved,
                                       Option<SecretsProvider> secretsProvider) {
        while (matcher.find()) {
            var type = matcher.group(1);
            var name = matcher.group(2);
            var fullRef = matcher.group(0);

            resolveReference(type, name, fullRef, unresolved, resolved, secretsProvider);
        }
    }

    private static void resolveReference(String type,
                                         String name,
                                         String fullRef,
                                         LinkedHashSet<String> unresolved,
                                         ArrayList<ResolvedMatch> resolved,
                                         Option<SecretsProvider> secretsProvider) {
        var envVarName = resolveEnvVarName(type, name);
        var envValue = option(System.getenv(envVarName));

        if (envValue.isPresent()) {
            envValue.onPresent(value -> resolved.add(new ResolvedMatch(fullRef, value)));

            return;
        }

        if ("secrets".equals(type)) {
            resolveViaProvider(secretsProvider, name, fullRef, envVarName, unresolved, resolved);

            return;
        }

        unresolved.add(fullRef + " -> env var " + envVarName + " not set");
    }

    private static void resolveViaProvider(Option<SecretsProvider> secretsProvider,
                                           String name,
                                           String fullRef,
                                           String envVarName,
                                           LinkedHashSet<String> unresolved,
                                           ArrayList<ResolvedMatch> resolved) {
        secretsProvider.onPresent(provider -> attemptProviderResolve(provider,
                                                                     name,
                                                                     fullRef,
                                                                     envVarName,
                                                                     unresolved,
                                                                     resolved))
                       .onEmpty(() -> unresolved.add(fullRef + " -> env var " + envVarName + " not set"));
    }

    @Contract
    private static void attemptProviderResolve(SecretsProvider provider,
                                               String name,
                                               String fullRef,
                                               String envVarName,
                                               LinkedHashSet<String> unresolved,
                                               ArrayList<ResolvedMatch> resolved) {
        provider.resolveSecret(name)
                .await()
                .onSuccess(value -> resolved.add(new ResolvedMatch(fullRef, value)))
                .onFailure(_ -> unresolved.add(fullRef + " -> env var " + envVarName + " not set"));
    }

    private static String resolveEnvVarName(String type, String name) {
        return "secrets".equals(type)
               ? "AETHER_" + name.toUpperCase()
                                 .replace('-', '_')
               : name;
    }

    private static String formatUnresolved(LinkedHashSet<String> unresolved) {
        return String.join("; ", unresolved);
    }

    private static String applyReplacements(String content, ArrayList<ResolvedMatch> resolved) {
        var result = content;

        for (var match : resolved) {
            result = result.replace(match.reference(), match.value());
        }

        return result;
    }

    record ResolvedMatch(String reference, String value) {}
}
