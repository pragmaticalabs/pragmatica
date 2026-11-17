// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.deployment.membership.ntt.DepartureOutcome;
import org.pragmatica.aether.deployment.membership.ntt.DrainWindow;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTRebalancer;
import org.pragmatica.dht.DHTTopologyListener;
import org.pragmatica.dht.DeparturePushObserver;
import org.pragmatica.dht.DeparturePushReport;
import org.pragmatica.dht.DeparturePushTuning;
import org.pragmatica.dht.DepartureWindow;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;


/// The DHT half of a draining node's departure push (#427), as `AetherNode` assembles it (#1818).
///
/// The push excludes every node the leader commanded to drain alongside this one. That set is read
/// from the collector WHEN the push runs, not when the supplier is built: the leader ping carrying the
/// drain command records its global `drainNodes` before the drain handler fires, so the push sees every
/// node commanded in the same ping. A node in it is not counted as a surviving holder, and its ack never
/// proves delivery, because the entry may be stale (a live node) or true (a node that halts with the copy).
/// The push therefore also targets the named nodes that precede the first unnamed node of the ring walk, and
/// that first unnamed node (#3208, #3209): the copy reaches the primary of every possible final replica set,
/// and a stale entry can cost extra copies (to a co-drainer, to an existing replica), never a missing one. A cleared set is the pre-#1818 behaviour: a
/// co-drainer missing from it can still absorb the push or be counted as a survivor.
///
/// #2034: the push runs inside the drain's [DrainWindow] and reports a [DepartureOutcome]. Its events carry the subject
/// `<nodeId>@<drainStartMillis>`, one drain episode, because node ids are reused (#2063).
public sealed interface DhtDeparturePush {
    /// The departure push the drain procedure invokes once the node has quiesced. `observers` maps the drain-episode subject to
    /// the observer that raises the push's events; it is read when the push runs, so a binding made after assembly is honoured.
    static Function<DrainWindow, Promise<DepartureOutcome>> dhtDeparturePush(DHTRebalancer rebalancer,
                                                                             ClusterSyncCollector drainCommands,
                                                                             NodeId self,
                                                                             Supplier<Function<String, DeparturePushObserver>> observers) {
        return window -> rebalancer.pushOnDeparture(new DepartureWindow(window.legsDeadlineNanos(),
                                                                        window.eventFloorNanos(),
                                                                        window.halted()),
                                                    DeparturePushTuning.DEFAULT,
                                                    drainCommands.commandedDrainNodes(),
                                                    observers.get().apply(subject(self,
                                                                                  window.drainStartMillis())))
                                   .map(DhtDeparturePush::outcome);
    }

    /// The event subject of one drain episode.
    static String subject(NodeId self, long drainStartMillis) {
        return self.id() + "@" + drainStartMillis;
    }

    /// Sole-copy keys left unconfirmed, or an inventory never enumerated, is a drain that completed with data at risk.
    static DepartureOutcome outcome(DeparturePushReport report) {
        return report.dataAtRisk()
               ? DepartureOutcome.COMPLETED_WITH_DATA_AT_RISK
               : DepartureOutcome.COMPLETED;
    }

    /// The observer bound to this node's aggregator (#2034): at-risk and delivered become the `dht-departure-sole-copy-*` operator
    /// warnings for `subject`, the final report becomes `DeparturePushIncomplete`. Every event is awaited (the returned promises
    /// settle when it lands, is given up or suppressed), so the push can wait for them before the node halts.
    static DeparturePushObserver departureEvents(ClusterEventAggregator aggregator, NodeId self, String subject) {
        return new DeparturePushObserver() {
            @Override
            @Contract
            public void onIncomplete(int keysAtRisk, List<String> sampleKeys) {
                aggregator.onDeparturePushIncomplete(self, keysAtRisk, sampleKeys);
            }

            @Override
            public Promise<Unit> onSoleCopyAtRisk(int count, List<String> sampleKeys) {
                return aggregator.raiseAwaited(OperatorWarning.operatorWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK,
                                                                               subject,
                                                                               atRiskMessage(self, count, sampleKeys)));
            }

            @Override
            public Promise<Unit> onSoleCopyDelivered(int count) {
                return aggregator.raiseAwaited(OperatorWarning.operatorWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED,
                                                                               subject,
                                                                               "Draining node " + self.id()
                                                                              + ": all " + count
                                                                              + " sole-copy DHT chunk(s) acknowledged by their new holders"));
            }

            @Override
            public Promise<Unit> onIncompleteReport(int keysAtRisk, int soleCopyAtRisk, List<String> sampleKeys) {
                return aggregator.onDeparturePushIncompleteAwaited(self, keysAtRisk, soleCopyAtRisk, sampleKeys);
            }
        };
    }

    private static String atRiskMessage(NodeId self, int count, List<String> sampleKeys) {
        return count == 0
               ? "Draining node " + self.id()
                + ": DHT inventory not enumerated before the drain deadline: at-risk set unknown; restore or re-put its keys from their source of truth"
               : "Draining node " + self.id()
                + ": " + count
                + " sole-copy DHT chunk(s) not yet acknowledged by any new holder; if the node halts first, restore or re-put them from their source of truth (first keys: " + String.join(",",
                                                                                                                                                                                           sampleKeys)
                + "; full list in the node's WARN log)";
    }

    /// The RECEIVING side (#1818 round 3): the senders a departure push is accepted from. A node is departing
    /// if the leader's latest drain set names it, or this node's membership saw it enter DEPARTING. Both are
    /// read per push, so a drain commanded after the push was built is still honoured. A push from any
    /// other node is refused and nacked, so its sender keeps the batch at risk.
    static Predicate<NodeId> departingSenders(ClusterSyncCollector drainCommands, DHTTopologyListener topology) {
        return sender -> drainCommands.commandedDrainNodes()
                                      .contains(sender) || topology.isDeparting(sender);
    }

    record unused() implements DhtDeparturePush {}
}
