// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.serialization.Codec;

import java.time.Instant;


/// Registry entry for a stream.
///
/// Captures the address, retention policy, bootstrap metadata, and the current reference count.
/// A stream exists in the cluster iff a registry entry exists for its address (§7.1 of the
/// namespaces spec).
///
/// Reference counting (§8) determines lifecycle — when `refCount` reaches zero, the entry (and
/// the stream it represents) is removed.
///
/// `registeredAt` is exposed as a millisecond epoch on the codec wire form
/// (`registeredAtEpochMillis`) so the compile-time codec processor doesn't need a
/// `java.time.Instant` codec; the API surface retains the [Instant] convenience accessor.
@Codec public record StreamRegistryEntry(StreamAddress address,
                                          RetentionPolicy retention,
                                          long registeredAtEpochMillis,
                                          RegisteredByKind registeredBy,
                                          int refCount) {
    @Codec public enum RegisteredByKind {
        /// Framework-internal streams (system namespace) registered at cluster bootstrap.
        FRAMEWORK,
        /// Application streams registered by blueprint deploy.
        BLUEPRINT
    }

    public Instant registeredAt() {
        return Instant.ofEpochMilli(registeredAtEpochMillis);
    }

    public static StreamRegistryEntry framework(StreamAddress address,
                                                 RetentionPolicy retention,
                                                 Instant registeredAt) {
        return new StreamRegistryEntry(address, retention, registeredAt.toEpochMilli(), RegisteredByKind.FRAMEWORK, 1);
    }

    public static StreamRegistryEntry blueprint(StreamAddress address,
                                                 RetentionPolicy retention,
                                                 Instant registeredAt) {
        return new StreamRegistryEntry(address, retention, registeredAt.toEpochMilli(), RegisteredByKind.BLUEPRINT, 1);
    }

    public StreamRegistryEntry withRefCount(int newRefCount) {
        return new StreamRegistryEntry(address, retention, registeredAtEpochMillis, registeredBy, newRefCount);
    }

    public StreamRegistryEntry incrementRef() {
        return withRefCount(refCount + 1);
    }

    public StreamRegistryEntry decrementRef() {
        return withRefCount(refCount - 1);
    }
}
