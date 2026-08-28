// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import java.util.List;

import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Stream-family routing over the dead-letter seam (durable-pubsub-spec §9 wiring): appends for
/// `topic:*` streams go to the durable [DlqStreamSink]; everything else keeps the sink it always
/// had. This is how ONE consumer runtime serves both declarative stream consumers (whose
/// volatile-default question is a separate ticket's business — ruling on #386) and durable-topic
/// groups (whose dead letters MUST survive restarts) without the runtime growing per-subscription
/// sink plumbing.
///
/// The name families cannot collide: `topic:*` is reserved by [DurableTopicNames], and the
/// `[streams.X]` declaration grammar never admits a `topic:`-prefixed name.
public record RoutingDeadLetterSink(DeadLetterHandler topicSink, DeadLetterHandler fallback) implements DeadLetterHandler {
    @Override
    public Promise<Unit> append(String streamName,
                                int partition,
                                long offset,
                                String failingGroup,
                                byte[] payload,
                                String errorMessage,
                                int attemptCount) {
        return sinkFor(streamName).append(streamName,
                                          partition,
                                          offset,
                                          failingGroup,
                                          payload,
                                          errorMessage,
                                          attemptCount);
    }

    @Override
    public List<DeadLetterEntry> read(String streamName, int maxCount) {
        return sinkFor(streamName).read(streamName, maxCount);
    }

    private DeadLetterHandler sinkFor(String streamName) {
        return DurableTopicNames.isTopicStream(streamName)
               ? topicSink
               : fallback;
    }
}
