/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.net.quic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.StreamType;

import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


/// Wraps a QUIC connection to a single peer with named streams.
///
/// Long-lived streams (consensus, KV store) are opened once and reused.
/// Short-lived streams (HTTP forward, DHT relay) are opened per exchange.
public final class QuicPeerConnection {
    /// Lazily (re)opens a missing long-lived lane stream on this live connection. Installed by the
    /// transport endpoint that built the connection (dialer/acceptor): it creates a fresh
    /// bidirectional stream, writes the lane preamble, installs the framing + data handler, registers
    /// the stream via [#registerStream], and calls back with the registered stream (or empty on
    /// failure). This restores the dial-path "all lanes present" invariant on the ACCEPTOR path,
    /// where [#registerPeerConnection] publishes the peer CONNECTED with ONLY the CONTROL lane and
    /// the data lanes register asynchronously as the dialer's preamble frames arrive — a write that
    /// races that window otherwise fails "No stream available" on a perfectly healthy link.
    @FunctionalInterface
    public interface LaneOpener {
        /// Open `lane` on the connection and invoke `onResult` with the registered stream (some) or
        /// empty when the open failed / the connection is dead. Asynchronous: `onResult` runs on the
        /// connection's event loop once the stream is created. No-op opener (the default) reports empty.
        @Contract
        void open(StreamType lane, Consumer<Option<QuicStreamChannel>> onResult);

        /// No-op opener: reports empty for every lane. Used for connections built by unit fixtures
        /// that never drive real streams, so lazy-open degrades to the BACKSTOP eviction path.
        static LaneOpener noop() {
            return (lane, onResult) -> onResult.accept(Option.empty());
        }
    }

    /// #718 — admission decision for a lazy lane (re)open: does THIS caller own the single in-flight
    /// open for the lane, or did it join one already running?
    ///
    /// Three outcomes were needed, not two, and that is the whole point of the type. The pre-#718 code
    /// expressed the open's result as `Option<QuicStreamChannel>`, where empty means "unhealable —
    /// evict the connection" (the BACKSTOP). A suppressed duplicate is NOT that: it is a healthy
    /// coalesce onto a healing lane, and reporting it as empty would fire the BACKSTOP eviction on
    /// every deduplicated message — converting a volume defect into a connection-churn defect.
    public sealed interface LaneOpenAdmission {
        /// No open was in flight for this lane: the caller owns it and must drive
        /// [QuicPeerConnection#openLane], then hand the opened stream every message that
        /// [QuicPeerConnection#completeLaneOpen] returns.
        record Started() implements LaneOpenAdmission {}

        /// An open for this lane was already in flight; the offered message joined its pending queue
        /// and will be written when that open completes. `oldestDropped` is true when the queue was at
        /// [QuicPeerConnection#PENDING_LANE_WRITES_MAX] and the oldest pending message was dropped to
        /// make room — the same drop-oldest policy, and the same reporting shape, as
        /// `PeerState.OfferOutcome.Queued`.
        record Coalesced(boolean oldestDropped) implements LaneOpenAdmission {}
    }

    /// #718 — bound on messages retained per (peer, lane) while one lane open is in flight.
    ///
    /// The coalescing window is a single lane-open round trip on an ALREADY-LIVE QUIC connection
    /// (create stream, write preamble) — sub-millisecond locally, one RTT at worst — so this bound is
    /// only ever reached by a genuine burst. 64 is chosen to mirror
    /// `initialMaxStreamsBidirectional(64)`, the very credit this dedup exists to stop exhausting:
    /// the connection retains at most as many queued messages per lane as it has streams in total.
    /// That tie is a deliberate aesthetic choice, not a derived figure.
    ///
    /// Worst-case retention is 64 x the largest framed message (a ~64KB DHT block response) per
    /// (peer, lane) — and only for a lane that is mid-open, which is the only way into this path.
    /// Overflow drops the OLDEST, which is strictly better than the behaviour it replaces: before
    /// #718 every one of these messages opened its own stream, and past the 64-stream credit they
    /// failed with `STREAM_LIMIT_ERROR` — losing the message AND burning the credit.
    static final int PENDING_LANE_WRITES_MAX = 64;

