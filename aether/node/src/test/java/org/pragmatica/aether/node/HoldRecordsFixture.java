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
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.cluster.metrics.HeldReport;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;

/// Fakes for [HoldRecords] (#3345, D45-6): the FAN's live held report per node, an event layer whose raises land, do not land,
/// or stay pending until the test settles them, a settable clock and a settable event log.
final class HoldRecordsFixture {
    static final NodeId NODE = NodeId.nodeId("drainer-1").unwrap();
    static final String EPISODE = "drainer-1@1000";
    static final long T0 = 1_000_000L;

    /// One raise the fake event layer received: `closer` empty for `raiseLanded`.
    record Raised(OperatorWarning warning, Option<OperatorWarning> closer) {}

    final Map<NodeId, HeldReport> live = new ConcurrentHashMap<>();
    final List<Raised> raised = new CopyOnWriteArrayList<>();
    final List<Promise<Boolean>> pending = new CopyOnWriteArrayList<>();
    final List<ClusterEvent> log = new ArrayList<>();
    volatile boolean lands = true;
    volatile boolean holdOpen = false;
    volatile long now = T0;

    final HoldRecords records = HoldRecords.holdRecords(nodeId -> Option.option(live.get(nodeId)),
                                                        new HoldRecords.WarningRaiser() {
                                                            @Override
                                                            public Promise<Boolean> raiseLanded(OperatorWarning warning) {
                                                                return record(warning, Option.none());
                                                            }

                                                            @Override
                                                            public Promise<Boolean> raiseClosing(OperatorWarning warning,
                                                                                                 OperatorWarning closer) {
                                                                return record(warning, Option.some(closer));
                                                            }
                                                        },
                                                        () -> Promise.success(List.copyOf(log)),
                                                        () -> now,
                                                        HoldRecords.REPORT_AFTER_MS);

    private Promise<Boolean> record(OperatorWarning warning, Option<OperatorWarning> closer) {
        raised.add(new Raised(warning, closer));
        if (holdOpen) {
            var promise = Promise.<Boolean>promise();
            pending.add(promise);
            return promise;
        }
        return Promise.success(lands);
    }

    static HeldReport report(long owed, long undeliverable, String digest, HeldEnd ended) {
        return HeldReport.heldReport(EPISODE, owed, undeliverable, digest, T0 - 500, List.of("k1", "k2"), ended);
    }

    static HeldReport held(long owed, String digest) {
        return report(owed, 0, digest, HeldEnd.NONE);
    }

    /// The drainer pongs with `report`.
    void pong(HeldReport report) {
        live.put(NODE, report);
        records.onPong(NODE);
    }

    /// Settle every pending raise with `landed` and wait until the unit has observed it.
    void settlePending(boolean landed, BooleanSupplier observed) {
        pending.forEach(promise -> promise.succeed(landed));
        pending.clear();
        eventually(observed);
    }

    static void eventually(BooleanSupplier condition) {
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(condition.getAsBoolean()).as("condition reached within 5 s").isTrue();
    }

    long raisedOf(OperatorWarningCode code) {
        return raised.stream()
                     .filter(raise -> raise.warning().code() == code && raise.closer().isEmpty())
                     .count();
    }

    List<OperatorWarning> closersOf(OperatorWarningCode code) {
        return raised.stream()
                     .flatMap(raise -> raise.closer().stream())
                     .filter(closer -> closer.code() == code)
                     .toList();
    }

    void logWarning(OperatorWarningCode code, String subject) {
        log.add(new ClusterEvent.OperatorWarning(null,
                                                 ClusterEvent.Severity.INFO,
                                                 "m",
                                                 Map.of("code", code.code(), "subject", subject)));
    }
}
