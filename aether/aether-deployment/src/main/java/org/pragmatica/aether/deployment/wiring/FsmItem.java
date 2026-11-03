// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.concurrent.CountDownLatch;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// What an FSM interpreter's mailbox carries (phase-3 design 3.1, 4.1): the FSM's own inputs, plus the few things only the interpreter can
/// say about itself. `I` is the FSM's input type, `K` its timer identity.
sealed interface FsmItem<I, K> {
    /// An input that needs no stamp (a completion, a membership or lifecycle edge, the FSM's own `Enqueue`).
    record Input<I, K>(I input) implements FsmItem<I, K> {}

    /// A KV-sourced input, stamped at delivery with the revision of the batch that produced it and the replay flag (design 4.4). The
    /// consumer applies the core rule: a replay-flagged input is dropped, and so is one at or below the revision of the last snapshot.
    record Kv<I, K>(I input, long revision, boolean replay) implements FsmItem<I, K> {}

    /// A timer body's tick; stepped only if the timer map still holds that id at that generation (design 4.3).
    record Tick<I, K>(FsmTimers.Tick<K> tick) implements FsmItem<I, K> {}

    /// A request to enter: the consumer snapshots only while the FSM is not yet active (design 4.1, NI-1, NI-6). `reconcile` is the 5 s
    /// level-heal driver, not a genuine quorum edge.
    record Entry<I, K>(boolean reconcile) implements FsmItem<I, K> {}

    /// A marker the consumer resolves when it reaches it: everything enqueued before it has been handled (design 7, 2a).
    record Barrier<I, K>(Promise<Unit> reached) implements FsmItem<I, K> {}

    /// The node's snapshot was restored from a committed one (`onStateRestored`, B14): the consumer judges, from its own state, whether the FSM
    /// must be re-incarnated (design 4.4).
    record Restored<I, K>() implements FsmItem<I, K> {}

    /// The `Close` of the interpreter: cancel timers, stop the consumer (design 7, item 22).
    record Close<I, K>(CountDownLatch done) implements FsmItem<I, K> {}
}
