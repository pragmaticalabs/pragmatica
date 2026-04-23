// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;


/// Stream registry — canonical catalog of registered stream addresses in a cluster.
///
/// The registry is the central piece of the namespacing scheme: every addressable stream has
/// exactly one registry entry, and the entry's lifecycle (creation, reference counting, deletion)
/// corresponds to the stream's own lifecycle per spec §8.
///
/// For RC1 this interface has a single in-memory implementation ([InMemoryStreamRegistry]).
/// The consensus-backed implementation — which replicates the entries through Rabia — is a
/// follow-up PR and must satisfy the same interface to preserve test/runtime substitutability.
public interface StreamRegistry {
    sealed interface StreamRegistryError extends Cause {
        enum General implements StreamRegistryError {
            ALREADY_REGISTERED("Stream already registered at this address"),
            NOT_FOUND("Stream not found"),
            NO_VERSIONS_REGISTERED("No versions registered for this (namespace, stream)");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements StreamRegistryError {
            @Override public String message() {
                return "";
            }
        }
    }

    /// Register a new entry. Returns [General.ALREADY_REGISTERED] if an entry already exists at
    /// the same address. Registration is idempotent only if the caller explicitly checks for
    /// existence first; the default contract treats duplicate registration as an error so callers
    /// notice unexpected collisions.
    Result<StreamRegistryEntry> register(StreamRegistryEntry entry);

    /// Fetch the entry for an exact address. [Option.none] if not found.
    Option<StreamRegistryEntry> lookup(StreamAddress address);

    /// Resolve a version spec to a concrete entry.
    ///
    ///  - [StreamVersionSpec.Exact] — exact lookup of `(namespace, stream, version)`.
    ///  - [StreamVersionSpec.Latest] — pick the highest registered version in
    ///    `(namespace, stream, *)` by semver ordering; fails with [General.NO_VERSIONS_REGISTERED]
    ///    if no version is registered.
    Result<StreamRegistryEntry> resolve(String namespace, String stream, StreamVersionSpec spec);

    /// Increment the reference count for the entry at the given address. Fails if no entry exists.
    Result<StreamRegistryEntry> acquireReference(StreamAddress address);

    /// Decrement the reference count for the entry at the given address. When the count reaches
    /// zero the entry is removed. Fails if no entry exists or the count is already zero.
    Result<ReleaseOutcome> releaseReference(StreamAddress address);

    /// Snapshot of all registered entries in undefined order.
    List<StreamRegistryEntry> snapshot();

    record ReleaseOutcome(StreamRegistryEntry entry, boolean removed) {}
}
