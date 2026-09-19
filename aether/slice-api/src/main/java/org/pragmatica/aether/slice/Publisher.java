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
    /// On a durable topic `idempotencyKey` becomes the event's message ID — the key downstream dedup
    /// (projection claims, idempotent subscribers) collapses duplicates on — instead of an ID minted per
    /// call. That makes a retry of the same logical event recognisable as a duplicate. Choose a key that
    /// names the logical event and is unique within the topic, e.g. `"order-42-placed"`; a blank key is
    /// refused.
    ///
    /// Retry after a [PublishOutcomeUnknown] is safe ONLY through this overload with the same key: the
    /// first attempt may already be in the log, and [#publish(Object)] would mint a second identity that
    /// dedup cannot match to the first.
    ///
    /// The default ignores the key and delegates to [#publish(Object)], which is exact for a tier that
    /// has no log and no message ID — the ephemeral RPC fan-out. A publisher that wraps another publisher
    /// MUST override this and forward the key, or the key is silently lost.
    default Promise<Unit> publish(T message, String idempotencyKey) {
        return publish(message);
    }
}
