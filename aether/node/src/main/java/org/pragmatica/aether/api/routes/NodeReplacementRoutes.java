// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService.Refusal;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService.Settlement;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.pragmatica.http.routing.PathParameter.aString;


/// #1543 E2 — the operator surface of replacement-based upgrade: start a replacement (the leader provisions the new node, or
/// the operator names a fresh id and starts it), list the replacements, and settle one that stopped with both nodes kept.
/// The phases are driven by the leader's reconciler; these routes only create and settle records through [NodeReplacementService].
public final class NodeReplacementRoutes implements RouteSource {
    /// `replacement` absent: the leader provisions the new node. Present: the operator-chosen fresh id the operator starts
    /// itself. `targetVersion` is the version the replacement must run before it is kept (absent = no gate).
    public record ReplaceNodeRequest(String replacement, String targetVersion) {}

    /// `outcome` is `keep-new` or `roll-back`.
    public record SettleRequest(String outcome) {}

    public record ReplacementEntry(String original,
                                   String replacement,
                                   String role,
                                   String phase,
                                   String mode,
                                   String source,
                                   String targetVersion,
                                   int attempt,
                                   String reason,
                                   long phaseDeadlineMs,
                                   long epoch) {
        static ReplacementEntry replacementEntry(NodeId original, NodeReplacementValue value) {
            return new ReplacementEntry(original.id(),
                                        value.replacement().id(),
                                        value.role(),
                                        value.phase().name(),
                                        value.mode(),
                                        value.source(),
                                        value.targetVersion(),
                                        value.attempt(),
                                        value.reason(),
                                        value.phaseDeadlineMs(),
                                        value.epoch());
        }
    }

    public record SettleResponse(String original, String outcome) {}

    private final Supplier<ManageableNode> nodeSupplier;

    private NodeReplacementRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static NodeReplacementRoutes nodeReplacementRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new NodeReplacementRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ReplacementEntry> route(ManagementRoute.NODE_REPLACE)
                                         .withPath(aString())
                                         .withBody(ReplaceNodeRequest.class)
                                         .toJson(this::replace),
                         ManagementRoutes.<List<ReplacementEntry>> route(ManagementRoute.NODE_REPLACEMENTS).toJson(this::list),
                         ManagementRoutes.<SettleResponse> route(ManagementRoute.NODE_REPLACEMENT_SETTLE)
                                         .withPath(aString())
                                         .withBody(SettleRequest.class)
                                         .toJson(this::settle));
    }

    Promise<ReplacementEntry> replace(String originalId, ReplaceNodeRequest request) {
        var version = Option.option(request.targetVersion()).or("");

        return RequestParse.asRequest(NodeId.nodeId(originalId))
                           .async()
                           .flatMap(original -> start(original,
                                                      Option.option(request.replacement()),
                                                      version).map(value -> ReplacementEntry.replacementEntry(original,
                                                                                                              value)))
                           .mapError(NodeReplacementRoutes::asManagementError);
    }

    private Promise<NodeReplacementValue> start(NodeId original, Option<String> chosen, String version) {
        var service = service();

        return chosen.filter(id -> !id.isBlank())
                     .fold(() -> service.begin(original, version),
                           id -> RequestParse.asRequest(NodeId.nodeId(id))
                                             .async()
                                             .flatMap(replacement -> service.beginExternal(original,
                                                                                           replacement,
                                                                                           version)));
    }

    List<ReplacementEntry> list() {
        return service().all()
                      .entrySet()
                      .stream()
                      .map(entry -> ReplacementEntry.replacementEntry(entry.getKey(),
                                                                      entry.getValue()))
                      .sorted(Comparator.comparing(ReplacementEntry::original))
                      .toList();
    }

    Promise<SettleResponse> settle(String originalId, SettleRequest request) {
        return RequestParse.asRequest(NodeId.nodeId(originalId))
                           .async()
                           .flatMap(original -> settlement(request).async()
                                                          .flatMap(settlement -> service().settle(original, settlement)
                                                                                        .map(_ -> new SettleResponse(original.id(),
                                                                                                                     request.outcome()))))
                           .mapError(NodeReplacementRoutes::asManagementError);
    }

    private static org.pragmatica.lang.Result<Settlement> settlement(SettleRequest request) {
        return switch (Option.option(request.outcome()).or("")) {
            case "keep-new" -> org.pragmatica.lang.Result.success(Settlement.KEEP_NEW);
            case "roll-back" -> org.pragmatica.lang.Result.success(Settlement.ROLL_BACK);
            default -> new ManagementServerError.InvalidRequest("outcome must be 'keep-new' or 'roll-back'").result();
        };
    }

    private NodeReplacementService service() {
        return nodeSupplier.get()
                           .nodeReplacementService();
    }

    /// A refusal is a statement about the request or the cluster's state, not a server fault: answer 404 / 400 / 409.
    static Cause asManagementError(Cause cause) {
        return switch (cause) {
            case Refusal.UnknownNode refusal -> new ManagementServerError.NotFound(refusal.message());
            case Refusal.RoleNotSupported refusal -> new ManagementServerError.InvalidRequest(refusal.message());
            case Refusal refusal -> new ManagementServerError.Conflict(refusal.message());
            default -> cause;
        };
    }
}
