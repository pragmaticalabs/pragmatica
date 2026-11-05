// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.io.TimeSpan;

import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A node's store delivers its notifications on its dispatcher thread (D1, #2648), after `process()` returns. A test that
/// commits through `process()` and then reads what a subscriber derived waits here first: the subscriber's view is defined
/// by the DELIVERED notifications, so reading it before delivery asserts on a state nothing claimed.
public sealed interface KvDelivery {
    TimeSpan DELIVERY_BOUND = timeSpan(10).seconds();

    /// Waits until every batch the store has applied so far has been delivered to its subscribers.
    static void awaitDelivered(KVStore<?, ?> store) {
        store.awaitDelivered(store.enqueuedSeq())
             .await(DELIVERY_BOUND)
             .onFailure(cause -> fail("the committed batch must be delivered: " + cause.message()));
    }

    record unused() implements KvDelivery {}
}
