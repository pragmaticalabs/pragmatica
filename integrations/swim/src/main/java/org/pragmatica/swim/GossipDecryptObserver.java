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
package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.lang.Contract;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Observer of the per-sender outcome of decrypting a SWIM datagram (#1200).
///
/// The transport drops a datagram it cannot decrypt and logs one WARN per datagram, which is the only trace a rotated cluster keeps of a node
/// that booted with a gossip key the cluster no longer accepts. This observer lets the node turn that into an operator event: [#unknownKey]
/// for a datagram under a key id the receiver does not hold, [#decrypted] for a datagram that opened. Both run on the transport's receive
/// thread and must not block.
public interface GossipDecryptObserver {
    GossipDecryptObserver NONE = new GossipDecryptObserver() {
        @Contract
        @Override
        public void unknownKey(InetSocketAddress sender, int keyId) {}

        @Contract
        @Override
        public void decrypted(InetSocketAddress sender) {}
    };

    /// A datagram from `sender` named key id `keyId`, which this node does not hold.
    @Contract
    void unknownKey(InetSocketAddress sender, int keyId);

    /// A datagram from `sender` decrypted.
    @Contract
    void decrypted(InetSocketAddress sender);

    /// Raises [OperatorWarningCode#GOSSIP_KEY_UNKNOWN_FROM_PEER] once per sender address and its recovery
    /// [OperatorWarningCode#GOSSIP_KEY_PEER_DECRYPTED] when a datagram from that address next decrypts. `sink` is read at raise time, so a
    /// sink bound after the transport started is honoured.
    static GossipDecryptObserver reporting(Supplier<OperatorWarningSink> sink) {
        return new Reporting(sink, System::currentTimeMillis);
    }

    /// The same on an injected wall clock (milliseconds), so a test can cross the report window without sleeping.
    static GossipDecryptObserver reporting(Supplier<OperatorWarningSink> sink, LongSupplier clockMillis) {
        return new Reporting(sink, clockMillis);
    }

    /// The source address of a UDP datagram is spoofable and an unknown key id needs no key, so anyone who can send UDP to the SWIM port can make
    /// this observer see any number of distinct senders. It therefore bounds both what it REMEMBERS and what it REPORTS:
    /// - at most [#MAX_NEW_SENDERS_PER_WINDOW] new senders raise an event in each [#WINDOW_MILLIS] window; the rest are counted, in a set
    ///   capped at [#MAX_SUPPRESSED_TRACKED], and summarised by ONE [OperatorWarningCode#GOSSIP_KEY_UNKNOWN_SENDERS_SUPPRESSED] event when the window
    ///   ends (raised by the next datagram after it, so a flood that stops leaves its summary pending until the next one);
    /// - the set of reported senders holds at most [#MAX_REPORTED] and drops its eldest beyond that. A dropped sender is reported again if it keeps
    ///   sending (inside the window budget), and its recovery is simply not raised.
    /// That is forward recovery: the log WARN per datagram is unchanged and stands as the record of everything suppressed.
    ///
    /// The subject is the sender's `ip:port`. A node sends its SWIM datagrams from its one bound UDP socket, so the port is stable for the life of
    /// the process (and, being derived from the node's configured port, across restarts), and two nodes on one host (a dev machine, Ember) get
    /// their own warning and their own recovery.
    final class Reporting implements GossipDecryptObserver {
        static final int MAX_REPORTED = 256;
        static final int MAX_NEW_SENDERS_PER_WINDOW = 16;
        static final int MAX_SUPPRESSED_TRACKED = 1024;
        static final long WINDOW_MILLIS = 60_000L;
        static final String SUPPRESSED_SUBJECT = "unknown-gossip-key-senders";
        private static final Logger LOG = LoggerFactory.getLogger(Reporting.class);

        private final Supplier<OperatorWarningSink> sink;
        private final LongSupplier clockMillis;

        private final Map<String, Boolean> reported = Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_REPORTED;
            }
        });

        private final Map<String, Boolean> suppressed = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_SUPPRESSED_TRACKED;
            }
        };

        private long windowStartMillis;
        private int newSendersInWindow;
        private volatile long windowEndMillis;

        private Reporting(Supplier<OperatorWarningSink> sink, LongSupplier clockMillis) {
            this.sink = sink;
            this.clockMillis = clockMillis;
            this.windowStartMillis = clockMillis.getAsLong();
            this.windowEndMillis = windowStartMillis + WINDOW_MILLIS;
        }

        /// Entries currently remembered as reported, for the bound test.
        int reportedCount() {
            return reported.size();
        }

        @Contract
        @Override
        public void unknownKey(InetSocketAddress sender, int keyId) {
            var subject = subject(sender);

            closeWindowIfEnded();
            if (!reported.containsKey(subject)) {
                admit(subject, keyId);
            }
        }

        @Contract
        @Override
        public void decrypted(InetSocketAddress sender) {
            closeWindowIfEnded();
            if (!reported.isEmpty() && reported.remove(subject(sender)) != null) {
                OperatorWarnings.raise(LOG,
                                       sink.get(),
                                       OperatorWarningCode.GOSSIP_KEY_PEER_DECRYPTED,
                                       subject(sender),
                                       "Gossip from {} decrypts again: its gossip key now matches the cluster's",
                                       subject(sender));
            }
        }

        private synchronized void admit(String subject, int keyId) {
            if (reported.containsKey(subject)) {
                return;
            }

            if (newSendersInWindow >= MAX_NEW_SENDERS_PER_WINDOW) {
                suppressed.put(subject, Boolean.TRUE);

                return;
            }

            newSendersInWindow++;
            reported.put(subject, Boolean.TRUE);
            OperatorWarnings.raise(LOG,
                                   sink.get(),
                                   OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER,
                                   subject,
                                   "Gossip from {} was dropped: it is encrypted under key id {}, which this node does not hold. The usual cause is a node that booted with a gossip key the cluster has rotated away from; it normally catches up within seconds, when it joins consensus and replays the rotation record, and gossip-key-peer-decrypted closes this event when its gossip decrypts. If it is not closed, check that the sender's peer list names reachable cores and that its cluster_secret matches the cluster's. The address is a UDP source address, which can be spoofed",
                                   subject,
                                   keyId);
        }

        /// Opens the next window when the current one has ended, raising the one summary event for the senders it suppressed.
        private void closeWindowIfEnded() {
            if (clockMillis.getAsLong() < windowEndMillis) {
                return;
            }

            rollWindow();
        }

        private synchronized void rollWindow() {
            var now = clockMillis.getAsLong();

            if (now < windowStartMillis + WINDOW_MILLIS) {
                return;
            }

            var count = suppressed.size();

            windowStartMillis = now;
            windowEndMillis = now + WINDOW_MILLIS;
            newSendersInWindow = 0;
            suppressed.clear();
            if (count > 0) {
                OperatorWarnings.raise(LOG,
                                       sink.get(),
                                       OperatorWarningCode.GOSSIP_KEY_UNKNOWN_SENDERS_SUPPRESSED,
                                       SUPPRESSED_SUBJECT,
                                       "{}{} more sender address(es) sent gossip under a key id this node does not hold in the last {} s and were not reported one by one (at most {} new senders are reported per window); the per-datagram WARN in the node log names each. The addresses are UDP source addresses, which can be spoofed",
                                       count >= MAX_SUPPRESSED_TRACKED
                                       ? "At least "
                                       : "",
                                       count,
                                       WINDOW_MILLIS / 1000,
                                       MAX_NEW_SENDERS_PER_WINDOW);
            }
        }

        private static String subject(InetSocketAddress sender) {
            return (sender.getAddress() == null
                    ? sender.getHostString()
                    : sender.getAddress()
                            .getHostAddress())
                 + ":" + sender.getPort();
        }
    }
}
