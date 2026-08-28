// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

/// Naming of the streams that back a durable topic (durable-pubsub-spec §3/§9).
///
/// Both names derive from the topic's CANONICAL address (`namespace:topic:version`), not the bare
/// topic name — two topics that merely share a bare name in different namespaces must never share
/// a backing stream (the RC2 #274 collision class, resolved for RPC dispatch by routing on the
/// canonical address; the stream layer inherits the same rule). The `topic:` prefix keeps the
/// runtime-created topic streams out of both the app `[streams.X]` namespace (bare kebab names)
/// and the `system:` family.
///
/// The DLQ suffix rides the topic-stream name (`topic:<address>.dlq`), so source and DLQ sort
/// adjacently in any stream listing and the pairing is derivable in both directions without a
/// registry.
public sealed interface DurableTopicNames {
    String TOPIC_STREAM_PREFIX = "topic:";
    String DLQ_SUFFIX = ".dlq";

    static String topicStream(String topicAddress) {
        return TOPIC_STREAM_PREFIX + topicAddress;
    }

    static String dlqStream(String topicAddress) {
        return topicStream(topicAddress) + DLQ_SUFFIX;
    }

    record unused() implements DurableTopicNames {}
}
