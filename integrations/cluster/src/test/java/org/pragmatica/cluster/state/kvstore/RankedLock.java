// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/// The test-build ranked lock of I-W13. Ranks follow the lock order `mutationLock` (1) < store monitor (2) < ring lock (3). Acquiring a rank
/// while the same thread holds a HIGHER rank is an out-of-order acquire: it is recorded and thrown. The ring lock is wrapped directly (the
/// ring takes its lock as a parameter); the store's two monitors cannot be wrapped, so a ranked store subclass declares them through
/// [#underRank] at its entry points before the monitors are taken.
final class RankedLock extends ReentrantLock {
    static final int MUTATION = 1;
    static final int STORE = 2;
    static final int RING = 3;

    private static final ThreadLocal<int[]> HELD = ThreadLocal.withInitial(() -> new int[RING + 1]);
    private static final List<String> VIOLATIONS = new CopyOnWriteArrayList<>();
    private static final AtomicLong ACQUISITIONS = new AtomicLong();

    private final int rank;

    RankedLock(int rank) {
        this.rank = rank;
    }

    @Override
    public void lock() {
        enter(rank);
        super.lock();
        ACQUISITIONS.incrementAndGet();
    }

    @Override
    public void unlock() {
        super.unlock();
        exit(rank);
    }

    /// Runs `body` as a holder of `rank`, after checking that no higher rank is held by this thread.
    static <T> T underRank(int rank, Supplier<T> body) {
        enter(rank);
        try {
            return body.get();
        } finally {
            exit(rank);
        }
    }

    static List<String> violations() {
        return List.copyOf(VIOLATIONS);
    }

    static void clearViolations() {
        VIOLATIONS.clear();
    }

    /// How many times any ranked lock was acquired: a control that the code under test really takes the wrapped lock.
    static long acquisitions() {
        return ACQUISITIONS.get();
    }

    private static void enter(int rank) {
        var held = HELD.get();

        for (var higher = rank + 1; higher < held.length; higher++) {
            if (held[higher] > 0) {
                var violation = "out-of-order acquire on " + Thread.currentThread().getName() + ": rank " + rank + " while holding rank " + higher;

                VIOLATIONS.add(violation);
                throw new IllegalStateException(violation);
            }
        }
        held[rank]++;
    }

    private static void exit(int rank) {
        HELD.get()[rank]--;
    }
}
