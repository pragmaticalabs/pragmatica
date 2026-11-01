// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;


/// One window's governor announcements as ONE submission to the core's log (#2542, the batch rule): the
/// transactions collected within `window`, or up to `cap` of them, go to consensus as one command list. This is a
/// batch, not an atomic unit: every transaction keeps its own witnesses and is accepted or refused on its own, and
/// the list only keeps them together in the log. Batching is per component (this one), never through a collector
/// shared with other components.
///
/// Repeated writes to one announcement key within a window collapse to the latest one, which shares the earlier one's
/// transaction id so that every waiter on the key finds its outcome under that id. A write for the same key by ANOTHER
/// nominee is a competing claim, not a repeat: it closes the window first, so the earlier claim is submitted before it,
/// exactly the order the requests arrived in.
///
/// `cap == 1` submits every transaction on the calling thread, unbatched. The window and cap that production runs with
/// are `GovernorAuthority.DEFAULT_COMMIT_WINDOW` and `DEFAULT_COMMIT_CAP`.
@SuppressWarnings("JBCT-RET-01")
final class GovernorCommitWindow {
    /// Where a transaction's outcome will be found: the batch's outcomes, and the id to look for in them.
    record Ticket(Promise<List<Object>> outcomes, String transactionId) {}

    private record Entry(NodeId nominee, String transactionId, KVCommand<AetherKey> command) {}

    private record Batch(Promise<List<Object>> outcomes, List<KVCommand<AetherKey>> commands) {}

    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> submit;
    private final TimeSpan window;
    private final int cap;
    private final Map<AetherKey, Entry> entries = new LinkedHashMap<>();
    private Promise<List<Object>> outcomes = Promise.promise();

    GovernorCommitWindow(Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> submit, TimeSpan window, int cap) {
        this.submit = submit;
        this.window = window;
        this.cap = cap;
    }

    /// Queues the transaction `build` makes for `key`, claimed by `nominee`; `build` receives the transaction id to use.
    Ticket enqueue(AetherKey key, NodeId nominee, Function<String, KVCommand<AetherKey>> build) {
        Option<Batch> closed;
        Option<Batch> full;
        Ticket ticket;

        synchronized (this) {
            closed = competes(key, nominee)
                     ? take()
                     : Option.none();
            var id = Option.option(entries.get(key)).map(Entry::transactionId).or(() -> UUID.randomUUID().toString());
            var opening = entries.isEmpty();

            entries.put(key, new Entry(nominee, id, build.apply(id)));
            ticket = new Ticket(outcomes, id);
            full = entries.size() >= cap
                   ? take()
                   : Option.none();
            if (opening && full.isEmpty()) {
                scheduleFlush(outcomes);
            }
        }

        closed.onPresent(this::send);
        full.onPresent(this::send);

        return ticket;
    }

    private boolean competes(AetherKey key, NodeId nominee) {
        return Option.option(entries.get(key))
                     .filter(entry -> !entry.nominee()
                                            .equals(nominee))
                     .isPresent();
    }

    private void scheduleFlush(Promise<List<Object>> target) {
        SharedScheduler.schedule(() -> flush(target), window);
    }

    /// A timer closes only the window it was started for: one that was already submitted early leaves its successor alone.
    private void flush(Promise<List<Object>> target) {
        Option<Batch> due;

        synchronized (this) {
            due = outcomes == target
                  ? take()
                  : Option.none();
        }

        due.onPresent(this::send);
    }

    private Option<Batch> take() {
        if (entries.isEmpty()) {
            return Option.none();
        }

        var batch = new Batch(outcomes,
                              entries.values().stream().map(Entry::command).toList());

        entries.clear();
        outcomes = Promise.promise();

        return Option.some(batch);
    }

    private void send(Batch batch) {
        submit.apply(batch.commands()).onResult(batch.outcomes()::resolve);
    }
}
