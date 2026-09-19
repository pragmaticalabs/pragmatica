// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.stream.topic.DurableTopicNames;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// #1282: engine-name prefixes that mark a stream KIND provisioned only by the runtime itself —
/// `system:` (system streams, named `system:<name>:<version>` from their `system`-namespace address),
/// `topic:` (durable topics and their DLQs, [DurableTopicNames]) and `entity:` (entity keyspace logs,
/// [EntityPartitionArc]). Runtime rules key off these prefixes, so a Management-API write must never mint
/// a stream under one with an operator-chosen config. Each prefix is taken from its canonical owner so the
/// guard cannot drift from the names internal provisioning actually uses. Applied to the ENGINE name: a
/// catalog address in the `system` namespace reduces to its bare name ([StreamManager#engineKey]) — the
/// flat operator-stream spelling — and is not reserved; a `topic`/`entity` namespace yields a prefixed
/// engine key and is.
sealed interface ReservedStreamNames {
    List<String> PREFIXES = List.of(ResourceAddress.SYSTEM_NAMESPACE + ":",
                                    DurableTopicNames.TOPIC_STREAM_PREFIX,
                                    EntityPartitionArc.ARC_PREFIX);

    /// The engine name unchanged, or [ManagementServerError.ReservedStreamName] naming the prefix it uses.
    static Result<String> requireUnreserved(String engineName) {
        return reservedPrefixOf(engineName).map(prefix -> refuse(engineName, prefix))
                                           .or(() -> success(engineName));
    }

    private static Option<String> reservedPrefixOf(String engineName) {
        return Option.from(PREFIXES.stream()
                                   .filter(engineName::startsWith)
                                   .findFirst());
    }

    private static Result<String> refuse(String engineName, String prefix) {
        return new ManagementServerError.ReservedStreamName(engineName, prefix).result();
    }

    record unused() implements ReservedStreamNames {}
}
