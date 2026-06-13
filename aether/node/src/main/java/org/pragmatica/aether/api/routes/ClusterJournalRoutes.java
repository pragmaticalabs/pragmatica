// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.journal.TransitionJournal;
import org.pragmatica.aether.node.journal.TransitionJournal.Layer;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;


/// Wave-1 Enrichment A (cluster-topology-overhaul spec): `GET /api/cluster/journal` — dump of
/// the per-node transition journal ([TransitionJournal]; every `MembershipFsm` and `PeerState`
/// transition, bounded ring buffer per layer). Per-node ([ManagementRoute#CLUSTER_JOURNAL] is
/// `LOCAL`) and read-only.
///
/// Query parameters:
///   - `layer` — `fsm` or `peer`; absent returns BOTH layers merged in sequence order.
///   - `limit` — maximum entries per layer (default [#DEFAULT_LIMIT]), newest kept.
public final class ClusterJournalRoutes implements RouteSource {
    private static final int DEFAULT_LIMIT = 256;

    private static final Cause INVALID_LAYER = Causes.cause("Invalid 'layer' query parameter: expected 'fsm' or 'peer'");

    private static final Cause NON_POSITIVE_LIMIT = Causes.cause("limit must be positive");

    public record JournalEntryView(long seq,
                                   long wallClockMs,
                                   String layer,
                                   String nodeId,
                                   String from,
                                   String to,
                                   String cause,
                                   long incarnation,
                                   String role) {}

    public record JournalResponse(int count, List<JournalEntryView> entries) {}

    private final Supplier<ManageableNode> nodeSupplier;

    private ClusterJournalRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ClusterJournalRoutes clusterJournalRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new ClusterJournalRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<JournalResponse> route(ManagementRoute.CLUSTER_JOURNAL)
                                         .withQuery(QueryParameter.aString("layer"),
                                                    QueryParameter.aString("limit"))
                                         .to(this::handleJournal)
                                         .asJson());
    }

    private Promise<JournalResponse> handleJournal(Option<String> layerOpt, Option<String> limitOpt) {
        var limit = limitOpt.map(ClusterJournalRoutes::parseLimit).or(DEFAULT_LIMIT);

        return parseLayers(layerOpt).map(layers -> buildResponse(layers, limit))
                          .async();
    }

    private static int parseLimit(String raw) {
        return Number.parseInt(raw)
                     .filter(NON_POSITIVE_LIMIT, value -> value > 0)
                     .or(DEFAULT_LIMIT);
    }

    private static Result<List<Layer>> parseLayers(Option<String> layerOpt) {
        return layerOpt.map(ClusterJournalRoutes::parseSingleLayer)
                       .or(Result.success(List.of(Layer.FSM, Layer.PEER)));
    }

    private static Result<List<Layer>> parseSingleLayer(String raw) {
        return switch (raw.toLowerCase()) {
            case "fsm" -> Result.success(List.of(Layer.FSM));
            case "peer" -> Result.success(List.of(Layer.PEER));
            default -> INVALID_LAYER.result();
        };
    }

    private JournalResponse buildResponse(List<Layer> layers, int limit) {
        var journal = nodeSupplier.get().transitionJournal();
        var entries = new ArrayList<TransitionJournal.Entry>();

        layers.forEach(layer -> entries.addAll(journal.snapshot(layer, limit)));
        entries.sort(Comparator.comparingLong(TransitionJournal.Entry::seq));
        var views = entries.stream().map(ClusterJournalRoutes::toView).toList();

        return new JournalResponse(views.size(), views);
    }

    private static JournalEntryView toView(TransitionJournal.Entry entry) {
        return new JournalEntryView(entry.seq(),
                                    entry.wallClockMs(),
                                    entry.layer().name(),
                                    entry.nodeId(),
                                    entry.from(),
                                    entry.to(),
                                    entry.cause(),
                                    entry.incarnation(),
                                    entry.role());
    }
}
