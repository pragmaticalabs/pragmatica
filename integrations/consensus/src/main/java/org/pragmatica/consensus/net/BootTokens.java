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
package org.pragmatica.consensus.net;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;


/// Per-process BOOT TOKEN registry — the single store every admission path consults (owner ruling,
/// session 28: terminal removal is the identity model). SWIM, the QUIC Hello handshake, reconnect/
/// re-admission and inbound message dispatch all share ONE instance per node, so no two layers can
/// hold divergent views of which process owns a NodeId.
///
/// Tokens are compared by EQUALITY only. `0` carries no process identity and is always admitted.
/// The first non-zero token seen for a NodeId is recorded; an equal token is the same process. A
/// different token retires the NodeId for the life of this process: the known process is treated as
/// dead and every later piece of evidence for that NodeId — from either process — is refused.
public interface BootTokens {
    /// Outcome of presenting a token for a peer.
    enum Admission {
        /// Unknown, first-seen or equal token — the same process.
        ADMITTED,
        /// A different token for a known NodeId: the NodeId was retired by THIS call.
        CONFLICT,
        /// The NodeId was already retired by an earlier conflict.
        RETIRED;
        public boolean admitted() {
            return this == ADMITTED;
        }
    }

    /// This process's own token (`0` when the node runs without one).
    long self();
    /// Present `token` as evidence for `peer`. Refusals (`CONFLICT`, `RETIRED`) are counted.
    Admission admit(NodeId peer, long token);
    boolean isRetired(NodeId peer);
    /// The token recorded for `peer`, `0` when none.
    long tokenOf(NodeId peer);
    /// Count of refused admissions — observability for the terminal-removal rule.
    long refusals();
    /// Register a listener invoked once per NodeId, on the admission that retires it — whichever
    /// layer (SWIM or the QUIC handshake) presented the conflicting token. Lets SWIM mark the known
    /// process dead even when the transport saw the new process first.
    Unit onRetired(Consumer<NodeId> listener);

    /// A peer told THIS process its own NodeId belongs to a retired (or different) process: this process
    /// can never be admitted. Notifies the self-refusal listeners exactly once, with the peer's reason.
    Unit selfRefused(String reason);

    /// Register a listener for [#selfRefused] — the node logs ERROR and exits (terminal removal).
    Unit onSelfRefused(Consumer<String> listener);

    static BootTokens bootTokens(long self) {
        record registry(long self,
                        Map<NodeId, Long> tokens,
                        Set<NodeId> retired,
                        AtomicLong refusalCount,
                        List<Consumer<NodeId>> retirementListeners,
                        List<Consumer<String>> selfRefusalListeners,
                        AtomicBoolean refusedSelf) implements BootTokens {
            @Override
            public Admission admit(NodeId peer, long token) {
                if (retired.contains(peer)) {
                    return refused(Admission.RETIRED);
                }

                if (token == 0L) {
                    return Admission.ADMITTED;
                }

                var known = tokens.putIfAbsent(peer, token);

                return known == null || known == token
                       ? Admission.ADMITTED
                       : conflict(peer);
            }

            private Admission conflict(NodeId peer) {
                return retired.add(peer)
                       ? announceRetirement(peer)
                       : refused(Admission.RETIRED);
            }

            private Admission announceRetirement(NodeId peer) {
                retirementListeners.forEach(listener -> listener.accept(peer));

                return refused(Admission.CONFLICT);
            }

            private Admission refused(Admission admission) {
                refusalCount.incrementAndGet();

                return admission;
            }

            @Override
            public boolean isRetired(NodeId peer) {
                return retired.contains(peer);
            }

            @Override
            public long tokenOf(NodeId peer) {
                return tokens.getOrDefault(peer, 0L);
            }

            @Override
            public long refusals() {
                return refusalCount.get();
            }

            @Override
            public Unit onRetired(Consumer<NodeId> listener) {
                retirementListeners.add(listener);

                return Unit.unit();
            }

            @Override
            public Unit selfRefused(String reason) {
                if (refusedSelf.compareAndSet(false, true)) {
                    selfRefusalListeners.forEach(listener -> listener.accept(reason));
                }

                return Unit.unit();
            }

            @Override
            public Unit onSelfRefused(Consumer<String> listener) {
                selfRefusalListeners.add(listener);

                return Unit.unit();
            }
        }

        return new registry(self,
                            new ConcurrentHashMap<>(),
                            ConcurrentHashMap.newKeySet(),
                            new AtomicLong(),
                            new CopyOnWriteArrayList<>(),
                            new CopyOnWriteArrayList<>(),
                            new AtomicBoolean());
    }
}
