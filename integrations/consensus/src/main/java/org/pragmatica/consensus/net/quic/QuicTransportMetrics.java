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

    // --- Snapshot ---

    /// Returns a snapshot of all QUIC transport metrics as a map
    /// suitable for JSON serialization and Prometheus exposition.
    public Map<String, Number> snapshot() {
        return Map.of("quic_active_connections", activeConnections.get(),
                       "quic_handshake_total", handshakeTotal.sum(),
                       "quic_handshake_failures_total", handshakeFailures.sum(),
                       "quic_messages_sent_total", messagesSent.sum(),
                       "quic_messages_received_total", messagesReceived.sum(),
                       "quic_write_failures_total", writeFailures.sum(),
                       "quic_backpressure_drops_total", backpressureDrops.sum());
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
}
