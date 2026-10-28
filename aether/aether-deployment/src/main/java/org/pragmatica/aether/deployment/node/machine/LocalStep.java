// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

/// One local operation the node FSM asks its interpreter to run (spec R-3.1.2 `LocalOp`). The FSM never touches the
/// slice store, the invocation bridge or the route publisher itself: it names the step, bounds it, and learns the
/// outcome as a `LocalOpSettled` input.
public enum LocalStep {
    /// Load the slice into the store (N1). Idempotent. Bounded at the `LOADING` timeout.
    LOAD,
    /// Activate the slice already in the store (N4). Fails when the slice is absent.
    ACTIVATE,
    /// Register the slice with the invocation bridge.
    REGISTER_BRIDGE,
    /// Register the topic, stream and scheduled-task legs; the outcome carries their KV commands (spec R-3.4.6).
    BUILD_LEGS,
    /// Register config-update sections and deliver the initial notification.
    REGISTER_CONFIG,
    /// Publish the HTTP routes. A failure fails the chain.
    PUBLISH_ROUTES,
    /// Describe the ACTIVE row: the outcome carries the endpoint method names and the stream-ref ACQUIRE commands.
    DESCRIBE_ACTIVE,
    /// Compute the teardown commands (topic, stream, scheduled-task removes and the stream-ref RELEASE) for the
    /// DEACTIVATING or UNLOADING batch. Best effort: a failure drops them and the chain continues.
    PREPARE_TEARDOWN,
    /// Unpublish routes, unregister the invocation bridge and the config-update registration. Best effort.
    UNPUBLISH,
    /// Stop the slice but keep it loaded (N13). Bounded at the `DEACTIVATING` timeout.
    DEACTIVATE,
    /// Unload the slice and close its class loader (N16). Bounded at the `UNLOADING` timeout.
    UNLOAD,
    /// Drop the entry a failed load left behind, so a retry reloads (B-178). Fire and forget.
    EVICT,
    /// Forced cleanup of a slice whose desired key went away (N17): unpublish, release refs, deactivate, unload.
    FORCED_CLEANUP,
    /// Unpublish routes and unregister the bridge without any KV write, on a genuine quorum loss (spec 3.4).
    SUSPEND,
    /// Push a changed config key to the registered slices (NI-5). Carries no artifact.
    NOTIFY_CONFIG
}
