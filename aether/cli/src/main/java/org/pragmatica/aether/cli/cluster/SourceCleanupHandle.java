// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Option;

import java.util.Map;


/// Persisted cleanup handle for a source profile.
///
/// Captures the cloud-provider identity, region, and the env-var NAMES the operator used
/// for credentials at bootstrap time, so a later cleanup process — possibly running from
/// a fresh shell where the default env vars (HCLOUD_TOKEN, AWS_ACCESS_KEY_ID, …) are not
/// set — can re-derive the correct credentials by reading the same env vars the operator
/// configured.
///
/// SAFETY CONTRACT: `credentialEnvVars` MUST contain env-var NAMES, never values. The
/// cleanup process reads names from this handle and then calls `System.getenv(name)`
/// itself; secrets never land on disk via the persisted bootstrap state file.
///
/// Example: for a Hetzner source where the operator wrote
///   credentials = "${env:HCLOUD_TOKEN_PROD}"
/// in the TOML, the resulting handle is:
///   provider = "hetzner"
///   region = some("eu-central")
///   credentialEnvVars = { "api_token" -> "HCLOUD_TOKEN_PROD" }
public record SourceCleanupHandle(String provider, Option<String> region, Map<String, String> credentialEnvVars) {
    public SourceCleanupHandle {
        credentialEnvVars = Map.copyOf(credentialEnvVars);
    }

    public static SourceCleanupHandle sourceCleanupHandle(String provider,
                                                          Option<String> region,
                                                          Map<String, String> credentialEnvVars) {
        return new SourceCleanupHandle(provider, region, credentialEnvVars);
    }
}
