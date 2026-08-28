// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The dispatch loop's door into slice invocation (durable-pubsub-spec §6), shaped so
/// `aether-stream` never depends on the invoke machinery: the node wires an implementation over
/// `SliceInvoker`, which resolves ANY live instance of the subscriber slice per attempt
/// (instance-agnostic — the cursor belongs to the group, not the instance; a retry is eligible
/// for a different instance).
///
/// The returned promise IS the ack (D2): success advances the group cursor, failure or timeout
/// triggers redelivery. The per-attempt timeout's single source of truth is the slice-invoker
/// call timeout (§6) — enforced by the implementation, never re-imposed here. A timed-out attempt
/// may still be executing when its retry dispatches elsewhere (§6's designed-in zombie-attempt
/// duplicate source, bounded only by the §8 idempotency aspect).
@FunctionalInterface
public interface DurableSubscriberInvoker {
    Promise<Unit> deliver(Artifact subscriber, MethodName method, byte[] payloadBytes);
}
