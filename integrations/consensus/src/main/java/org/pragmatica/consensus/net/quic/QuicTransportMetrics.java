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

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.pragmatica.lang.Contract;


/// Thread-safe QUIC transport metrics using atomic counters.
///
/// Tracks connection lifecycle, handshakes, and message throughput
/// for the QUIC cluster network. Counters use [LongAdder] for
/// high-throughput, low-contention accumulation.
public final class QuicTransportMetrics {
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final LongAdder handshakeTotal = new LongAdder();
    private final LongAdder handshakeFailures = new LongAdder();
    private final LongAdder messagesSent = new LongAdder();
    private final LongAdder messagesReceived = new LongAdder();
    private final LongAdder writeFailures = new LongAdder();
    private final LongAdder backpressureDrops = new LongAdder();
    private final LongAdder backpressureQueued = new LongAdder();
    private final LongAdder backpressureRetries = new LongAdder();
    private final AtomicInteger backpressureQueueDepth = new AtomicInteger(0);
    /// Count of lazy lane (re)opens triggered by a write that found a CONNECTED peer with a missing
    /// data lane (the QUIC reconnect stream-zombie). One increment per write that took the lazy-open
    /// PRIMARY heal path, regardless of whether the open later succeeds.
    private final LongAdder streamZombieLazyOpens = new LongAdder();
    /// Count of stream-zombie BACKSTOP evictions: a write found a CONNECTED peer with no usable
    /// stream AND the lazy open could not heal it, so the connection was evicted for a clean re-dial.
    private final LongAdder streamZombieEvictions = new LongAdder();
    /// #487: count of sends DROPPED to a peer with no PeerState (dead or never-connected). Counts EVERY
    /// drop, not just the rate-limited WARNs, so ops see true drop volume (the invisibility of this class
    /// hid the #467/#457 self-send drops for months).
    private final LongAdder dropToUnknownPeer = new LongAdder();
    /// #726: PAYLOAD bytes handed to the channel on send — the serialized frame passed to
    /// `writeAndFlush`, before QUIC adds framing, TLS encryption overhead, or retransmits. Not a
    /// wire-byte or bandwidth figure; do not treat it as one.
    private final LongAdder bytesSent = new LongAdder();
    /// #726: PAYLOAD bytes decoded from the buffer on receive — the frame read out of the QUIC
    /// stream lane after the pipeline has already stripped QUIC/TLS overhead. Symmetric with
    /// [#bytesSent] on the same honesty boundary.
    private final LongAdder bytesReceived = new LongAdder();

    private QuicTransportMetrics() {}

    public static QuicTransportMetrics quicTransportMetrics() {
        return new QuicTransportMetrics();
    }

    // --- Recording methods ---
    @Contract
    public void onConnectionEstablished() {
        activeConnections.incrementAndGet();
        handshakeTotal.increment();
    }

    @Contract
    public void onConnectionClosed() {
        activeConnections.decrementAndGet();
    }

    @Contract
    public void onHandshakeFailure() {
        handshakeFailures.increment();
    }

    @Contract
    public void onMessageSent() {
        messagesSent.increment();
    }

    @Contract
    public void onMessageReceived() {
        messagesReceived.increment();
    }

    @Contract
    public void onWriteFailure() {
        writeFailures.increment();
    }

    @Contract
    public void onBackpressureDrop() {
        backpressureDrops.increment();
    }

    /// Records that a CONSENSUS send hit the write high-watermark and was handed to the
    /// async retry path (instead of being silently dropped). One increment per backpressured
    /// CONSENSUS send that enters retry — not per retry attempt.
    @Contract
    public void onBackpressureRetry() {
        backpressureRetries.increment();
    }

    @Contract
    public void onBackpressureQueued() {
        backpressureQueued.increment();
        backpressureQueueDepth.incrementAndGet();
    }

    @Contract
    public void onBackpressureDrained() {
        backpressureQueueDepth.decrementAndGet();
    }

    @Contract
    public void onBackpressureQueueCleared(int size) {
        backpressureQueueDepth.addAndGet(-size);
    }

    /// Records that a write found a CONNECTED peer with a missing data lane and took the lazy-open
    /// heal path (the stream-zombie PRIMARY fix).
    @Contract
    public void onStreamZombieLazyOpen() {
        streamZombieLazyOpens.increment();
    }

