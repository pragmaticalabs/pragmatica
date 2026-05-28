// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.TimeSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Stage 5 / E1 divergence logger (membership v2 spec §13). Captures NTT's would-do
/// [`ReconcileIntent`]s side-by-side with the FSM's actually-do [`FsmDecisionEvent`]s so the
/// E3 chaos suite can quantify NTT-vs-FSM equivalence empirically.
///
/// **Observation-only.** Emits structured log lines tagged `[v2-divergence]` for grep-based
/// post-run analysis. No production behaviour depends on this class. Aggregation /
/// equivalence decisions happen at E3.
///
/// **Correlation.** Two parallel per-peer buffers retain the latest NTT intent and latest
/// FSM decision keyed by [`NodeId`]. A periodic [`#runCorrelationSweep`] (scheduled by
/// Stage 6 on the same tick as [`LeaderReconciler`]) walks the buffers:
/// - both present within the [`#CORRELATION_WINDOW`] → emit `verdict=ALIGNED`, drop both
///   sides for the peer
/// - only one present and buffered longer than the window → emit
///   `verdict=DIVERGENT side=NTT_ONLY|FSM_ONLY`, drop that side
///
/// Stale-side eviction reuses the buffered timestamp (`TimeSource`-derived nanos at observe
/// time), not wall-clock; so the sweep is deterministic under a fake [`TimeSource`].
///
/// **Concurrency.** Both buffers are [`ConcurrentHashMap`]s. The log emit callback
/// (`Consumer<String>`) is volatile-free — set once at construction. Sweep is single-threaded
/// per [`Stage 6`] scheduling; concurrent observations from NTT- and FSM-side threads are
/// safe because they touch disjoint maps.
///
/// **Scope.** Cluster-wide ([`#peerId`] = `None`) NTT/FSM intents are not currently correlated
/// — they are logged but skipped during sweep. Stage 6+ may add a dedicated cluster-wide
/// correlation bucket if E3 metrics surface a need.
public final class DivergenceLogger {
    private static final Logger log = LoggerFactory.getLogger(DivergenceLogger.class);

    /// Correlation window (E1 default — lift to [`MembershipConfig`] if it becomes
    /// configurable in Stage 6+). Long enough to absorb the gap between NTT's claim/timer-fire
    /// and the FSM's consensus-applied write, short enough that genuinely orphan intents are
    /// flagged within one tick period.
    static final TimeSpan CORRELATION_WINDOW = timeSpan(30L).seconds();

    private static final String TAG = "[v2-divergence]";

    private final TimeSource timeSource;
    private final Consumer<String> emit;

    private final ConcurrentHashMap<NodeId, BufferedFsm> fsmBuffer = new ConcurrentHashMap<>();

    private DivergenceLogger(TimeSource timeSource, Consumer<String> emit) {
        this.timeSource = timeSource;
        this.emit = emit;
    }

    /// Production factory. Emits to this class's SLF4J logger at INFO so post-run grep over
    /// the standard aether-node log file finds every line.
    public static DivergenceLogger divergenceLogger(TimeSource timeSource) {
        return new DivergenceLogger(timeSource, log::info);
    }

    /// Test factory. The supplied [`Consumer`] receives every emitted line verbatim, letting
    /// tests assert on full structured strings without intercepting SLF4J.
    public static DivergenceLogger divergenceLogger(TimeSource timeSource, Consumer<String> emit) {
        return new DivergenceLogger(timeSource, emit);
    }

    /// Invoked by `LeaderReconciler.setReconcileListener(...)` for every emitted intent.
    /// Logs the intent as a cluster-wide observation. NTT intents no longer carry per-peer
    /// payload (E2 Phase 1.5 simplification — only the count delta is reported), so the
    /// previous per-peer ALIGNED correlation against FSM decisions is no longer possible.
    /// Phase 2 will likely retire this class entirely now that the reconcile is fully
    /// state-derived.
    @Contract
    public void observeNttIntent(ReconcileIntent intent) {
        emit.accept(formatNttLine(intent));
    }

