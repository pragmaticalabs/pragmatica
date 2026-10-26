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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
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
///
/// **Durability (#2429).** Without an identity ledger this registry is process memory: a restarted node knows nothing
/// of what it retired. With one ([#attachLedger], [#preload]) the same two stores are loaded from the node's disk
/// before admission opens, and a retirement is written to it BEFORE the registry answers the refusal or announces the
/// retirement. Only retirements are kept: the boot token a peer presented is not, because a restarted node carries a new
/// one and a registry that remembered the old would refuse every restarted peer. The comparison rule above is unchanged.
public interface BootTokens {
    /// The cause recorded in the ledger when a different process presented a token for a bound NodeId.
    String TOKEN_CONFLICT = "TOKEN_CONFLICT";

    /// Outcome of presenting a token for a peer.
    enum Admission {
        /// Unknown, first-seen or equal token — the same process.
        ADMITTED,
        /// A different token for a known NodeId: the NodeId was retired by THIS call.
        CONFLICT,
        /// The NodeId was already retired by an earlier conflict.
        RETIRED,
        /// Neither admitted nor refused (#2429): the registry cannot yet answer, because it is not loaded from the identity
        /// ledger, the node is WIPED and admits no new peer until a merge completed, or (for the synchronous [#admit] only)
        /// the answer waits for a retirement to become durable. The evidence is presented again later; it is not counted as
        /// a refusal.
        UNAVAILABLE;
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
    /// [#admit] for a caller that can wait: a conflicting token is answered, and its retirement announced, only once the
    /// retirement is durable in the identity ledger (write-ahead, #2429). Never blocks the caller; completes at once for
    /// every other presentation.
    Promise<Admission> admitDurably(NodeId peer, long token);
    /// Completes once the retirement of `peer` is durable (or its write has failed: the retirement stands and is announced
    /// all the same, see the registry's conflict handling). Completes at once for a NodeId whose retirement needs no write
    /// of its own: one loaded from the ledger at boot, one merged from a peer, or any NodeId on a node without a ledger.
    /// A caller that answers a refusal to the process itself waits on this, so the answer never precedes the disk (#2429).
    Promise<Unit> retirementDurable(NodeId peer);
    /// Attach this node's identity ledger. From here on the registry answers [Admission#UNAVAILABLE] until
    /// [#preload] has loaded the ledger's facts: it fails closed, never open.
    Unit attachLedger(IdentityLedger ledger);
    /// Load the facts the ledger held at boot and open admission. `wiped` means the ledger was lost (not created
    /// now): the registry then admits no new peer until [#merged] reports a completed merge with a peer's facts.
    Unit preload(IdentityFacts facts, boolean wiped);
    /// A peer's facts were merged into this node's: apply them, make them durable, and end a WIPED state.
    Promise<Unit> merged(IdentityFacts peerFacts);
    /// Whether the identity ledger is attached and its last write left it current. False without a ledger.
    boolean ledgerDurable();
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
                        AtomicBoolean refusedSelf,
                        AtomicReference<Option<IdentityLedger>> ledger,
                        AtomicBoolean ready,
                        AtomicBoolean wiped,
                        Map<NodeId, Promise<Unit>> writes) implements BootTokens {
            @Override
            public Admission admit(NodeId peer, long token) {
                var screened = screen(peer, token);

                return screened == Admission.CONFLICT
                       ? answeredNow(conflict(peer))
                       : screened;
            }

            @Override
            public Promise<Admission> admitDurably(NodeId peer, long token) {
                var screened = screen(peer, token);

                return screened == Admission.CONFLICT
                       ? conflict(peer)
                       : Promise.success(screened);
            }

            /// A synchronous caller takes the verdict only if it is already known; a retirement still being written is
            /// UNAVAILABLE to it, and is announced when it is durable.
            private Admission answeredNow(Promise<Admission> verdict) {
                var answer = new AtomicReference<>(Admission.UNAVAILABLE);

                return verdict.onSuccess(answer::set)
                              .isResolved()
                       ? answer.get()
                       : Admission.UNAVAILABLE;
            }

            /// Every outcome that needs no write. [Admission#CONFLICT] here only means "a different process, not yet
            /// retired": the caller retires it and answers with the real verdict. Nothing is allocated on the common path.
            private Admission screen(NodeId peer, long token) {
                if (!ready.get()) {
                    return Admission.UNAVAILABLE;
                }

                if (retired.contains(peer)) {
                    return refused(Admission.RETIRED);
                }

                if (token == 0L) {
                    return Admission.ADMITTED;
                }

                if (wiped.get() && !tokens.containsKey(peer)) {
                    return Admission.UNAVAILABLE;
                }

                var known = tokens.putIfAbsent(peer, token);

                return known == null || known == token
                       ? Admission.ADMITTED
                       : Admission.CONFLICT;
            }

            /// Write-ahead: the retirement stands in memory at once (every later piece of evidence is refused), but it is
            /// announced, and this caller answered, only after the ledger holds it.
            private Promise<Admission> conflict(NodeId peer) {
                var write = Promise.<Unit> promise();
                // The entry is published BEFORE the NodeId counts as retired, so a thread that sees the retirement can always
                // find the write it has to wait for ([#retirementDurable]).
                if (writes.putIfAbsent(peer, write) != null || !retired.add(peer)) {
                    return Promise.success(refused(Admission.RETIRED));
                }
                // FER: the retirement already stands in memory and is announced even when its ledger write fails; what is
                // lost is protection across a restart of every holder, reported through the ledger's durability listener.
                return ledger.get()
                             .fold(() -> settled(write).map(_ -> announceRetirement(peer)),
                                   held -> retireDurably(held, peer, write));
            }

            private Promise<Unit> settled(Promise<Unit> write) {
                return write.succeed(Unit.unit());
            }

            private Promise<Admission> retireDurably(IdentityLedger held, NodeId peer, Promise<Unit> write) {
                return held.record(IdentityFacts.terminated(peer, TOKEN_CONFLICT))
                           .recover(_ -> Unit.unit())
                           .flatMap(_ -> settled(write))
                           .map(_ -> announceRetirement(peer));
            }

            @Override
            public Promise<Unit> retirementDurable(NodeId peer) {
                return Option.option(writes.get(peer))
                             .or(() -> Promise.success(Unit.unit()));
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
            public Unit attachLedger(IdentityLedger attached) {
                ready.set(false);
                ledger.set(Option.some(attached));

                return Unit.unit();
            }

            @Override
            public Unit preload(IdentityFacts facts, boolean lost) {
                apply(facts);
                wiped.set(lost);
                ready.set(true);

                return Unit.unit();
            }

            private Unit apply(IdentityFacts facts) {
                retired.addAll(facts.retired());

                return Unit.unit();
            }

            @Override
            public Promise<Unit> merged(IdentityFacts peerFacts) {
                return ledger.get()
                             .fold(() -> Promise.success(Unit.unit()),
                                   attached -> attached.record(peerFacts))
                             .map(_ -> adoptMerge(peerFacts));
            }

            private Unit adoptMerge(IdentityFacts peerFacts) {
                wiped.set(false);

                return apply(peerFacts);
            }

            @Override
            public boolean ledgerDurable() {
                return ledger.get()
                             .map(IdentityLedger::durable)
                             .or(false);
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
                            new AtomicBoolean(),
                            new AtomicReference<>(Option.none()),
                            new AtomicBoolean(true),
                            new AtomicBoolean(),
                            new ConcurrentHashMap<>());
    }
}