    /// Records a stream-zombie BACKSTOP eviction: a CONNECTED peer with no usable stream that the
    /// lazy open could not heal, evicted for a clean re-dial.
    @Contract
    public void onStreamZombieEviction() {
        streamZombieEvictions.increment();
    }

    /// #487: records a send DROPPED to a peer with no PeerState. Every drop is counted, whether or not
    /// the accompanying WARN was rate-limited.
    @Contract
    public void onDropToUnknownPeer() {
        dropToUnknownPeer.increment();
    }

    /// #726: records PAYLOAD bytes handed to the channel on send, at the lane boundary — before
    /// QUIC framing, TLS overhead, or retransmits. Never call the resulting counter "bandwidth".
    @Contract
    public void onBytesSent(long byteCount) {
        bytesSent.add(byteCount);
    }

    /// #726: records PAYLOAD bytes decoded from the buffer on receive, at the lane boundary —
    /// after the pipeline has already stripped QUIC/TLS overhead. Never call the resulting
    /// counter "bandwidth".
    @Contract
    public void onBytesReceived(long byteCount) {
        bytesReceived.add(byteCount);
    }

    // --- Snapshot ---
    /// Returns a snapshot of all QUIC transport metrics as a map
    /// suitable for JSON serialization and Prometheus exposition.
    @SuppressWarnings("JBCT-PAT-01")  // Metrics snapshot assembly
    public Map<String, Number> snapshot() {
        var metrics = new java.util.HashMap<String, Number>();

        metrics.put("quic_active_connections", activeConnections.get());
        metrics.put("quic_handshake_total", handshakeTotal.sum());
        metrics.put("quic_handshake_failures_total", handshakeFailures.sum());
        metrics.put("quic_messages_sent_total", messagesSent.sum());
        metrics.put("quic_messages_received_total", messagesReceived.sum());
        metrics.put("quic_write_failures_total", writeFailures.sum());
        metrics.put("quic_backpressure_drops_total", backpressureDrops.sum());
        metrics.put("quic_backpressure_queued_total", backpressureQueued.sum());
        metrics.put("quic_backpressure_retries_total", backpressureRetries.sum());
        metrics.put("quic_backpressure_queue_depth", backpressureQueueDepth.get());
        metrics.put("quic_stream_zombie_lazy_opens_total", streamZombieLazyOpens.sum());
        metrics.put("quic_stream_zombie_evictions_total", streamZombieEvictions.sum());
        metrics.put("quic_drop_to_unknown_peer_total", dropToUnknownPeer.sum());
        // #726: payload bytes at the lane boundary (no QUIC framing/TLS overhead/retransmits) —
        // not a wire-byte or bandwidth figure.
        metrics.put("quic_bytes_sent_total", bytesSent.sum());
        metrics.put("quic_bytes_received_total", bytesReceived.sum());

        return Map.copyOf(metrics);
    }

    public int activeConnectionCount() {
        return activeConnections.get();
    }

    public long handshakeTotalCount() {
        return handshakeTotal.sum();
    }

    public long handshakeFailureCount() {
        return handshakeFailures.sum();
    }

    public long messagesSentCount() {
        return messagesSent.sum();
    }

    public long messagesReceivedCount() {
        return messagesReceived.sum();
    }

    public long writeFailureCount() {
        return writeFailures.sum();
    }

    public long backpressureDropCount() {
        return backpressureDrops.sum();
    }

    public long backpressureQueuedCount() {
        return backpressureQueued.sum();
    }

    public long backpressureRetryCount() {
        return backpressureRetries.sum();
    }

    public int backpressureQueueDepth() {
        return backpressureQueueDepth.get();
    }

    public long streamZombieLazyOpenCount() {
        return streamZombieLazyOpens.sum();
    }

    public long streamZombieEvictionCount() {
        return streamZombieEvictions.sum();
    }

    public long dropToUnknownPeerCount() {
        return dropToUnknownPeer.sum();
    }

    /// #726: PAYLOAD bytes handed to the channel on send, cumulative. See [#onBytesSent] for the
    /// exact boundary this counts at.
    public long bytesSentCount() {
        return bytesSent.sum();
    }

    /// #726: PAYLOAD bytes decoded from the buffer on receive, cumulative. See [#onBytesReceived]
    /// for the exact boundary this counts at.
    public long bytesReceivedCount() {
        return bytesReceived.sum();
    }
}
