// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import org.pragmatica.aether.deployment.cluster.fsm2.Admission;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// What membership says about one node id when it admits it (spec CI-15, C6; design 5.3, MQ-4 ruled): the **process** it admitted, as
/// `ProcessIncarnation(bootIncarnation, bootToken)`. Under the leader-assigned epoch (Option A) the admission carries nothing else: the epoch
/// is the committed record's.
///
/// - `bootToken` is the registry's token for the node *now* (`BootTokens.tokenOf`; `0` while unknown, which C8 treats as "no positive
///   evidence of a different process").
/// - `bootIncarnation` is the membership FSM's SWIM incarnation high-water for the node (`MembershipFsm.memberIncarnations()`).
///   `[guess: the spec names "the SWIM boot incarnation" (C6) and no accessor holds the peer's own boot value; the high-water is the closest
///   thing the leader reads, and only the token takes part in the process comparison (Admission.isDifferentProcessFrom)]`
///
/// **The one reader** (cluster-assessment delta 7, owner Q2): every token and incarnation read of the deployment engine goes through this
/// seam (the routes' `knownToken`, the seam poller's `ProcessKnown`, the node interpreter, the `Rebuild` admissions), so swapping the identity
/// authority later is a change here only. The node builds it before membership exists ([#boundLate]); a read before membership is bound is
/// counted in [#unboundReads], which a correctly assembled node never increments.
public final class AdmissionSeam {
    private final ToLongFunction<NodeId> tokenOf;
    private final Supplier<Option<ToLongFunction<NodeId>>> incarnations;
    private final AtomicLong unboundReads = new AtomicLong();

    private AdmissionSeam(ToLongFunction<NodeId> tokenOf, Supplier<Option<ToLongFunction<NodeId>>> incarnations) {
        this.tokenOf = tokenOf;
        this.incarnations = incarnations;
    }

    public static AdmissionSeam admissionSeam(ToLongFunction<NodeId> tokenOf,
                                              ToLongFunction<NodeId> bootIncarnationOf) {
        return new AdmissionSeam(tokenOf, () -> Option.some(bootIncarnationOf));
    }

    /// A seam whose incarnation source is bound after the seam is built (the node assembles membership after the deployment wiring). Until
    /// it is bound an incarnation reads `0` (no evidence) and the read is counted.
    public static AdmissionSeam boundLate(ToLongFunction<NodeId> tokenOf,
                                          Supplier<Option<ToLongFunction<NodeId>>> incarnations) {
        return new AdmissionSeam(tokenOf, incarnations);
    }

    /// `admission(n)`.
    public Admission admission(NodeId node) {
        return Admission.admission(new ProcessIncarnation(bootIncarnationOf(node), tokenOf(node)));
    }

    /// The registry's token for `node` now (`0` while unknown).
    public long tokenOf(NodeId node) {
        return tokenOf.applyAsLong(node);
    }

    /// Reads made before the incarnation source was bound.
    public long unboundReads() {
        return unboundReads.get();
    }

    private long bootIncarnationOf(NodeId node) {
        return incarnations.get()
                           .onEmpty(unboundReads::incrementAndGet)
                           .map(source -> source.applyAsLong(node))
                           .or(0L);
    }

    /// The admissions of `nodes`, for the snapshot a `Rebuild` is stepped from.
    public Map<NodeId, Admission> admissions(Set<NodeId> nodes) {
        return nodes.stream()
                    .collect(Collectors.toMap(node -> node, this::admission));
    }
}
