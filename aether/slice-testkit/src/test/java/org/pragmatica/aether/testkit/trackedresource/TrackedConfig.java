// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.trackedresource;


/// Configuration for [TrackedResource], bound from the `[tracked.resource]` section of the slice's
/// own `META-INF/resources.toml`.
///
/// A record rather than a bare `String` because that is the only shape the composite-backed loader
/// can bind: `SpiResourceProvider.resolveConfigLoader` PREFERS a loader derived from the
/// `ConfigurationProvider` on the provisioning context over the one the provider was constructed
/// with, so a deployed slice always resolves resource config through its composite.
public record TrackedConfig(String name) {}
