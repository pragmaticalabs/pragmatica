// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


@FunctionalInterface
public interface Publisher<T> {
    Promise<Unit> publish(T message);

    /// Publish `message` under a caller-chosen, stable identity (#1237).
    ///
    /// On a durable topic `idempotencyKey` becomes the event's message ID — the key message-ID dedup
    /// (projection claims, idempotent subscribers) is specified to collapse duplicates on — instead of an
    /// ID minted per call, so a retry of the same logical event carries the same identity. Choose a key
    /// that names the logical event and is unique within the topic, e.g. `"order-42-placed"`; the durable
    /// publisher refuses a blank key (the ephemeral default below ignores the key entirely).
    ///
    /// A stable key is a NECESSARY condition for a dedup-safe retry after a [PublishOutcomeUnknown], not a
    /// sufficient one: the first attempt may already be in the log, and [#publish(Object)] would mint a
    /// second identity nothing could match to the first. Whether a subscriber actually collapses the pair
    /// depends on the delivery side. [unverified: messageId is not yet delivered to subscribers — #1295]
    /// And the durable publisher routes keyless events round-robin, so the retry may land on a different
    /// partition from the first copy and be dispatched concurrently with it (durable-pubsub-spec §8).
    ///
    /// The default ignores the key and delegates to [#publish(Object)], which is exact for a tier that
    /// has no log and no message ID — the ephemeral RPC fan-out. A publisher that wraps another publisher
    /// MUST override this and forward the key, or the key is silently lost.
    default Promise<Unit> publish(T message, String idempotencyKey) {
        return publish(message);
    }
}
