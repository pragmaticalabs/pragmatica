// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamAccess.StreamMetadata;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;


/// Framework-only consumer SPI for `system:*` namespace streams (spec §6.1).
///
/// Consumer-side mirror of {@link FrameworkStreamPublisher}. The framework-vs-app boundary on
/// `system:*` reads is enforced as a compile-time invariant rather than a runtime check: the only
/// permitted implementation is the package-private {@link SystemStreamConsumer} record in this
/// package, which application code cannot reference or instantiate. The factory entry point
/// {@link FrameworkStreamConsumers#systemStreamConsumer(StreamAddress, StreamAccess)} is the sole
/// construction site and additionally validates the supplied address is in the `system` namespace.
///
/// Companion to {@link StreamAccess} (which is app-facing). The slice-runtime resolver MUST refuse
/// to bind a `StreamAccess<T>` for any `system:*` address — apps that somehow obtained such a
/// binding (e.g. via reflection, hand-edited blueprints, or test paths) get a clean failure at
/// provision time rather than silent privilege escalation. {@link StreamAccess#ensureAppAddress}
/// is the canonical resolver-side check; this SPI split lands the compile-time half.
///
/// Method shape mirrors the read surface of {@link StreamAccess}: offset and partition+offset
/// fetches, consumer-group offset commit/lookup, and metadata. Writes are intentionally absent —
/// the framework writes via {@link FrameworkStreamPublisher} and reads via this SPI.
public sealed interface FrameworkStreamConsumer<T> permits SystemStreamConsumer {
    Promise<List<StreamEvent<T>>> fetch(long fromOffset, int maxEvents);

    Promise<List<StreamEvent<T>>> fetch(int partition, long fromOffset, int maxEvents);

    Promise<Unit> commit(String consumerGroup, int partition, long offset);

    Promise<Option<Long>> committedOffset(String consumerGroup, int partition);

    Promise<StreamMetadata> metadata();
}
