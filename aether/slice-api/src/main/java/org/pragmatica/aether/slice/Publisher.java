// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


@FunctionalInterface
public interface Publisher<T> {
    Promise<Unit> publish(T message);

    /// Publish `message` under a caller-chosen, stable identity (#1237).
    ///
    /// On a durable topic `idempotencyKey` is stamped on the event as its message ID, instead of an ID
    /// minted per call, so a retry of the same logical event carries the same identity. It is an identity,
    /// not a guard: the log does NOT deduplicate, and a retry appends a second copy under the same ID.
    /// Choose a key that names the logical event and is unique within the topic, e.g. `"order-42-placed"`;
    /// the durable publisher refuses a blank key (the ephemeral default below ignores the key entirely).
    ///
    /// A stable key is a NECESSARY condition for a dedup-safe retry after a [PublishOutcomeUnknown], not a
    /// sufficient one: the first attempt may already be in the log, and [#publish(Object)] would mint a
    /// second identity nothing could match to the first. Collapsing the pair is the subscriber's work: a
    /// subscriber that declares the context-carrying handler shape receives the ID in its
    /// [org.pragmatica.aether.slice.topic.MessageContext] and must deduplicate on it itself. Two routes are
    /// built in: the projection facade folds under a claim on `(projectionName, generation, messageId)`, and
    /// a subscriber can opt into `IdempotencyMethodInterceptor` with a key extractor reading the `messageId`.
    /// And the durable publisher routes events without a partition key round-robin, so the retry may land
    /// on a different partition from the first copy and be dispatched concurrently with it
    /// (durable-pubsub-spec §8).
    ///
    /// The default ignores the key and delegates to [#publish(Object)], which is exact for a tier that
    /// has no log and no message ID — the ephemeral RPC fan-out. A publisher that wraps another publisher
    /// MUST override this and forward the key, or the key is silently lost.
    default Promise<Unit> publish(T message, String idempotencyKey) {
        return publish(message);
    }
}
