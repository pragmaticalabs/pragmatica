// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.regex.Pattern;

/// Cluster identity value object — single source of truth for cluster name across:
///   - bootstrap config TOML (`[cluster] name = ...`)
///   - composed runtime TOML (`[cluster] name`, `[cloud.discovery] cluster_name`)
///   - Hetzner / cloud labels (`aether-cluster=<name>`)
///   - KV-Store (`ClusterConfigValue.clusterName` — String for serialization)
///   - operator-facing identifiers (`AETHER_<UPPER>_<NAME>_API_KEY`)
///
/// Names are validated against `^[a-z][a-z0-9-]{0,62}$` so they're safe everywhere — Hetzner
/// label spec, DNS labels, env var derivation. Construction goes through a `Result` factory
/// so all downstream readers can trust the invariant.
public record ClusterIdentity(String name, String version) {
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");

    public static Result<ClusterIdentity> clusterIdentity(String name, String version) {
        return validateName(name).map(_ -> new ClusterIdentity(name, version));
    }

    public Result<ClusterIdentity> withName(String newName) {
        return validateName(newName).map(_ -> new ClusterIdentity(newName, version));
    }

    private static Result<Unit> validateName(String candidate) {
        if (candidate == null || candidate.isBlank()) {return new InvalidName("Cluster name must not be blank").result();}
        if (!NAME_PATTERN.matcher(candidate).matches()) {
            return new InvalidName("Cluster name '" + candidate + "' does not match required pattern " + NAME_PATTERN.pattern())
                    .result();
        }
        return Result.success(Unit.unit());
    }

    public record InvalidName(String message) implements Cause {}
}