    /// Invoked by Stage 6 from the FSM-side hook for every committed (or no-op'd) decision.
    /// Logs the decision and buffers it per-peer for the next [`#runCorrelationSweep`].
    /// Buffer timestamp uses the LOGGER's [`TimeSource`] (not the event's own
    /// `observedAtNanos`) so sweep eviction stays deterministic under a fake clock; the
    /// event's source-side timestamp is preserved in the emitted log line for E3 analysis.
    @Contract
    public void observeFsmDecision(FsmDecisionEvent event) {
        emit.accept(formatFsmLine(event));

        var observedNanos = timeSource.nanoTime();
        event.peerId().onPresent(peer -> fsmBuffer.put(peer, new BufferedFsm(observedNanos, event)));
    }

    /// Walk the FSM-side buffer; emit `verdict=DIVERGENT side=FSM_ONLY` for any FSM
    /// decision still buffered past the correlation window. NTT-side ALIGNED detection
    /// was retired in E2 Phase 1.5 (no per-peer payload on the NTT side).
    @Contract
    public void runCorrelationSweep() {
        var now = timeSource.nanoTime();
        var windowNanos = CORRELATION_WINDOW.nanos();

        Map.copyOf(fsmBuffer).forEach((peer, fsm) -> evaluateLoneFsmPeer(peer, fsm, now, windowNanos));
    }

    /// Observability — NTT side no longer buffers per-peer. Returns 0 unconditionally.
    public int bufferedNttCount() {
        return 0;
    }

    /// Observability — current size of the FSM-side correlation buffer.
    public int bufferedFsmCount() {
        return fsmBuffer.size();
    }

    private void evaluateLoneFsmPeer(NodeId peer, BufferedFsm fsm, long now, long windowNanos) {
        if (now - fsm.observedAtNanos > windowNanos) {
            emit.accept(formatDivergentFsmOnly(peer, fsm, now));
            fsmBuffer.remove(peer);
        }
    }

    private static String formatNttLine(ReconcileIntent intent) {
        return TAG
               + " source=NTT  peer=<cluster-wide>"
               + " ts=" + intent.observedAtNanos()
               + " trigger=" + intent.trigger()
               + " configured=" + intent.configuredCoreCount()
               + " observed=" + intent.clusterMembershipCount()
               + " action=" + describeAction(intent);
    }

    private static String formatFsmLine(FsmDecisionEvent event) {
        return TAG
               + " source=FSM  peer=" + describePeerOption(event.peerId())
               + " ts=" + event.observedAtNanos()
               + " type=" + event.type()
               + " reason=" + event.reason()
               + " stateBefore=" + event.fsmStateBefore()
               + " stateAfter=" + event.fsmStateAfter();
    }

    private static String formatDivergentFsmOnly(NodeId peer, BufferedFsm fsm, long now) {
        var bufferedMs = TimeUnit.NANOSECONDS.toMillis(now - fsm.observedAtNanos);

        return TAG
               + " verdict=DIVERGENT   peer=" + peer.id()
               + " side=FSM_ONLY"
               + " buffered_for_ms=" + bufferedMs
               + " details=type=" + fsm.event.type()
               + ",reason=" + fsm.event.reason()
               + ",stateBefore=" + fsm.event.fsmStateBefore()
               + ",stateAfter=" + fsm.event.fsmStateAfter();
    }

    private static String describePeerOption(Option<NodeId> peerId) {
        return peerId.map(NodeId::id).or("<cluster-wide>");
    }

    private static String describeAction(ReconcileIntent intent) {
        var provisionCount = intent.provisionCount();
        var drainCount = intent.drainCount();

        if (provisionCount == 0 && drainCount == 0) {
            return "none";
        }
        if (provisionCount > 0 && drainCount == 0) {
            return "provision";
        }
        if (drainCount > 0 && provisionCount == 0) {
            return "drain";
        }

        return "mixed";
    }

    private record BufferedFsm(long observedAtNanos, FsmDecisionEvent event) {}
}
