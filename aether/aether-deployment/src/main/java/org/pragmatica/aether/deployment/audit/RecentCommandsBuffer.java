// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.audit;

import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;


/// In-memory ring buffer over the most recent `audit.lifecycle.commands` events seen by the
/// local node. Phase 3 PR-C (cluster-convergence-reconciler) backing store for the
/// `GET /api/audit/commands` operator endpoint.
///
/// Design rationale: building a full stream-consumer subscription on top of
/// `StreamReadRouter` for raw-byte payload decoding (Codec round-trip) would have required
/// substantial new infrastructure (offset tracking, partition fan-out, serializer wiring at
/// the REST layer). Per the PR-C scope note, this is acceptable because the audit channel
/// is an observability surface, not the source of truth (the `NodeLifecycleKey` KV store
/// owns membership state). The ring buffer is populated locally via a tee on the
/// `StreamPublisher<CommandLifecycleEvent>` wrapper that the same node uses to publish to
/// the stream — every command that `DirectLifecycleWriter.applyCommand` writes is also
/// captured here.
///
/// Capacity is fixed at construction; oldest entries are evicted when the buffer overflows.
/// All operations are synchronized — concurrent publishes from the lifecycle writer and
/// concurrent reads from the REST handler are safe.
///
/// Phase 4-5 reconciler can rely on this buffer for the same `cluster audit` inspection
/// surface — the source-attribution field on `CommandLifecycleEvent` lets operators filter
/// by `RECONCILER` once the reconciler is wired.
///
/// RC2 follow-up: replace with a proper `audit.lifecycle.commands` subscription via
/// `StreamReadRouter` so that audit history survives node restarts and so a follower can
/// surface events committed via leader writes.
public final class RecentCommandsBuffer {
    private final int capacity;
    private final Deque<CommandLifecycleEvent> entries;
    private final Object lock = new Object();

    private RecentCommandsBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.entries = new LinkedList<>();
    }

    /// Build a ring buffer with the supplied maximum entry count. `capacity` should be sized
    /// to the operator inspection window — the default in `AetherNode` is 1024.
    public static RecentCommandsBuffer recentCommandsBuffer(int capacity) {
        return new RecentCommandsBuffer(capacity);
    }

    /// Append a single audit event, evicting the oldest entry when the buffer is full.
    /// Called from `DirectLifecycleWriter` via the tee in `AetherNode`. Null entries are
    /// rejected — Codec round-trip never produces null and the caller is in-process so a
    /// defensive null guard is sufficient.
    public void record(CommandLifecycleEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (lock) {
            if (entries.size() >= capacity) {
                entries.pollFirst();
            }
            entries.addLast(event);
        }
    }

    /// Snapshot the current contents, applying optional time and source filters.
    ///
    ///   - `sinceMs` — return entries with `timestampMs() >= sinceMs`. `0` returns all
    ///     entries currently in the buffer.
    ///   - `source` — case-insensitive match against `CommandLifecycleEvent.source()`.
    ///     `null` / empty string / "all" returns all sources.
    ///   - `limit` — most-recent N entries from the filtered view. `<= 0` returns all
    ///     filtered entries (subject to buffer capacity).
    ///
    /// The returned list is newest-last (preserves insertion order). The list is a defensive
    /// copy — callers may mutate it safely.
    public List<CommandLifecycleEvent> snapshot(long sinceMs, String source, int limit) {
        var normalizedSource = normalizeSource(source);
        synchronized (lock) {
            var filtered = filterEntries(sinceMs, normalizedSource);
            return limit > 0 && limit < filtered.size()
                   ? new ArrayList<>(filtered.subList(filtered.size() - limit, filtered.size()))
                   : filtered;
        }
    }

    /// Convenience snapshot of all current entries with no filtering, used by tests.
    public List<CommandLifecycleEvent> snapshotAll() {
        return snapshot(0L, null, 0);
    }

    /// Current entry count — primarily for tests / metrics.
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /// Effective capacity — primarily for tests / metrics.
    public int capacity() {
        return capacity;
    }

    /// Build a tee on an upstream `StreamPublisher` so every publish call also lands in this
    /// buffer. The returned publisher delegates the asynchronous write to the upstream and
    /// records the event synchronously before delegation — so a slow upstream cannot delay
    /// observability of the most recent event. Upstream publish failures do not affect the
    /// local buffer; the entry is already recorded.
    public StreamPublisher<CommandLifecycleEvent> teeOn(StreamPublisher<CommandLifecycleEvent> upstream) {
        Objects.requireNonNull(upstream, "upstream");
        return event -> {
            record(event);
            return upstream.publish(event);
        };
    }

    /// Standalone publisher backed only by this buffer — used by tests that don't need a
    /// real stream publisher.
    public StreamPublisher<CommandLifecycleEvent> asPublisher() {
        return event -> {
            record(event);
            return Promise.unitPromise();
        };
    }

    private List<CommandLifecycleEvent> filterEntries(long sinceMs, String normalizedSource) {
        if (sinceMs <= 0 && normalizedSource == null) {
            return new ArrayList<>(entries);
        }
        var out = new ArrayList<CommandLifecycleEvent>(entries.size());
        for (var event : entries) {
            if (matches(event, sinceMs, normalizedSource)) {
                out.add(event);
            }
        }
        return out;
    }

    private static boolean matches(CommandLifecycleEvent event, long sinceMs, String normalizedSource) {
        if (sinceMs > 0 && event.timestampMs() < sinceMs) {
            return false;
        }
        return normalizedSource == null || normalizedSource.equalsIgnoreCase(event.source());
    }

    private static String normalizeSource(String source) {
        if (source == null) {
            return null;
        }
        var trimmed = source.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("all")) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    /// Test-only — clears the buffer. Not part of the operator API surface.
    void clearForTesting() {
        synchronized (lock) {
            entries.clear();
        }
    }

    /// Test-only — returns the unmodifiable raw view (newest last).
    List<CommandLifecycleEvent> rawForTesting() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(entries));
        }
    }
}
