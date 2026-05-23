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
///   - `source`             — emitter discriminator (`OPERATOR`, `RECONCILER`,
///                            `DRAIN_COORDINATOR`, `CTM`, `BOOTSTRAP`, `UNKNOWN`).
///                            Phase 3 PR-C convergence-reconciler addition — Phase 4-5
///                            reconciler-emitted commands carry `RECONCILER`; the new
///                            operator-facing `POST /api/nodes/lifecycle/commands` route
///                            stamps `OPERATOR`. Legacy callers that have not yet been
///                            converted carry `UNKNOWN` — explicit threading at the
///                            remaining call sites is RC2 polish.
///
/// Downstream consumers (operator UI, ops-LLM, external decision-makers) can correlate
/// receive/apply latency and detect rejected/failed transitions using `commandType`,
/// `peerId`, and the timestamps.
@Codec
public sealed interface CommandLifecycleEvent permits CommandLifecycleEvent.CommandReceived, CommandLifecycleEvent.CommandApplied {
    /// Source tag for legacy / unconverted emitter sites — explicit per-site threading is
    /// RC2 polish per Phase 3 PR-C.
    String SOURCE_UNKNOWN = "UNKNOWN";

    /// Source tag stamped by the operator-facing `POST /api/nodes/lifecycle/commands`
    /// route (and the symmetric `aether nodes decommission` CLI subcommand).
    String SOURCE_OPERATOR = "OPERATOR";

    /// Source tag reserved for Phase 4-5 reconciler-emitted commands.
    String SOURCE_RECONCILER = "RECONCILER";

    /// Source tag stamped by the drain coordinator on drain-complete / drain-failed paths.
    String SOURCE_DRAIN_COORDINATOR = "DRAIN_COORDINATOR";

    /// Source tag stamped by the cluster topology manager on scale-down decommission.
    String SOURCE_CTM = "CTM";

    /// Source tag stamped by bootstrap-driven lifecycle writes.
    String SOURCE_BOOTSTRAP = "BOOTSTRAP";

    String commandType();
    String peerId();
    String reasonTag();
    String justificationMessage();
    String source();

    long timestampMs();

    /// Emitted when a lifecycle command enters `LifecycleWriter.applyCommand(...)`.
    @Codec
    record CommandReceived(String commandType,
                           String peerId,
                           String reasonTag,
                           String justificationMessage,
                           String source,
                           long timestampMs) implements CommandLifecycleEvent {}

    /// Emitted after the underlying KV write completes; `accepted=false` means the
    /// `commandApplier` promise resolved with a failure.
    @Codec
    record CommandApplied(String commandType,
                          String peerId,
                          String reasonTag,
                          String justificationMessage,
                          String source,
                          long timestampMs,
                          boolean accepted) implements CommandLifecycleEvent {}
}
