// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.WarningEntry;
import org.pragmatica.aether.api.ManagementApiResponses.WarningsResponse;
import org.pragmatica.aether.api.ManagementServerError.ServiceUnavailable;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.cluster.state.kvstore.KVStore.RevisionedSnapshot;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// `GET /api/v1/warnings` (#3274): the open FSM warnings of one committed state, read on the answering core and labelled by its revision.
///
/// Core nodes only, no forwarding. Four typed refusals, each a 503 with its own detail: a worker (its replica holds no warning rows), a core
/// without quorum (read through [StatusRoutes#quorumStatus], the source `/api/status`, `/api/health` and `/health/ready` share), revision 0
/// (genesis or a reset store), and a label that is not exact (a restore moved the label without the content, until the next install).
/// The quorum check is an observation read before the store monitor hold, not a safety guard: a served answer is at most as stale as the
/// core's apply lag.
public final class WarningsRoutes implements RouteSource {
    static final Cause WORKER = new ServiceUnavailable("Warnings are served by core nodes only; this node is a worker");

    static final Cause NO_QUORUM = new ServiceUnavailable("This core does not hold quorum; its warning rows may be stale");

    static final Cause NO_REVISION = new ServiceUnavailable("This core has committed no state yet (revision 0)");

    static final Cause NOT_EXACT = new ServiceUnavailable("This core's revision label is not exact; warnings resume after its next snapshot install");

    private static final Comparator<WarningEntry> ORDER = Comparator.comparing(WarningEntry::code).thenComparing(WarningEntry::subject);

    private final Supplier<ManageableNode> nodeSupplier;

    private WarningsRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static WarningsRoutes warningsRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new WarningsRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<WarningsResponse> route(ManagementRoute.WARNINGS)
                                         .to(_ -> currentWarnings())
                                         .asJson());
    }

    private Promise<WarningsResponse> currentWarnings() {
        var node = nodeSupplier.get();

        return read(node.isConfiguredWorker(),
                    () -> StatusRoutes.quorumStatus(node).held(),
                    () -> node.kvStore()
                              .snapshotWithRevision(FsmWarningKey.class::isInstance)).async();
    }

    /// The whole decision, with the node reduced to its three observations: role, quorum (read only for a core) and the labelled snapshot.
    static Result<WarningsResponse> read(boolean worker,
                                         BooleanSupplier quorumHeld,
                                         Supplier<RevisionedSnapshot<AetherKey, AetherValue>> snapshot) {
        return require(!worker, WORKER).flatMap(_ -> require(quorumHeld.getAsBoolean(),
                                                             NO_QUORUM))
                      .flatMap(_ -> labelled(snapshot.get()));
    }

    private static Result<WarningsResponse> labelled(RevisionedSnapshot<AetherKey, AetherValue> snapshot) {
        return require(snapshot.revision() > 0,
                       NO_REVISION).flatMap(_ -> require(snapshot.exact(),
                                                         NOT_EXACT))
                      .map(_ -> response(snapshot));
    }

    private static Result<Unit> require(boolean holds, Cause refusal) {
        return holds
               ? Result.unitResult()
               : refusal.result();
    }

    private static WarningsResponse response(RevisionedSnapshot<AetherKey, AetherValue> snapshot) {
        return new WarningsResponse(snapshot.revision(), openWarnings(snapshot));
    }

    private static List<WarningEntry> openWarnings(RevisionedSnapshot<AetherKey, AetherValue> snapshot) {
        return ordered(snapshot.entries()
                               .entrySet()
                               .stream()
                               .filter(WarningsRoutes::isOpenRow)
                               .map(WarningsRoutes::entryOf)
                               .toList());
    }

    private static List<WarningEntry> ordered(List<WarningEntry> entries) {
        return entries.stream()
                      .sorted(ORDER)
                      .toList();
    }

    private static WarningEntry entryOf(Map.Entry<AetherKey, AetherValue> row) {
        return warningEntry((FsmWarningKey) row.getKey(), (FsmWarningValue) row.getValue());
    }

    /// A tombstone (`open` false) is a closed warning whose recovery is not yet published: not an open warning.
    private static boolean isOpenRow(Map.Entry<AetherKey, AetherValue> entry) {
        return entry.getKey() instanceof FsmWarningKey
               && entry.getValue() instanceof FsmWarningValue value
               && value.open();
    }

    /// The ONE place a stored warning row becomes a response entry. `message` is the row's opener message (the latest the writing leader
    /// committed), empty once the row carries none.
    static WarningEntry warningEntry(FsmWarningKey key, FsmWarningValue value) {
        return new WarningEntry(key.code(),
                                key.subject(),
                                value.members(),
                                value.opener().or(""));
    }
}
