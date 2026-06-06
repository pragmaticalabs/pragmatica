// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceAddress;

import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;


/// Framework-only publisher SPI for `system:*` namespace streams (spec §6.1).
///
/// The framework-vs-app boundary on `system:*` writes is enforced as a compile-time invariant
/// rather than a runtime check: the only permitted implementation is the package-private
/// {@link SystemStreamPublisher} record in this package, which application code cannot reference
/// or instantiate. The factory entry point
/// {@link FrameworkStreamPublishers#systemStreamPublisher(ResourceAddress, StreamPublisher)} is the
/// sole construction site and additionally validates the supplied address is in the `system`
/// namespace.
///
/// Companion to {@link StreamPublisher} (which is app-facing). The slice-runtime resolver MUST
/// refuse to bind a `StreamPublisher<T>` for any `system:*` address — apps that somehow obtained
/// such a binding (e.g. via reflection, hand-edited blueprints, or test paths) get a clean failure
/// at provision time rather than silent privilege escalation. That resolver-level enforcement is
/// wired in the same wave that plumbs {@link ResourceAddress} into the publisher factory; this
/// SPI split lands the compile-time half ahead of the resolver wiring.
///
/// Method shape mirrors {@link StreamPublisher}: a single `publish(T event)` returning
/// `Promise<Unit>`, with a default `publishBatch(List<T>)` derived from it.
///
/// Permitted impls:
///   - {@link SystemStreamPublisher} — production: delegates to a transport `StreamPublisher<T>`.
///   - {@link TestSystemStreamPublisher} — test-only: delegates to a capture callback. Constructed
///     via {@link FrameworkStreamPublishers#testPublisher(ResourceAddress, java.util.function.Consumer)}.
public sealed interface FrameworkStreamPublisher<T> permits SystemStreamPublisher, TestSystemStreamPublisher {
    Promise<Unit> publish(T event);

    default Promise<Unit> publishBatch(List<T> events) {
        return Promise.allOf(events.stream().map(this::publish).toList()).mapToUnit();
    }
}
