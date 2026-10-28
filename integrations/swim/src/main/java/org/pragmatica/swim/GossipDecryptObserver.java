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
        return new Reporting(sink);
    }

    /// Bounded: the sender address of a UDP datagram is spoofable, so the set of reported senders holds at most [#MAX_REPORTED] and drops its
    /// eldest beyond that. A dropped sender is reported again if it keeps sending, and its recovery is simply not raised (its warning stays open
    /// until the event layer's own expiry). That is forward recovery: the log WARN per datagram is unchanged and stands as the record.
    final class Reporting implements GossipDecryptObserver {
        static final int MAX_REPORTED = 256;
        private static final Logger LOG = LoggerFactory.getLogger(Reporting.class);

        private final Supplier<OperatorWarningSink> sink;

        private final Map<String, Boolean> reported = Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_REPORTED;
            }
        });

        private Reporting(Supplier<OperatorWarningSink> sink) {
            this.sink = sink;
        }

        @Contract
        @Override
        public void unknownKey(InetSocketAddress sender, int keyId) {
            var subject = subject(sender);

            if (reported.putIfAbsent(subject, Boolean.TRUE) == null) {
                OperatorWarnings.raise(LOG,
                                       sink.get(),
                                       OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER,
                                       subject,
                                       "Gossip from {} is encrypted under key id {}, which this node does not hold: the sender probably booted with a gossip key the cluster has rotated away from, and it cannot join. Give it the cluster's current gossip key (AETHER_GOSSIP_KEY or AETHER_GOSSIP_KEY_FILE) or let the cluster leader provision it",
                                       subject,
                                       keyId);
            }
        }

        @Contract
        @Override
        public void decrypted(InetSocketAddress sender) {
            if (!reported.isEmpty() && reported.remove(subject(sender)) != null) {
                OperatorWarnings.raise(LOG,
                                       sink.get(),
                                       OperatorWarningCode.GOSSIP_KEY_PEER_DECRYPTED,
                                       subject(sender),
                                       "Gossip from {} decrypts again: its gossip key now matches the cluster's",
                                       subject(sender));
            }
        }

        private static String subject(InetSocketAddress sender) {
            return sender.getAddress() == null
                   ? sender.getHostString()
                   : sender.getAddress()
                           .getHostAddress();
        }
    }
}
