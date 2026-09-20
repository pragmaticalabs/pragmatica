// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import java.util.HashMap;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// A [ProjectionStore] held in this process (#1333).
///
/// **What it is for, and what it is not.** It honours every contract on [ProjectionStore] — the
/// one-step reset, the generation fence, REBUILDING admission in offset order, stamped cursor
/// reports — for attempts INSIDE ONE PROCESS: every operation runs under one monitor, which is the
/// "one indivisible step" the interface asks for, scoped to this JVM. It is coherent for a projection
/// whose group has exactly one assignee (a single-partition topic, or a slice deployed to one node) and
/// for tests. It is NOT a shared backing: two nodes each holding one see two models, two generations
/// and two rewinds, and the generation slot does not survive the process — a rebuild after a restart
/// mints a token the cluster's fenced cursor may already outrank, which the node's rewind reports as a
/// refusal rather than silently hanging REBUILDING.
public final class InMemoryProjectionStore<S> implements ProjectionStore<S> {
    private final Map<String, S> data = new HashMap<>();
    private final Map<Integer, Long> nextReplayOffset = new HashMap<>();
    private final Map<Integer, Long> replayThrough = new HashMap<>();
    private long generation;
    private long rewinds;
    private Option<RewindToken> currentRewind = Option.none();

    private InMemoryProjectionStore() {}

    public static <S> InMemoryProjectionStore<S> inMemoryProjectionStore() {
        return new InMemoryProjectionStore<>();
    }

    @Override
    public synchronized Promise<Option<S>> read(String key) {
        return Promise.success(Option.option(data.get(key)));
    }

    @Override
    public synchronized Promise<WriteOutcome> write(String key,
                                                    S state,
                                                    long expectedGeneration,
                                                    Option<DeliveryPosition> position) {
        if (generation != expectedGeneration) {
            return Promise.success(WriteOutcome.STALE_GENERATION);
        }

        return Promise.success(position.map(at -> admitAt(key, state, at)).or(() -> admitPositionless(key, state)));
    }

    private WriteOutcome admitPositionless(String key, S state) {
        return replayThrough.isEmpty()
               ? written(key, state)
               : WriteOutcome.REBUILDING;
    }

    private WriteOutcome admitAt(String key, S state, DeliveryPosition at) {
        if (!replayThrough.containsKey(at.partition())) {
            return written(key, state);
        }

        var next = nextReplayOffset.get(at.partition());

        if (at.offset() < next) {
            return WriteOutcome.ALREADY_APPLIED;
        }

        if (at.offset() != next) {
            return WriteOutcome.REBUILDING;
        }

        advance(at.partition(), at.offset());

        return written(key, state);
    }

    private WriteOutcome written(String key, S state) {
        data.put(key, state);

        return WriteOutcome.WRITTEN;
    }

    /// Step a replaying partition past `offset`; past its head it goes LIVE on its own.
    private void advance(int partition, long offset) {
        nextReplayOffset.put(partition, offset + 1);
        if (offset + 1 > replayThrough.get(partition)) {
            nextReplayOffset.remove(partition);
            replayThrough.remove(partition);
        }
    }

    @Override
    public synchronized Promise<Long> resetToNewGeneration(ReplayRange range) {
        generation++;
        data.clear();
        nextReplayOffset.clear();
        replayThrough.clear();
        currentRewind = Option.none();
        range.partitions().forEach(this::startReplay);

        return Promise.success(generation);
    }

    /// An empty span (head below from) has nothing to replay: that partition is LIVE at once.
    private void startReplay(int partition, PartitionRange span) {
        if (span.throughOffset() >= span.fromOffset()) {
            nextReplayOffset.put(partition, span.fromOffset());
            replayThrough.put(partition, span.throughOffset());
        }
    }

    @Override
    public synchronized Promise<Unit> markReplayed(long expectedGeneration, DeliveryPosition at) {
        if (generation == expectedGeneration && replayThrough.containsKey(at.partition()) && nextReplayOffset.get(at.partition()) == at.offset()) {
            advance(at.partition(), at.offset());
        }

        return Promise.unitPromise();
    }

    /// The token is `(generation, rewind)` with `rewind` a store-wide counter, so tokens are strictly
    /// increasing in the order the runtime's `RewindEpoch` compares them.
    @Override
    public synchronized Promise<RewindToken> beginRewind(long expectedGeneration) {
        var minted = new RewindToken(expectedGeneration, ++rewinds);

        if (generation == expectedGeneration) {
            currentRewind = Option.some(minted);
        }

        return Promise.success(minted);
    }

    @Override
    public synchronized Promise<Unit> cursorCommitted(RewindToken token, int partition, long committedCursor) {
        if (currentRewind.filter(token::equals).isPresent() && replayThrough.containsKey(partition) && committedCursor > nextReplayOffset.get(partition)) {
            advance(partition, committedCursor - 1);
        }

        return Promise.unitPromise();
    }

    @Override
    public synchronized Promise<Long> generation() {
        return Promise.success(generation);
    }

    @Override
    public synchronized Promise<ReplayStatus> replayStatus() {
        var rebuilding = new HashMap<Integer, PartitionReplay>();

        replayThrough.forEach((partition, through) -> rebuilding.put(partition,
                                                                     new PartitionReplay(nextReplayOffset.get(partition),
                                                                                         through)));

        return Promise.success(new ReplayStatus(generation, Map.copyOf(rebuilding), currentRewind));
    }
}
