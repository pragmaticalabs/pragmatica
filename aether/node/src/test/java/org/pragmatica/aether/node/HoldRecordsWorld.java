// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.cluster.metrics.HeldReport;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;

/// One cluster seen by several leader instances: the FAN, ONE landed-event log shared by every leader, the clock, and
/// whether raises land or stay pending. Closers are counted per subject in the shared log, so a second closer raised by a
/// later leader for the same episode is visible (the invariant "exactly one closer per episode whose at-risk was raised").
final class HoldRecordsWorld {
    static final NodeId N = NodeId.nodeId("drainer-1").unwrap();
    static final String E1 = "drainer-1@1000";
    static final String E2 = "drainer-1@2000";
    static final String E3 = "drainer-1@3000";
    static final long T0 = 1_000_000L;
    static final HoldRecords.LossConfirmation UNK = HoldRecords.LossConfirmation.InventoryUnknown.INVENTORY_UNKNOWN;
    static final OperatorWarningCode AT_RISK = OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK;

    final Map<NodeId, HeldReport> fan = new ConcurrentHashMap<>();
    final List<ClusterEvent> log = new CopyOnWriteArrayList<>();
    final List<Runnable> pending = new CopyOnWriteArrayList<>();
    volatile boolean lands = true;
    volatile boolean holdOpen = false;
    volatile long now = T0;

    HoldRecords leader() {
        return HoldRecords.holdRecords(id -> Option.option(fan.get(id)), new HoldRecords.WarningRaiser() {
            @Override
            public Promise<Boolean> raiseLanded(OperatorWarning warning) {
                return raise(warning, Option.none());
            }

            @Override
            public Promise<Boolean> raiseClosing(OperatorWarning warning, OperatorWarning closer) {
                return raise(warning, Option.some(closer));
            }
        }, () -> Promise.success(List.copyOf(log)), () -> now, HoldRecords.REPORT_AFTER_MS);
    }

    private Promise<Boolean> raise(OperatorWarning warning, Option<OperatorWarning> closer) {
        if (holdOpen) {
            var promise = Promise.<Boolean>promise();
            pending.add(() -> settleOne(warning, closer, promise));
            return promise;
        }
        if (lands) {
            land(warning, closer);
        }
        return Promise.success(lands);
    }

    private void settleOne(OperatorWarning warning, Option<OperatorWarning> closer, Promise<Boolean> promise) {
        if (lands) {
            land(warning, closer);
        }
        promise.succeed(lands);
    }

    /// raiseClosing lands the at-risk only when it is not already open in the log, then the closer.
    private void land(OperatorWarning warning, Option<OperatorWarning> closer) {
        var openHere = warning.code() == AT_RISK && atRisk(warning.subject()) > closersLanded(warning.subject());
        if (closer.isEmpty() || !openHere) {
            add(warning);
        }
        closer.onPresent(this::add);
    }

    private void add(OperatorWarning warning) {
        log.add(new ClusterEvent.OperatorWarning(null, ClusterEvent.Severity.INFO, warning.message(),
                                                 Map.of("code", warning.code().code(), "subject", warning.subject())));
    }

    void settle() {
        var all = new ArrayList<>(pending);
        pending.clear();
        all.forEach(Runnable::run);
    }

    long count(String code, String subject) {
        return log.stream()
                  .map(ClusterEvent.OperatorWarning.class::cast)
                  .filter(w -> code.equals(w.details().get("code")) && subject.equals(w.details().get("subject")))
                  .count();
    }

    long closersLanded(String subject) {
        return count(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED.code(), subject)
               + count(OperatorWarningCode.DHT_DEPARTURE_HOLD_ENDED.code(), subject);
    }

    long atRisk(String subject) {
        return count(AT_RISK.code(), subject);
    }

    void pong(HoldRecords leader, HeldReport report) {
        fan.put(N, report);
        leader.onPong(N);
    }

    static HeldReport held(String episode, long asOf) {
        return HeldReport.heldReport(episode, -1, 0, "", asOf, List.of("k1"), HeldEnd.NONE);
    }

    static HeldReport heldDigest(String episode, long asOf, String digest) {
        return HeldReport.heldReport(episode, 3, 0, digest, asOf, List.of("k1"), HeldEnd.NONE);
    }

    static HeldReport ended(String episode, long asOf, HeldEnd end) {
        return HeldReport.heldReport(episode, -1, 0, "", asOf, List.of("k1"), end);
    }

    static void ticks(HoldRecords leader, int n) {
        for (int i = 0; i < n; i++) {
            leader.tick();
        }
    }
}
