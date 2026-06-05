// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.StreamConfig;


/// Declared stream resource from a blueprint's `resources.toml`.
///
/// Two forms:
///  - [Owned] — internal stream declaration. Namespace is derived from the declaring blueprint's
///    coordinates; the resource carries local alias, version spec, and the full [StreamConfig]
///    (partitions, retention, etc.). Both producer and consumer roles use this form; the role is
///    determined at annotation-binding time.
///  - [External] — reference to a stream owned by a different namespace (consumer-only in RC1).
///    Carries a fully-qualified [StreamAddress]; no config is duplicated here because the producing
///    blueprint owns the authoritative config.
///
/// The alias is the local TOML section name (e.g., `orders` from `[streams.orders]`) and is used by
/// slice code to resolve the resource via `@ResourceQualifier(config = "streams.<alias>")`.
public sealed interface StreamResource {
    String alias();

    record Owned(String alias, StreamVersionSpec version, StreamConfig config) implements StreamResource {}

    record External(String alias, StreamAddress target) implements StreamResource {}

    static StreamResource owned(String alias, StreamVersionSpec version, StreamConfig config) {
        return new Owned(alias, version, config);
    }

    static StreamResource external(String alias, StreamAddress target) {
        return new External(alias, target);
    }
}
