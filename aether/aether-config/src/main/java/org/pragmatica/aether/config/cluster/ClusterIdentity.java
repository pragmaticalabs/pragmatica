// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;


/// Cluster identity value object — single source of truth for cluster name across:
///   - bootstrap config TOML (`[cluster] name = ...`)
///   - composed runtime TOML (`[cluster] name`, `[cloud.discovery] cluster_name`)
///   - Hetzner / cloud labels (`aether-cluster=<name>`)
///   - KV-Store (`ClusterConfigValue.clusterName` — String for serialization)
///   - operator-facing identifiers (`AETHER_<UPPER>_<NAME>_API_KEY`)
///
/// The name is a [ClusterName] — the RFC-1035 grammar lives THERE, once, and this type no longer
/// keeps a second copy of it. What this type adds is the pairing with a version and the
/// bootstrap-config cause vocabulary: a rejection surfaces as [InvalidName] carrying the offending
/// text, because the TOML parser's error report names the value the operator typed.
public record ClusterIdentity(ClusterName name, String version) {
    public static Result<ClusterIdentity> clusterIdentity(String name, String version) {
        return parseName(name).map(parsed -> new ClusterIdentity(parsed, version));
    }

    public Result<ClusterIdentity> withName(String newName) {
        return parseName(newName).map(parsed -> new ClusterIdentity(parsed, version));
    }

    /// Grammar delegated to [ClusterName#clusterName]; the rejection is re-clothed as [InvalidName]
    /// so the cause type the bootstrap-config surface already reports is unchanged, and so the
    /// message still echoes the candidate — [ClusterName]'s own cause describes the RULE (it is
    /// shared by call sites that have no single offending value to name) and never the input.
    private static Result<ClusterName> parseName(String candidate) {
        return ClusterName.clusterName(candidate).mapError(cause -> invalidName(candidate, cause));
    }

    private static Cause invalidName(String candidate, Cause cause) {
        return new InvalidName("Cluster name '" + candidate + "' is invalid. " + cause.message());
    }

    public record InvalidName(String message) implements Cause {}
}
