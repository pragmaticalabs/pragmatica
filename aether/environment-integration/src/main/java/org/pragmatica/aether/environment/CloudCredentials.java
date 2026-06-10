// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public sealed interface CloudCredentials {
    record unused() implements CloudCredentials {}

    Function<String, String> SYSTEM_ENV = System::getenv;

    static Result<CloudConfig> fromEnvironment(String providerName) {
        return fromEnvironment(providerName, SYSTEM_ENV);
    }

    static Result<CloudConfig> fromEnvironment(String providerName, Function<String, String> envLookup) {
        return switch (providerName) {
            case "hetzner" -> hetzner(envLookup);
            case "aws" -> aws(envLookup);
            case "gcp" -> gcp(envLookup);
            case "azure" -> azure(envLookup);
            case "docker" -> Result.success(emptyCloudConfig("docker"));
            default -> Result.failure(EnvironmentError.operationNotSupported("Unknown cloud provider: " + providerName));
        };
    }

    private static Result<CloudConfig> hetzner(Function<String, String> env) {
        return resolve("hetzner", env, List.of(spec("HCLOUD_TOKEN", "api_token", true)));
    }

    private static Result<CloudConfig> aws(Function<String, String> env) {
        return resolve("aws",
                       env,
                       List.of(spec("AWS_ACCESS_KEY_ID", "access_key_id", true),
                               spec("AWS_SECRET_ACCESS_KEY", "secret_access_key", true),
                               spec("AWS_REGION", "region", true)));
    }

    private static Result<CloudConfig> gcp(Function<String, String> env) {
        return resolve("gcp",
                       env,
                       List.of(spec("GCP_PROJECT_ID", "project_id", true),
                               spec("GCP_SERVICE_ACCOUNT_EMAIL", "service_account_email", true),
                               spec("GCP_PRIVATE_KEY_PEM", "private_key_pem", true),
                               spec("GCP_ZONE", "zone", true)));
    }

    private static Result<CloudConfig> azure(Function<String, String> env) {
        return resolve("azure",
                       env,
                       List.of(spec("AZURE_TENANT_ID", "tenant_id", true),
                               spec("AZURE_CLIENT_ID", "client_id", true),
                               spec("AZURE_CLIENT_SECRET", "client_secret", true),
                               spec("AZURE_SUBSCRIPTION_ID", "subscription_id", true),
                               spec("AZURE_RESOURCE_GROUP", "resource_group", true),
                               spec("AZURE_LOCATION", "location", true)));
    }

    private static Result<CloudConfig> resolve(String providerName,
                                               Function<String, String> env,
                                               List<EnvVarSpec> specs) {
        var creds = new LinkedHashMap<String, String>();
        var missing = new ArrayList<String>();

        for (var s : specs) {
            var value = env.apply(s.envVar());

            if (value == null || value.isBlank()) {
                if (s.required()) {
                    missing.add(s.envVar());
                }
            } else {
                creds.put(s.credentialKey(), value);
            }
        }

        if (!missing.isEmpty()) {
            return Result.failure(EnvironmentError.CredentialsMissing.credentialsMissing(providerName, missing).unwrap());
        }

        return Result.success(new CloudConfig(providerName,
                                              Map.copyOf(creds),
                                              Map.of(),
                                              Map.of(),
                                              Map.of(),
                                              Map.of(),
                                              Map.of()));
    }

    private static CloudConfig emptyCloudConfig(String providerName) {
        return new CloudConfig(providerName, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static EnvVarSpec spec(String envVar, String credentialKey, boolean required) {
        return new EnvVarSpec(envVar, credentialKey, required);
    }

    record EnvVarSpec(String envVar, String credentialKey, boolean required) {}
}
