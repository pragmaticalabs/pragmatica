// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.journal;

import org.pragmatica.lang.Contract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Per-node persistent TRANSITION JOURNAL (cluster-topology-overhaul spec, Wave 1
/// Enrichment A — permanent, not temporary instrumentation).
///
/// A bounded, in-memory, structured ring buffer per layer that records **every
/// `MembershipFsm` transition** (layer [Layer#FSM]) **and every `PeerState` transition**
/// (layer [Layer#PEER]) as an [Entry] `(monotonicSeq, wallClockMs, layer, nodeId, from,
/// to, cause, incarnation?, role?)`. Properties (spec §5.1 item 1):
///
///   - **Bounded** — configurable cap per layer (default [#DEFAULT_CAPACITY_PER_LAYER],
///     overridable via the `aether.journal.capacityPerLayer` system property); oldest
///     entry evicted on overflow. Survives until process exit, not just a test window.
///   - **Dumpable on demand** — `GET /api/cluster/journal?layer=fsm|peer&limit=N`
///     (`ClusterJournalRoutes`) snapshots the tail; additionally the tail is
///     auto-flushed to the log on a WARN-class transition (FSM `→Dead`, PEER
///     `CONNECTED→EVICTED` or `→REMOVED`), rate-limited to one flush per
///     [#AUTO_FLUSH_MIN_INTERVAL_MS].
///   - **Not consensus, not KV** — pure local observability, lost on crash.
///
/// Thread safety: appends arrive from FSM tap threads and transport threads; each layer's
/// deque is guarded by its own monitor, the sequence is a process-wide [AtomicLong] (so
/// entries are totally ordered across layers). Appends are cheap and non-blocking and are
/// invoked from inside producer monitors — this class never calls back into producers.
public final class TransitionJournal {
    private static final Logger log = LoggerFactory.getLogger(TransitionJournal.class);

    public static final int DEFAULT_CAPACITY_PER_LAYER = 4096;
    /// Number of trailing entries dumped by the WARN-class auto-flush.
    private static final int AUTO_FLUSH_TAIL = 32;
    /// Minimum interval between WARN-class auto-flushes (shutdown removes every peer at
    /// once — without the limiter that would dump one tail per peer).
    private static final long AUTO_FLUSH_MIN_INTERVAL_MS = 10_000L;
    private static final String CAPACITY_PROPERTY = "aether.journal.capacityPerLayer";
    private static final TransitionJournal INERT = new TransitionJournal(0);

    /// Producing layer of a journal entry.
    public enum Layer {
        FSM,
        PEER
    }

    /// One journaled transition. `incarnation` is `-1` and `role` blank when the producing
    /// layer does not carry them (PEER entries, boot diagnostics).
    public record Entry(long seq,
                        long wallClockMs,
                        Layer layer,
                        String nodeId,
                        String from,
                        String to,
                        String cause,
                        long incarnation,
                        String role) {}

    private final int capacityPerLayer;
    private final AtomicLong sequence = new AtomicLong(0);
    private final AtomicLong lastAutoFlushMs = new AtomicLong(0);
    private final Deque<Entry> fsmEntries = new ArrayDeque<>();
    private final Deque<Entry> peerEntries = new ArrayDeque<>();

    private TransitionJournal(int capacityPerLayer) {
        this.capacityPerLayer = capacityPerLayer;
    }

    /// Production factory: capacity from the `aether.journal.capacityPerLayer` system
    /// property, defaulting to [#DEFAULT_CAPACITY_PER_LAYER].
    public static TransitionJournal transitionJournal() {
        return new TransitionJournal(Integer.getInteger(CAPACITY_PROPERTY, DEFAULT_CAPACITY_PER_LAYER));
    }

    /// Factory with an explicit per-layer capacity (tests / diagnostics).
    public static TransitionJournal transitionJournal(int capacityPerLayer) {
        return new TransitionJournal(capacityPerLayer);
    }

    /// Shared inert journal (capacity 0 — appends dropped, snapshots empty). Default for
    /// `ManageableNode` test proxies that never wire the live journal.
    public static TransitionJournal inert() {
        return INERT;
    }

    /// Append one transition. Cheap, non-blocking, never throws — safe to invoke from
    /// inside producer monitors. Oldest entry evicted when the layer ring is full.
    @Contract
    public void append(Layer layer, String nodeId, String from, String to, String cause, long incarnation, String role) {
        if (capacityPerLayer <= 0) {
            return;
        }
        var entry = new Entry(sequence.incrementAndGet(),
                              System.currentTimeMillis(),
                              layer,
                              nodeId,
                              from,
                              to,
                              cause,
                              incarnation,
                              role);
        var ring = ringFor(layer);
        synchronized (ring) {
            if (ring.size() >= capacityPerLayer) {
                ring.pollFirst();
            }
            ring.offerLast(entry);
        }
        autoFlushIfWarnClass(entry);
    }

    /// Snapshot the newest `limit` entries of `layer`, oldest-first (chronological).
    public List<Entry> snapshot(Layer layer, int limit) {
        var bounded = Math.max(0, limit);
        var ring = ringFor(layer);
        synchronized (ring) {
            var skip = Math.max(0, ring.size() - bounded);
            var out = new ArrayList<Entry>(Math.min(ring.size(), bounded));
            var index = 0;
            for (var entry : ring) {
                if (index >= skip) {
                    out.add(entry);
                }
                index++;
            }
            return out;
        }
    }

    private Deque<Entry> ringFor(Layer layer) {
        return layer == Layer.FSM
               ? fsmEntries
               : peerEntries;
    }

    /// Auto-flush the layer tail to the log on a WARN-class transition (spec §5.1 item 1:
    /// "dumpable … on a WARN/ERROR-class transition"). Rate-limited; log-only.
    @Contract
    private void autoFlushIfWarnClass(Entry entry) {
        if (!isWarnClass(entry)) {
            return;
        }
        var last = lastAutoFlushMs.get();
        if (entry.wallClockMs() - last < AUTO_FLUSH_MIN_INTERVAL_MS
            || !lastAutoFlushMs.compareAndSet(last, entry.wallClockMs())) {
            return;
        }
        log.warn("Transition journal WARN-class edge: layer={} node={} {}->{} cause={} (seq={}); last {} {} entries: {}",
                 entry.layer(),
                 entry.nodeId(),
                 entry.from(),
                 entry.to(),
                 entry.cause(),
                 entry.seq(),
                 AUTO_FLUSH_TAIL,
                 entry.layer(),
                 snapshot(entry.layer(), AUTO_FLUSH_TAIL));
    }

    private static boolean isWarnClass(Entry entry) {
        return switch (entry.layer()) {
            case FSM -> "Dead".equals(entry.to());
            case PEER -> ("EVICTED".equals(entry.to()) && "CONNECTED".equals(entry.from()))
                         || "REMOVED".equals(entry.to());
        };
    }
}
