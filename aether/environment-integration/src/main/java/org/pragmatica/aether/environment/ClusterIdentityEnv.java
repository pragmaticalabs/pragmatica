// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.List;


/// Single source of truth for the environment variables a compute provider must
/// propagate to every node it mints, so an auto-healed replacement inherits the
/// same cluster identity its compose-fixed siblings receive. Previously each
/// provider hand-enumerated this set, so vars silently went missing one generation
/// deep (the recurring "replacements miss what seeds get" bug class).
///
/// [#IDENTITY_VARS] are cluster-identity vars common to ALL providers (cloud +
/// Docker). [#DOCKER_INFRA_VARS] are Docker-specific infrastructure vars. Both are
/// allow-list driven: a provider iterates the list and emits each var once (when
/// present), rather than naming individual vars inline.
///
/// [#INSECURE_DEV_MODE] is INTENTIONALLY isolated — it is NOT part of
/// [#IDENTITY_VARS]. It is propagated only via a clearly-commented standalone block
/// in each provider so dev-mode never silently rides the identity allow-list into a
/// production deploy.
public sealed interface ClusterIdentityEnv {
    /// Cluster-identity env vars propagated by every provider (cloud + Docker).
    ///
    /// `AETHER_ZONE` is here because omitting it made the zone knob unreachable END-TO-END (#592): `Main`
    /// maps it to `NodeInfo.LABEL_ZONE`, the handshake propagates that label into `SwimMember.labels` — but
    /// both provisioning paths iterate THIS allow-list, so a provisioned node never received the variable
    /// and every node came up zoneless. The consumer #592 cited, worker-community grouping, was deleted in
    /// #673 (2026-09-14): `GroupAssignment` never ran. `AETHER_ZONE` is NOT dead — the label still has two
    /// live readers, `ClusterTopologyManagerRecord` and `ClusterTopologyRoutes` (observability) — so only
    /// the rationale changed, not the entry. Note this is the SWIM-label zone, a different knob from the
    /// `[worker] zone` TOML key, which is parsed and read by nothing.
    /// `AETHER_API_KEYS` (PLURAL) is the node's SERVER-side credential set — the keys it ACCEPTS,
    /// parsed by `ConfigLoader.resolveApiKeys` ahead of any TOML. `AETHER_API_KEY` (SINGULAR, above)
    /// is the CLIENT credential the CLI SENDS. They are different variables read by different code,
    /// and only the singular one was propagated. That was invisible for as long as the published
    /// image baked a server-side key into `docker/aether-node/aether.toml`: a minted node inherited
    /// no key set but did not need one, because its image already carried a matching ADMIN key.
    /// With that baked credential removed the omission becomes load-bearing — a CTM-minted or
    /// auto-healed node would accept NOTHING its compose-fixed siblings accept, which is this
    /// allow-list's stated "replacements miss what seeds get" class arriving one variable over.
    List<String> IDENTITY_VARS = List.of("AETHER_CLUSTER_NAME",
                                         "AETHER_CLUSTER_SECRET",
                                         "AETHER_PROVISIONED_BY",
                                         "AETHER_API_KEY",
                                         "AETHER_API_KEYS",
                                         "AETHER_SOURCE",
                                         "AETHER_ROLE",
                                         "AETHER_ZONE");

    /// The members of [#IDENTITY_VARS] that describe THE NODE rather than the cluster, so a provider sets them
    /// from the node's own provision context and NEVER copies them from the provisioning host's env: that host
    /// is usually the leader, and its role, source and zone are its own (#1650; AETHER_ROLE since W4).
    List<String> NODE_OWN_VARS = List.of("AETHER_ROLE", "AETHER_SOURCE", "AETHER_ZONE");
    /// Docker-specific infrastructure env vars (network + docker group id).
    List<String> DOCKER_INFRA_VARS = List.of("AETHER_DOCKER_NETWORK", "DOCKER_GID");
    /// `[backup]` as environment (#1968): `ConfigLoader` lets each of these override the same key of the node's `[backup]`
    /// TOML section, and a provider that mints a node WITHOUT a TOML of its own (the Docker provider: environment and the
    /// image's baked config only) forwards them from the provisioning host, so a replacement carries the same backup
    /// configuration as its siblings. Without this, a replacement that became leader had no `[backup]` and the backup silently
    /// stopped. Deliberately not in [#IDENTITY_VARS]: they describe a feature the node runs, not the cluster's identity.
    String BACKUP_ENABLED = "AETHER_BACKUP_ENABLED";
    String BACKUP_PATH = "AETHER_BACKUP_PATH";
    String BACKUP_REMOTE = "AETHER_BACKUP_REMOTE";
    String BACKUP_RESTORE = "AETHER_BACKUP_RESTORE";
    List<String> BACKUP_VARS = List.of(BACKUP_ENABLED, BACKUP_PATH, BACKUP_REMOTE, BACKUP_RESTORE);
    /// Insecure dev-mode flag. Isolated from [#IDENTITY_VARS] on purpose — propagated
    /// only via a standalone block so it can never silently inherit into production.
    String INSECURE_DEV_MODE = "AETHER_INSECURE_DEV_MODE";

    record unused() implements ClusterIdentityEnv {}
}
