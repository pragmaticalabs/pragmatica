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

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.pragmatica.swim.SwimMember.MemberState;


/// Bounded buffer for membership updates piggybacked on protocol messages.
/// Thread-safe: every operation runs under the buffer's monitor, so a peek — which ages
/// each entry and re-queues the survivors — is atomic to every other thread. A concurrent
/// `size`/`faultyCount`/`expireFaultyUpdates` never observes the buffer mid-peek (#1151:
/// the unlocked drain-and-requeue let `expireFaultyUpdates` miss an in-flight FAULTY
/// verdict, which was then gossiped into the healed cluster). Contention is the probe
/// tick against the transport thread at protocol cadence — the monitor is cheap there.
///
/// [Fix #10] Uses peek-and-age instead of drain-on-read. Updates are returned
/// multiple times (up to dissemination limit) to ensure they reach all members.
/// Each update tracks how many times it was piggybacked — after enough
/// disseminations (lambda * log(N)), it is evicted.
public final class PiggybackBuffer {
    private static final Logger LOG = LoggerFactory.getLogger(PiggybackBuffer.class);

    private final Deque<TrackedUpdate> buffer = new ArrayDeque<>();
    private final int maxSize;
    private final int maxDisseminations;
    private final Set<NodeId> oversizeWarned = ConcurrentHashMap.newKeySet();

    private record TrackedUpdate(MembershipUpdate update, int disseminationCount) {
        TrackedUpdate withDissemination() {
            return new TrackedUpdate(update, disseminationCount + 1);
        }
    }

    private PiggybackBuffer(int maxSize) {
        this.maxSize = maxSize;
        // Disseminate each update at least 3 * maxSize times (approximates lambda * log(N))
        this.maxDisseminations = 3 * Math.max(maxSize, 4);
    }

    /// Factory creating a buffer bounded to the given maximum size.
    public static PiggybackBuffer piggybackBuffer(int maxSize) {
        return new PiggybackBuffer(maxSize);
    }

    /// Add an update to the buffer. If the buffer is full, the oldest entry is evicted.
    @Contract
    public synchronized void addUpdate(MembershipUpdate update) {
        buffer.addLast(new TrackedUpdate(update, 0));
        trimToSize();
    }

    /// Peek up to {@code max} updates WITHOUT removing them.
    /// Each peeked update increments its dissemination counter.
    /// Updates that have been disseminated enough times are evicted.
    public synchronized List<MembershipUpdate> peekUpdates(int max) {
        return peekUpdates(max, Integer.MAX_VALUE);
    }

    /// As [#peekUpdates(int)], but stops once the estimated encoded size of the peeked updates would
    /// exceed {@code budgetBytes}. The update that did not fit stays at the FRONT of the buffer, so the
    /// next round sends it first: a budget delays an update, it never starves it. A first update that
    /// ALONE exceeds the budget is sent WITHOUT its labels (WARN once per member) rather than oversized or
    /// skipped: an oversized datagram is dropped by every receiver and, sitting at the front, would block
    /// the queue behind it; the state, incarnation and address it carries still propagate.
    public synchronized List<MembershipUpdate> peekUpdates(int max, int budgetBytes) {
        var result = new ArrayList<MembershipUpdate>(Math.min(max, buffer.size()));
        var toRequeue = new ArrayList<TrackedUpdate>();
        var usedBytes = 0;

        for (int i = 0; i < max; i++) {
            var item = buffer.pollFirst();

            if (item == null) {
                break;
            }

            var candidate = item.update();
            var itemBytes = estimatedBytes(candidate);

            if (!result.isEmpty() && usedBytes + itemBytes > budgetBytes) {
                buffer.addFirst(item);
                break;
            }

            if (result.isEmpty() && itemBytes > budgetBytes) {
                candidate = withoutLabels(candidate, itemBytes, budgetBytes);
                itemBytes = estimatedBytes(candidate);
            }

            usedBytes += itemBytes;
            result.add(candidate);
            var incremented = item.withDissemination();

            if (incremented.disseminationCount() < maxDisseminations) {
                toRequeue.add(incremented);
            }
            // else: evicted — disseminated enough times
        }
        // Re-add non-evicted updates to the back
        toRequeue.forEach(buffer::addLast);

        return Collections.unmodifiableList(result);
    }

