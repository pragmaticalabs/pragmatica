// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.slice.stream.StreamEngineKey;
import org.pragmatica.aether.stream.topic.DurableTopicNames;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// #1282: engine-name prefixes that mark a stream KIND provisioned only by the runtime itself —
/// `system:` (system streams), `topic:` (durable topics and their DLQs) and `entity:` (entity keyspace
/// logs). Runtime rules key off these prefixes, so a Management-API write must never mint a stream under
/// one with an operator-chosen config. The prefix list is [StreamEngineKey#RESERVED_KIND_PREFIXES], shared
/// with the blueprint parser's `External` source check; `ReservedStreamNamesTest` pins it against the
/// canonical owners ([DurableTopicNames], [EntityPartitionArc], the `system` namespace). Applied to the
/// ENGINE name: a catalog address in the `system` namespace reduces to its bare name
/// ([StreamManager#engineKey]) — the flat operator-stream spelling — and is not reserved; a
/// `topic`/`entity` namespace yields a prefixed engine key and is.
sealed interface ReservedStreamNames {
    /// The engine name unchanged, or [ManagementServerError.ReservedStreamName] naming the prefix it uses.
    static Result<String> requireUnreserved(String engineName) {
        return StreamEngineKey.reservedKindPrefixOf(engineName)
                              .map(prefix -> refuse(engineName, prefix))
                              .or(() -> success(engineName));
    }

    private static Result<String> refuse(String engineName, String prefix) {
        return new ManagementServerError.ReservedStreamName(engineName, prefix).result();
    }

    /// The predicate the pre-auth path gate applies, with the SAME canonicalization in front of it
    /// (#742 review SF-2): a body-carried name may be the bare engine key (`cluster-events`) or the
    /// catalog spelling (`system:cluster-events:1.0.0`); the versioned gate reduces the latter through
    /// `ResourceAddress` → `StreamManager.engineKey` before asking `SystemStreams`, and so does this.
    /// A name that does not parse as an address is checked as the bare key it is. Shared by the
    /// body-carried routes the gate cannot see: `STREAM_CREATE` (#968, in [StreamApiRoutes]) and the
    /// consumer-group join/leave (in [StreamRoutes]).
    static boolean namesSystemStream(String name) {
        return SystemStreams.isForbiddenEngineKey(name) || ResourceAddress.resourceAddress(name)
                                                                          .map(StreamManager::engineKey)
                                                                          .map(SystemStreams::isForbiddenEngineKey)
                                                                          .or(false);
    }

    record unused() implements ReservedStreamNames {}
}
