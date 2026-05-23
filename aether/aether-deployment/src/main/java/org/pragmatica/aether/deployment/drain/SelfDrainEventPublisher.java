// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue;

import java.util.Map;


/// Narrow sink the `SelfDrainCoordinator` uses to surface the `SELF_DRAIN_INITIATED`
/// cluster event when it flips from `ACTIVE` to `DRAINING`. Kept here (in the deployment
/// module's `drain` package) rather than referencing `ClusterEventLogPublisher` directly
/// because `aether-deployment` does NOT depend on `aether-node` — only the reverse. The
/// production wiring in `AetherNode` adapts `ClusterEventLogPublisher::publish` to this
/// shape; tests pass `NO_OP` to avoid pulling in a publisher harness.
///
/// **Fire-and-forget contract.** The coordinator is about to halt the JVM; it does NOT
/// await the publish result. The implementation should never throw — at this point the
/// node is committed to the drain regardless. A failed publish is logged by the
/// implementation but does not interrupt the drain sequence.
@FunctionalInterface
public interface SelfDrainEventPublisher {
    void publish(ClusterEventValue.EventType type,
                 ClusterEventValue.Severity severity,
                 String message,
                 Map<String, String> details);

    /// No-op publisher for tests and contexts where the cluster-event log is not wired.
    SelfDrainEventPublisher NO_OP = (type, severity, message, details) -> {};
}
