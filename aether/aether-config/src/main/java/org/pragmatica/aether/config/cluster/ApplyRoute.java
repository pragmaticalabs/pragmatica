// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

/// #686: who creates and retires the nodes of a pool the apply changes, as the CLI claims it on `POST /api/v1/cluster/config`.
///
/// The server never trusts the claim: it recomputes the route of every pool-changing action from the committed auto-heal
/// value and refuses the plan with [ClusterConfigError.RouteMismatch] when any action's route differs from the claim.
public enum ApplyRoute {
    /// The leader's reconcilers fill the pool after the commit: a worker or spot pool, or a core pool with auto-heal enabled
    /// (an absent auto-heal record counts as enabled).
    LEADER,
    /// The CLI creates the nodes before the commit: a core pool with auto-heal disabled.
    APPLY
}
