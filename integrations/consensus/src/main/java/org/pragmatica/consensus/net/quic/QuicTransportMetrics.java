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
    private final AtomicInteger backpressureQueueDepth = new AtomicInteger(0);

    private QuicTransportMetrics() {}

    public static QuicTransportMetrics quicTransportMetrics() {
        return new QuicTransportMetrics();
    }

    // --- Recording methods ---

    public void onConnectionEstablished() {
        activeConnections.incrementAndGet();
        handshakeTotal.increment();
    }

    public void onConnectionClosed() {
        activeConnections.decrementAndGet();
    }

    public void onHandshakeFailure() {
        handshakeFailures.increment();
    }

    public void onMessageSent() {
        messagesSent.increment();
    }

    public void onMessageReceived() {
        messagesReceived.increment();
    }

    public void onWriteFailure() {
        writeFailures.increment();
    }

    public void onBackpressureDrop() {
        backpressureDrops.increment();
    }

    public void onBackpressureQueued() {
        backpressureQueued.increment();
        backpressureQueueDepth.incrementAndGet();
    }

    public void onBackpressureDrained() {
        backpressureQueueDepth.decrementAndGet();
    }

    public void onBackpressureQueueCleared(int size) {
        backpressureQueueDepth.addAndGet(-size);
    }

    // --- Snapshot ---

    /// Returns a snapshot of all QUIC transport metrics as a map
    /// suitable for JSON serialization and Prometheus exposition.
    @SuppressWarnings("JBCT-PAT-01") // Metrics snapshot assembly
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
        metrics.put("quic_backpressure_queue_depth", backpressureQueueDepth.get());
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

    public int backpressureQueueDepth() {
        return backpressureQueueDepth.get();
    }
}
