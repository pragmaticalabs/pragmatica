// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
    /// maps it to `NodeInfo.LABEL_ZONE`, the handshake propagates that label into `SwimMember.labels`, and
    /// worker-community grouping reads it — but both provisioning paths iterate THIS allow-list, so a
    /// provisioned node never received the variable and every node came up zoneless. Fixing the grouping
    /// alone would have left the whole chain inert.
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

    /// Docker-specific infrastructure env vars (network + docker group id).
    List<String> DOCKER_INFRA_VARS = List.of("AETHER_DOCKER_NETWORK", "DOCKER_GID");
    /// Insecure dev-mode flag. Isolated from [#IDENTITY_VARS] on purpose — propagated
    /// only via a standalone block so it can never silently inherit into production.
    String INSECURE_DEV_MODE = "AETHER_INSECURE_DEV_MODE";

    record unused() implements ClusterIdentityEnv {}
}
