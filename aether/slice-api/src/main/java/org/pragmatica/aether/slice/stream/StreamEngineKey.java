// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.util.List;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.lang.Option;


/// The ONE mapping from a catalog [ResourceAddress] to the string the stream engine keys by: the full
/// `namespace:stream:version` address, in every namespace.
///
/// `system` streams used to reduce to their bare name here, but the framework creates, publishes and consumes
/// every system stream under `address.asString()` (`SystemStreamFactories`), so the management routes and a
/// blueprint `External` source addressed a ring that did not exist, and a user stream named `cluster-events`
/// would have been taken for the system one (#1338). The mapping is still a single shared function (#1040) so
/// the management path, the write-gate ([SystemStreams#isForbiddenEngineKey]) and the slice/engine
/// materialization path cannot drift apart again; it lives in `slice-api` because those consumers sit in
/// modules that cannot see each other.
///
/// Any test of this mapping must cover a `system` address AND a non-`system` address with the same stream name.
public sealed interface StreamEngineKey {
    /// The engine-level key for the given catalog address: the full `namespace:stream:version` address.
    static String engineKey(ResourceAddress address) {
        return address.asString();
    }

    /// Whether `engineKey` names a system stream: it carries the `system:` kind prefix. Writes to such a stream
    /// are the node's own system publishers' alone (#1338).
    static boolean isSystemStream(String engineKey) {
        return engineKey.startsWith(ResourceAddress.SYSTEM_NAMESPACE + ":");
    }

    /// Engine-key prefixes marking a stream KIND that only internal provisioning creates (#1282): `system:`
    /// (system streams, named from their `system`-namespace address), `topic:` (durable topics and their
    /// DLQs) and `entity:` (entity keyspace logs). Runtime rules key off these prefixes, so no user input —
    /// a Management-API write or a Management-API write — may mint under one; a blueprint `External` source in the `system` namespace is a read-only reference, not a mint, and is the one exception. Declared here because
    /// the blueprint parser (aether/slice) cannot see the canonical owners (`DurableTopicNames` in
    /// aether-stream, `EntityPartitionArc` in aether-dht); `ReservedStreamNamesTest` in aether/node pins
    /// this list against them.
    List<String> RESERVED_KIND_PREFIXES = List.of(ResourceAddress.SYSTEM_NAMESPACE + ":", "topic:", "entity:");

    /// The reserved kind prefix `engineKey` starts with, or none.
    static Option<String> reservedKindPrefixOf(String engineKey) {
        return Option.from(RESERVED_KIND_PREFIXES.stream().filter(engineKey::startsWith).findFirst());
    }

    record unused() implements StreamEngineKey {}
}