    private MembershipUpdate withoutLabels(MembershipUpdate update, int itemBytes, int budgetBytes) {
        if (update.labels().isEmpty()) {
            return update;
        }

        if (oversizeWarned.add(update.nodeId())) {
            LOG.warn("SWIM update for {} is ~{} B, over the {} B piggyback budget on its own — gossiping it without its"
                     + " {} label(s); the full labels still travel on ANNOUNCE and the QUIC Hello",
                     update.nodeId().id(),
                     itemBytes,
                     budgetBytes,
                     update.labels().size());
        }

        return MembershipUpdate.membershipUpdate(update.nodeId(),
                                                 update.state(),
                                                 update.incarnation(),
                                                 update.address(),
                                                 update.bootToken());
    }

    /// Largest SWIM datagram this node sends, on the wire (after encryption). 1400 B is the choice: a
    /// 1500 B Ethernet MTU less 28 B of IPv4+UDP headers leaves 1472 B, and 1400 keeps ~70 B of slack for
    /// tunnel encapsulation (VXLAN/WireGuard/overlay networks) so a datagram is never IP-fragmented.
    /// It is also well under the 2048 B receive buffer Netty allocates by default per datagram — a
    /// datagram larger than that buffer is truncated and then fails to decrypt, silently losing gossip.
    public static final int MAX_DATAGRAM_BYTES = 1400;
    /// AES-GCM framing added to every datagram: 4 B key id + 12 B nonce + 16 B tag.
    static final int ENCRYPTION_OVERHEAD_BYTES = 32;

    /// Fixed part of the message envelope (type tag, sender-id framing, sequence, list header), rounded up.
    /// The sender's id itself is added per node — see [#piggybackBudgetFor].
    static final int ENVELOPE_FIXED_BYTES = 64;

    /// Budget for the piggybacked updates of one message sent BY `self`: the datagram ceiling less AES-GCM
    /// framing, less the envelope, whose only unbounded part is the sender's own id — so it is computed from
    /// that id's actual encoded length, not assumed (a constant reserve broke for ids over ~110 characters).
    /// Floored at zero: an id so long that nothing else fits still sends its updates one at a time, labels
    /// stripped, and cannot be made smaller by this code.
    public static int piggybackBudgetFor(NodeId self) {
        return Math.max(0, MAX_DATAGRAM_BYTES - ENCRYPTION_OVERHEAD_BYTES - ENVELOPE_FIXED_BYTES - utf8Length(self.id()));
    }

    /// Conservative (never under-) estimate of an update's encoded size: per-field framing is rounded up
    /// to 10 B, strings count their UTF-8 bytes, and every label costs its key and value plus framing.
    public static int estimatedBytes(MembershipUpdate update) {
        var bytes = 48 + utf8Length(update.nodeId().id()) + utf8Length(update.address().getHostString());

        for (var label : update.labels().entrySet()) {
            bytes += 10 + utf8Length(label.getKey()) + utf8Length(label.getValue());
        }

        return bytes;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /// Current number of buffered updates.
    public synchronized int size() {
        return buffer.size();
    }

    /// Current number of buffered FAULTY updates (P2 — used to assert isolation-era expiry).
    public synchronized int faultyCount() {
        return (int) buffer.stream()
                           .filter(tracked -> tracked.update()
                                                     .state() == MemberState.FAULTY)
                           .count();
    }

    /// Expire (drop) every buffered FAULTY dissemination (P2 isolation-era verdict expiry).
    /// Called when a node that observed its OWN isolation rejoins the cluster: the FAULTY
    /// verdicts it accumulated while cut off are epistemically "I was unreachable", NOT
    /// "those peers died", so they must not be gossiped into the healed cluster (the S06
    /// partition-heal collapse: a rejoining minority injected isolation-era FAULTY verdicts
    /// that terminalized live majority nodes). Non-FAULTY updates (ALIVE/SUSPECT) are
    /// retained — only the death verdicts are dropped. Returns the number of entries dropped.
    public synchronized int expireFaultyUpdates() {
        var before = buffer.size();

        buffer.removeIf(tracked -> tracked.update()
                                          .state() == MemberState.FAULTY);

        return before - buffer.size();
    }

    /// Forget out-of-scope gossip without disseminating a synthetic death.
    public synchronized org.pragmatica.lang.Unit retainMembers(Predicate<NodeId> eligibility) {
        buffer.removeIf(tracked -> !eligibility.test(tracked.update().nodeId()));

        return org.pragmatica.lang.Unit.unit();
    }

    private void trimToSize() {
        while (buffer.size() > maxSize * 2) {
            buffer.pollFirst();
        }
    }
}
