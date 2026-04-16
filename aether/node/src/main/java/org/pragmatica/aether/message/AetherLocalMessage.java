// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.message;

import org.pragmatica.messaging.Message;


/// Marker interface for Aether-specific local messages.
///
///
/// Local messages are dispatched within a single node via MessageRouter,
/// not sent over the network. This serves as documentation for the message
/// types used in Aether:
///
///
///   - {@link org.pragmatica.aether.metrics.deployment.DeploymentEvent} - Slice deployment lifecycle events
///   - {@link org.pragmatica.aether.invoke.SliceFailureEvent} - Invocation failure events
///   - {@link org.pragmatica.aether.api.OperationalEvent} - Operational audit events (security, lifecycle, config, backup, blueprint)
///
///
///
/// Compile-time exhaustiveness is validated via {@link org.pragmatica.messaging.MessageRouter.Entry.SealedBuilder}
/// when building routes in {@link org.pragmatica.aether.node.AetherNode}.
///
///
/// Note: Network messages (InvocationMessage, MetricsMessage, etc.) extend
/// ProtocolMessage and are handled separately through the cluster network layer.
///
/// @see RouteGroup
/// @see org.pragmatica.messaging.MessageRouter.Entry.SealedBuilder
public interface AetherLocalMessage extends Message.Local {}