    private final NodeId peerId;
    private final QuicChannel connection;
    private final QuicStreamChannel[] longLivedStreams;
    private final int consensusWatermarkLowBytes;
    private final int consensusWatermarkHighBytes;
    private volatile LaneOpener laneOpener = LaneOpener.noop();
    /// #718 — lanes with a lazy open IN FLIGHT, each mapped to the messages waiting on it. A present
    /// key IS the in-flight marker; [#completeLaneOpen] removing it IS the release. Guarded by this
    /// instance's monitor.
    ///
    /// The marker lives on the CONNECTION, not in a `(peerId, lane)` map on the transport, and the
    /// lifetime is the reason: an eviction and re-dial builds a FRESH `QuicPeerConnection`, so a
    /// marker leaked by a callback that never fires dies with the connection that owned it. Keyed by
    /// peer id instead, the same leak would suppress lane healing for that peer permanently.
    private final Map<StreamType, Deque<byte[]>> inFlightLaneOpens = new EnumMap<>(StreamType.class);

    private QuicPeerConnection(NodeId peerId,
                               QuicChannel connection,
                               int consensusWatermarkLowBytes,
                               int consensusWatermarkHighBytes) {
        this.peerId = peerId;
        this.connection = connection;
        this.longLivedStreams = new QuicStreamChannel[StreamType.values().length];
        this.consensusWatermarkLowBytes = consensusWatermarkLowBytes;
        this.consensusWatermarkHighBytes = consensusWatermarkHighBytes;
    }

    /// Create a new peer connection wrapping a QUIC channel, using the default CONSENSUS
    /// stream write-buffer watermarks ([QuicTransportTuning] defaults).
    public static QuicPeerConnection quicPeerConnection(NodeId peerId, QuicChannel connection) {
        return new QuicPeerConnection(peerId,
                                      connection,
                                      QuicTransportTuning.DEFAULT_WATERMARK_LOW_BYTES,
                                      QuicTransportTuning.DEFAULT_WATERMARK_HIGH_BYTES);
    }

    /// Create a new peer connection wrapping a QUIC channel with explicit CONSENSUS stream
    /// write-buffer watermarks. Used when the enclosing transport carries non-default tuning.
    public static QuicPeerConnection quicPeerConnection(NodeId peerId,
                                                        QuicChannel connection,
                                                        int consensusWatermarkLowBytes,
                                                        int consensusWatermarkHighBytes) {
        return new QuicPeerConnection(peerId, connection, consensusWatermarkLowBytes, consensusWatermarkHighBytes);
    }

    public NodeId peerId() {
        return peerId;
    }

    public QuicChannel connection() {
        return connection;
    }

    /// Install the lazy lane-opener. Called by the transport endpoint that built this connection
    /// (dialer/acceptor) right after construction, so a write that finds a missing data lane can
    /// (re)open it on the live channel instead of failing. A `null` opener resets to the no-op.
    @Contract
    public void laneOpener(LaneOpener opener) {
        this.laneOpener = opener == null
                          ? LaneOpener.noop()
                          : opener;
    }

    /// Lazily (re)open a missing long-lived `lane` stream on this live connection, invoking
    /// `onResult` with the registered stream (some) or empty on failure. Delegates to the installed
    /// [LaneOpener]; the default no-op opener reports empty (no live channel to open against).
    @Contract
    public void openLane(StreamType lane, Consumer<Option<QuicStreamChannel>> onResult) {
        laneOpener.open(lane, onResult);
    }

    /// #718 — admit `bytes` to the single in-flight lazy open for `lane`, starting one if none is
    /// running. Returns [LaneOpenAdmission.Started] to exactly one caller per open cycle; that caller
    /// drives [#openLane] and must pair this with [#completeLaneOpen].
    ///
    /// Without this gate the lazy re-open fired once PER OUTBOUND MESSAGE whose lane was missing, and
    /// each one created a stream: 1,570 attempted opens in one measured 72-second run, of which the
    /// 857 that failed were *exactly* the 857 `STREAM_LIMIT_ERROR`s — equal counts, in every arm. One
    /// transiently missing lane was being converted into exhaustion of the 64-stream credit for the
    /// whole connection.
    public synchronized LaneOpenAdmission beginLaneOpen(StreamType lane, byte[] bytes) {
        var pending = inFlightLaneOpens.get(lane);

        if (pending == null) {
            inFlightLaneOpens.put(lane, freshPendingQueue(bytes));

            return new LaneOpenAdmission.Started();
        }

        var wasFull = pending.size() >= PENDING_LANE_WRITES_MAX;

        if (wasFull) {
            pending.pollFirst();
        }

        pending.offerLast(bytes);

        return new LaneOpenAdmission.Coalesced(wasFull);
    }

