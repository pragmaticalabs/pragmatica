// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;


/// Non-sealed extension hatch for {@link ClusterEvent} (spec §6.4.1).
///
/// Framework extensions (plugins, framework modules) implement this interface to introduce
/// additional cluster event variants without modifying the sealed parent's `permits` list. Apps
/// must NOT publish extended events to system streams — the closed-write principle from spec
/// §6.1 still applies; only `FrameworkStreamPublisher` callers can emit into `system:*`.
///
/// The `discriminator()` returns a free-form unique tag identifying the variant. Recommended
/// convention: fully-qualified record name + ":" + variant name (e.g.
/// `com.example.plugin.CustomEvent:special`).
///
/// On the consumer side, an `ExtendedEvent` arm in a sealed pattern-match switch is mandatory
/// (the compiler enforces exhaustiveness over the sealed parent). Consumers typically dispatch
/// on `discriminator()` to per-extension handlers, log structured for operational visibility, or
/// no-op when extensions are not of interest.
public non-sealed interface ExtendedEvent extends ClusterEvent {
    String discriminator();
}
