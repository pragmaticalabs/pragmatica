// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.stream.StreamEngineKey;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Result.unitResult;


/// Shared classification of a {@link StreamPartitionManager#createStream} outcome
/// (stream-offheap-budget-spec §5.3 / reconciliation #18).
///
/// `createStream` reports `STREAM_ALREADY_EXISTS` as a failure-typed {@link Result} (it is the
/// idempotent-duplicate signal, not a genuine error), so every idempotent caller — `StreamRoutes`,
/// {@link StreamPublisherFactory}, {@link StreamAccessFactory}, {@link SystemStreamFactories}, and
/// {@link org.pragmatica.aether.stream.topic.DurableTopicSubstrate} (topic + DLQ activation) —
/// must tolerate exactly that cause and PROPAGATE everything else (notably
/// `STREAM_MEMORY_EXCEEDED`). Centralizing the predicate here removes the three divergent copies that
/// previously each swallowed all create failures.
public sealed interface StreamCreateOutcome {
    /// Map an "ensure the stream exists" create outcome to a propagating `Result<Unit>`:
    /// `STREAM_ALREADY_EXISTS` becomes success (the stream is usable); any other failure — including
    /// `STREAM_MEMORY_EXCEEDED` — is returned unchanged so the caller's provisioning / publish fails
    /// loud (spec §4.5b / §6).
    static Result<Unit> tolerateAlreadyExists(Result<Unit> outcome) {
        return outcome.fold(StreamCreateOutcome::recoverAlreadyExists, Result::success);
    }

    /// What a slice-facing factory does to make `config`'s stream exist (#1338). A `system:` stream is provisioned by
    /// the runtime alone, so a slice that binds one (a blueprint `External` system source) never creates it and
    /// never has its own declaration compared with the committed one: it must already exist, else provisioning
    /// fails with [StreamError.StreamNotFound]. Every other stream is created as declared.
    static Result<Unit> ensureExists(StreamPartitionManager manager, StreamConfig config) {
        return StreamEngineKey.isSystemStream(config.name())
               ? manager.streamInfo(config.name())
                        .<Result<Unit>> map(_ -> unitResult())
                        .or(() -> new StreamError.StreamNotFound(config.name()).result())
               : tolerateAlreadyExists(manager.createDeclaredStream(config));
    }

    private static Result<Unit> recoverAlreadyExists(Cause cause) {
        return cause == StreamError.General.STREAM_ALREADY_EXISTS
               ? unitResult()
               : cause.result();
    }

    record unused() implements StreamCreateOutcome {}
}
