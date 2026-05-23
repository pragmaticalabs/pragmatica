// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.audit;

import org.pragmatica.serialization.Codec;


/// Audit-stream payload published to the `audit.lifecycle.commands` topic for every
/// `LifecycleCommand` flowing through `LifecycleWriter.applyCommand(...)`.
///
/// Two variants:
///   - [CommandReceived] — published immediately when a command enters `applyCommand`.
///   - [CommandApplied]  — published after the resulting KV write completes; `accepted`
///                         reflects whether the underlying `commandApplier` future succeeded.
///
/// ### Payload shape
///
/// To stay codec-friendly without dragging in `Cause` (a non-`@Codec` interface implemented
/// by many `Causes.cause(...)` anonymous instances), the originating `LifecycleCommand` is
/// flattened into a surrogate of primitives/strings:
///   - `commandType`        — simple class name of the `LifecycleCommand` variant
///                            (e.g. `ForceDecommission`, `ForceDrain`, `ForceOnDuty`,
///                            `RecordJoining`, `RequestReJoin`).
///   - `peerId`             — string form of the target `NodeId`.
///   - `reasonTag`          — variant-specific reason payload, or empty:
///                              · `ForceDecommission` → `StopReason.name()`
///                              · `ForceDrain`        → `DrainReason.name()`
///                              · other variants      → empty string.
///   - `justificationMessage` — `Cause.message()` text from the command's `justification()`.
///
/// Downstream consumers (operator UI, ops-LLM, external decision-makers) can correlate
/// receive/apply latency and detect rejected/failed transitions using `commandType`,
/// `peerId`, and the timestamps.
@Codec
public sealed interface CommandLifecycleEvent permits CommandLifecycleEvent.CommandReceived, CommandLifecycleEvent.CommandApplied {
    String commandType();
    String peerId();
    String reasonTag();
    String justificationMessage();

    long timestampMs();

    /// Emitted when a lifecycle command enters `LifecycleWriter.applyCommand(...)`.
    @Codec
    record CommandReceived(String commandType,
                           String peerId,
                           String reasonTag,
                           String justificationMessage,
                           long timestampMs) implements CommandLifecycleEvent {}

    /// Emitted after the underlying KV write completes; `accepted=false` means the
    /// `commandApplier` promise resolved with a failure.
    @Codec
    record CommandApplied(String commandType,
                          String peerId,
                          String reasonTag,
                          String justificationMessage,
                          long timestampMs,
                          boolean accepted) implements CommandLifecycleEvent {}
}