    /// #718 — release the in-flight marker for `lane` and hand back every message that was waiting on
    /// it, oldest first, including the one offered by the caller that got
    /// [LaneOpenAdmission.Started]. Must be called on BOTH outcomes of the open — success and failure
    /// — or the marker leaks and the lane can never be healed again on this connection. Returns an
    /// empty list when no open was in flight, so a duplicate call is inert rather than harmful.
    public synchronized List<byte[]> completeLaneOpen(StreamType lane) {
        var pending = inFlightLaneOpens.remove(lane);

        return pending == null
               ? List.of()
               : List.copyOf(pending);
    }

    /// Visible for tests: lanes currently holding an in-flight lazy open.
    synchronized int inFlightLaneOpenCount() {
        return inFlightLaneOpens.size();
    }

    private static Deque<byte[]> freshPendingQueue(byte[] bytes) {
        var queue = new ArrayDeque<byte[]>();

        queue.offerLast(bytes);

        return queue;
    }

    /// Get a long-lived stream, if already opened.
    public Option<QuicStreamChannel> stream(StreamType type) {
        return option(longLivedStreams[type.streamIndex()]);
    }

    /// Register a long-lived stream for the given type.
    ///
    /// For [StreamType#CONSENSUS] the stream's write-buffer high-watermark is raised
    /// (default 256KB low / 1MB high — see [QuicTransportTuning]) so that bursts of
    /// consensus commands fit in the write buffer before `isWritable()` flips false. This
    /// is the first line of defence against the consensus-send backpressure defect; the
    /// async retry wrap in `QuicClusterNetwork.writeIfWritable` is the safety net for the
    /// pathological case. `WriteBufferWaterMark` is a Netty `ChannelConfig` facility and is
    /// supported by the incubator `QuicStreamChannel`'s config.
    /// #718 — a re-registration that REPLACES a live stream now CLOSES the one it displaced. The
    /// overwrite was silent before: nothing can look the old stream up again, so it stayed open,
    /// unreachable, holding one of the connection's 64 bidirectional stream credits for the lifetime
    /// of the connection. That is a credit leak on the exact resource the lazy-open storm exhausts.
    ///
    /// Honest bound on the close: writes already sitting in the superseded channel's Netty buffer at
    /// the instant of replacement are failed by `close()`. Those messages were ALREADY unreachable —
    /// the lane now resolves to `channel` — and recovering them is the retransmit's job, which is the
    /// convention this send path already documents for the lazy-open and offline-buffer outcomes it
    /// reports as optimistically `Sent`.
    @Contract
    public void registerStream(StreamType type, QuicStreamChannel channel) {
        if (type == StreamType.CONSENSUS) {
            channel.config()
                   .setWriteBufferWaterMark(new WriteBufferWaterMark(consensusWatermarkLowBytes,
                                                                     consensusWatermarkHighBytes));
        }

        var superseded = longLivedStreams[type.streamIndex()];

        longLivedStreams[type.streamIndex()] = channel;
        closeSuperseded(type, superseded, channel);
    }

    /// Closes a lane stream displaced by [#registerStream]. A re-register with the SAME channel (the
    /// idempotent case) and an already-dead channel are both no-ops — only a live, genuinely
    /// displaced stream is closed.
    @Contract
    private void closeSuperseded(StreamType type, QuicStreamChannel superseded, QuicStreamChannel replacement) {
        if (superseded == null || superseded == replacement || !superseded.isActive()) {
            return;
        }

        log.debug("Closing superseded {} stream for peer {} — re-registered on the same connection, "
                 + "returning its stream credit",
                  type,
                  peerId);
        superseded.close();
    }

    /// Check if the underlying QUIC connection is active.
    public boolean isActive() {
        return connection.isActive();
    }

    /// Close all streams and the underlying connection.
    public Promise<Unit> close() {
        return Promise.lift(cause -> QuicTransportError.ConnectionCloseFailed.FACTORY.apply(Causes.fromThrowable(cause)),
                            this::closeSync);
    }

    @Contract
    @SuppressWarnings("JBCT-UTIL-01")
    private void closeSync() throws Exception {
        closeLongLivedStreams();
        connection.close().sync();
    }

    private static final Logger log = LoggerFactory.getLogger(QuicPeerConnection.class);

    private void closeLongLivedStreams() {
        for (int i = 0; i < longLivedStreams.length; i++) {
            var stream = longLivedStreams[i];

            if (stream != null && stream.isActive()) {
                stream.close();
            }

            longLivedStreams[i] = null;
        }
    }
}
