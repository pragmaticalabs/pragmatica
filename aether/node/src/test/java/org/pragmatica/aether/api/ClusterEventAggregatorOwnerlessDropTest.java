// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;

import static org.assertj.core.api.Assertions.assertThat;


/// #957 — the owner-gate drops events, and TWO DIFFERENT SITUATIONS produce that drop. This pins that
/// the aggregator tells them apart, because only one of them is a defect.
///
/// **Why this needed a second supplier rather than a louder log.** `ownerCheck` returns false both when
/// another node owns partition 0 — the steady state on every non-owner, where the event IS published,
/// just not here — and when ownership cannot be determined at all, where NO node publishes and the
/// event is lost with no queue and no retry. Logging the first at WARN would emit a line per event per
/// non-owner per tick and report correct operation as a fault; that is not a louder instrument, it is a
/// broken one.
///
/// **Why every assertion here is on the COUNTER and never on "nothing was published".** A correct
/// suppression and a lost event are both "nothing was published" — the naive assertion cannot fail, and
/// a test that cannot fail is not evidence. The counter is the only observation that separates them.
class ClusterEventAggregatorOwnerlessDropTest {

    private static final NodeId SELF = new NodeId("self-node");
    private static final BooleanSupplier OWNER = () -> true;
    private static final BooleanSupplier NOT_OWNER = () -> false;
    private static final BooleanSupplier RESOLVABLE = () -> true;
    private static final BooleanSupplier UNRESOLVABLE = () -> false;
    private static final BooleanSupplier NOT_REPLAYING = () -> false;

    /// Publisher deliberately unbound: every case here is decided by the owner gate, which runs BEFORE
    /// any publish. If a change ever moved the gate after the publish, these tests would notice.
    private static ClusterEventAggregator aggregator(BooleanSupplier ownerCheck, BooleanSupplier ownershipResolvable) {
        return ClusterEventAggregator.clusterEventAggregator(() -> null,
                                                             () -> null,
                                                             ownerCheck,
                                                             SELF,
                                                             HlcClock.hlcClock(SELF),
                                                             () -> 1,
                                                             NOT_REPLAYING,
                                                             () -> true,
                                                             ownershipResolvable);
    }

    private static ClusterEvent breach(HlcClock hlc) {
        return new ClusterEvent.ThresholdBreached(hlc.now(),
                                                  ClusterEvent.Severity.CRITICAL,
                                                  "cpu.usage breached on node-1",
                                                  Map.of("metric", "cpu.usage", "nodeId", "node-1"));
    }

    /// The hole: nobody can own, so nobody publishes and the event is gone. Counted, so the gap in the
    /// audit log has a size an operator can read rather than infer.
    @Test
    void unresolvableOwnership_countsTheDrop() {
        var aggregator = aggregator(NOT_OWNER, UNRESOLVABLE);
        var hlc = HlcClock.hlcClock(SELF);

        assertThat(aggregator.ownerlessDrops())
                .describedAs("precondition: nothing dropped yet")
                .isZero();

        aggregator.emit(breach(hlc));
        aggregator.emit(breach(hlc));
        aggregator.emit(breach(hlc));

        assertThat(aggregator.ownerlessDrops())
                .describedAs("each event dropped for want of an owner must be counted")
                .isEqualTo(3);
    }

    /// **The discriminating control, and the reason the counter means anything.** This is the ordinary
    /// steady state on N-1 nodes of a healthy cluster: this node is not the owner, but ownership is
    /// resolvable, so some other node published the event. Nothing was lost, so nothing is counted.
    ///
    /// Without this test the counter would be satisfied by an implementation that counted every
    /// suppression — which on a 5-node cluster would report ~4 "lost" events per second forever.
    @Test
    void nonOwnerWithResolvableOwnership_countsNothing() {
        var aggregator = aggregator(NOT_OWNER, RESOLVABLE);
        var hlc = HlcClock.hlcClock(SELF);

        for (int i = 0; i < 50; i++) {
            aggregator.emit(breach(hlc));
        }

        assertThat(aggregator.ownerlessDrops())
                .describedAs("a non-owner in a healthy cluster loses nothing — the owner published it")
                .isZero();
    }

    /// The third arm: this node IS the owner, so the event goes to the publish path and is never
    /// counted as dropped. Together the three arms are mutually exclusive and cover the gate's whole
    /// decision space.
    @Test
    void owner_countsNothing() {
        var aggregator = aggregator(OWNER, RESOLVABLE);
        var hlc = HlcClock.hlcClock(SELF);

        aggregator.emit(breach(hlc));

        assertThat(aggregator.ownerlessDrops()).isZero();
    }

    /// The counter is about ownership, not about the publisher. Here ownership is resolvable and held,
    /// and the publisher is unbound — a real bootstrap state — so the event is still lost, but it is
    /// NOT an ownerless drop and must not be miscounted as one. `publishSafely` logs that case
    /// separately.
    @Test
    void ownerWithUnboundPublisher_isNotCountedAsAnOwnerlessDrop() {
        var aggregator = aggregator(OWNER, RESOLVABLE);
        var hlc = HlcClock.hlcClock(SELF);

        aggregator.emit(breach(hlc));

        assertThat(aggregator.ownerlessDrops())
                .describedAs("an unbound publisher is a different failure and has its own log line")
                .isZero();
    }
}
