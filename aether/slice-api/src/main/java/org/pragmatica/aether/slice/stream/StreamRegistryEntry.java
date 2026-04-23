// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.RetentionPolicy;

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
/// No @Codec yet: the registry is in-memory for RC1; KV-backed storage comes with the consensus
/// wiring PR, at which point this record (and the types it references) gets codecs added.
public record StreamRegistryEntry(StreamAddress address,
                                          RetentionPolicy retention,
                                          Instant registeredAt,
                                          RegisteredByKind registeredBy,
                                          int refCount) {
    public enum RegisteredByKind {
        /// Framework-internal streams (system namespace) registered at cluster bootstrap.
        FRAMEWORK,
        /// Application streams registered by blueprint deploy.
        BLUEPRINT
    }

    public static StreamRegistryEntry framework(StreamAddress address,
                                                 RetentionPolicy retention,
                                                 Instant registeredAt) {
        return new StreamRegistryEntry(address, retention, registeredAt, RegisteredByKind.FRAMEWORK, 1);
    }

    public static StreamRegistryEntry blueprint(StreamAddress address,
                                                 RetentionPolicy retention,
                                                 Instant registeredAt) {
        return new StreamRegistryEntry(address, retention, registeredAt, RegisteredByKind.BLUEPRINT, 1);
    }

    public StreamRegistryEntry withRefCount(int newRefCount) {
        return new StreamRegistryEntry(address, retention, registeredAt, registeredBy, newRefCount);
    }

    public StreamRegistryEntry incrementRef() {
        return withRefCount(refCount + 1);
    }

    public StreamRegistryEntry decrementRef() {
        return withRefCount(refCount - 1);
    }
}
