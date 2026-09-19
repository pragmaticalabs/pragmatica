// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.util.List;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.lang.Option;


/// The ONE reduction from a catalog [ResourceAddress] to the string the stream engine keys by.
///
/// `system` streams reduce to their bare name; everything else keys by the full
/// `namespace:name:version` address. The rule itself is unchanged — what changed (#1040) is that it
/// now has exactly one implementation instead of three.
///
/// It lives here, in `slice-api`, because all three consumers sit in modules that cannot see each
/// other: `StreamManager` (aether-node, the management/API routes), [SystemStreams#isForbiddenEngineKey]
/// (this module, the management write-gate), and the slice/engine materialization path
/// (aether-stream's resource factories, aether-deployment's subscription registration). Before #1040
/// the first two each recomputed the reduction locally and the third did not apply it at all, so one
/// blueprint declaration produced two live rings under two spellings — the management path addressing
/// `ns:stream:version` while the engine materialized the bare `resources.toml` section name, with the
/// declared durability contract silently void on the catalog path.
///
/// WHY A SHARED FUNCTION IS THE FIX AND A SHARED CONVENTION IS NOT. The two spellings agreed for
/// `system:cluster-events` — the one address whose reduction is its own bare name — which is exactly
/// the stream every diagnostic reaches for as a positive control. A control drawn from the one case
/// where both branches coincide cannot observe a divergence between them, whatever else it proves.
/// Any test of this reduction must use a NON-`system` namespace.
public sealed interface StreamEngineKey {
    /// The engine-level key for the given catalog address: the bare stream name for `system`
    /// streams, the full catalog address otherwise.
    static String engineKey(ResourceAddress address) {
        return address.isSystem()
               ? address.name()
                        .value()
               : address.asString();
    }

    /// Engine-key prefixes marking a stream KIND that only internal provisioning creates (#1282): `system:`
    /// (system streams, named from their `system`-namespace address), `topic:` (durable topics and their
    /// DLQs) and `entity:` (entity keyspace logs). Runtime rules key off these prefixes, so no user input —
    /// a Management-API write or a blueprint `External` source — may mint under one. Declared here because
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
