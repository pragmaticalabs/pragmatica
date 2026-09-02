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
    List<String> IDENTITY_VARS = List.of("AETHER_CLUSTER_NAME",
                                         "AETHER_CLUSTER_SECRET",
                                         "AETHER_PROVISIONED_BY",
                                         "AETHER_API_KEY",
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
