// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeclaredRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRoute;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRouteValue;
import org.pragmatica.aether.slice.kvstore.RouteState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;


/// The leader-side writers of the declared-route table that follow the committed blueprint (#3389 A2): DECLARE (W1), the re-drive of a lost
/// DECLARE (W1b), REMOVE of a removed blueprint's rows (W4) and PUBLISH (W2); [#fail] (W3) is the one writer that rides another transaction.
///
/// One mechanism for the four: [#reconcile] diffs the rows the blueprint's declared routes name against the committed rows the blueprint owns
/// and drafts the difference as one `ROUTES` transaction. PUBLISH is part of that difference: a `Declared` row of an artifact some node
/// publishes routes for becomes `Published`. A republish after a rollback (W5) and a restored backup (W6) are the same event, a
/// committed blueprint put or remove, so they have no writer of their own.
///
/// Every draft carries a witness on the owner blueprint's row, so a draft that lands after the blueprint's remove or republish is refused by the
/// applier, in any commit order and across a leader change.
public sealed interface Routes {
    // -- W1, W4: the diff against the committed blueprint ----------------------------------------------------------
    /// Draft the difference between the routes blueprint `id` declares and the rows it owns: a row for each declared route that has none (or
    /// whose row another blueprint owns, or whose `Failed` row belongs to an earlier attempt, which becomes `Declared` on the new attemptId), a
    /// removal of each owned row no declared route names, and of every owned row once the blueprint is gone. A `registerOnly` blueprint
    /// deploys nothing, so it declares nothing and its rows are left as they are.
    ///
    /// A draft of this blueprint already queued or in flight is not repeated: its refusal or loss is derived again by [#sweep].
    static ClusterModel reconcile(ClusterModel m, Ctx ctx, BlueprintId id) {
        return inFlight(m, id)
               ? m
               : submit(m, id, difference(m, id));
    }

    // -- W2: PUBLISH, W3: FAIL ---------------------------------------------------------------------------------------
    /// The event hook of PUBLISH: a node's routes for `artifact` are committed. When the artifact has declared rows the reconcile derives the
    /// flip at once; the sweep derives it again if the draft is refused or lost, and after a leader change.
    static ClusterModel onNodeRoutes(ClusterModel m, Ctx ctx, Artifact artifact) {
        return m.declaredByArtifact.containsKey(artifact)
               ? Reconcile.request(m, ctx)
               : m;
    }

    /// W3: every `Declared` or `Published` row of `artifact` becomes `Failed(reason)`, each mutation expecting the row as the mirror holds it. The
    /// mutations join the artifact's FAILURE transaction (`Outbox.artifactDraft`), so the judgment and the rows commit or are refused together.
    static ClusterModel fail(ClusterModel m, Artifact artifact, String reason) {
        Idx.members(m.declaredByArtifact, artifact)
           .toList()
           .stream()
           .sorted(Comparator.comparing(DeclaredRouteKey::asString))
           .flatMap(key -> failure(m, key, reason).stream())
           .forEach(mutation -> Outbox.artifact(m,
                                                artifact,
                                                intent -> intent.withExtra(mutation)));

        return m;
    }

    private static Option<KVCommand.Mutation<AetherKey, AetherValue>> failure(ClusterModel m,
                                                                              DeclaredRouteKey key,
                                                                              String reason) {
        return m.declaredRoutes.get(key)
                               .filter(row -> !(row.state() instanceof RouteState.Failed))
                               .map(row -> new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                          Option.some((AetherValue) row),
                                                                                          Option.some(restated(row,
                                                                                                               RouteState.Failed.failed(reason)))));
    }

    /// The row at its own owner and attemptId in `state`.
    private static AetherValue restated(DeclaredRouteValue row, RouteState state) {
        return DeclaredRouteValue.declaredRouteValue(row.blueprintId(), row.attemptId(), state);
    }

    // -- W1b: the re-drive ------------------------------------------------------------------------------------------
    /// Reconcile step 1b: [#reconcile] for every blueprint that is committed or still owns rows. A ROUTES draft that was lost with a leader's
    /// term, dropped by a suspension, refused or failed is not resubmitted by the outbox; this step derives it again from the mirror.
    static ClusterModel sweep(ClusterModel m, Ctx ctx) {
        Stream.concat(m.blueprints.keys().stream(),
                      m.declaredByOwner.keys().stream())
              .distinct()
              .sorted(Comparator.comparing(BlueprintId::asString))
              .forEach(id -> reconcile(m, ctx, id));

        return m;
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> difference(ClusterModel m, BlueprintId id) {
        return m.blueprints.get(id)
                           .fold(() -> removals(m,
                                                id,
                                                Set.of()),
                                 value -> value.registerOnly()
                                          ? List.<KVCommand.Mutation<AetherKey, AetherValue>> of()
                                          : declaredDifference(m, id, value));
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> declaredDifference(ClusterModel m,
                                                                                       BlueprintId id,
                                                                                       AppBlueprintValue value) {
        var wanted = wantedKeys(value);

        return Stream.concat(wanted.stream().flatMap(key -> declare(m,
                                                                    id,
                                                                    value.attemptId(),
                                                                    key).stream()),
                             removals(m,
                                      id,
                                      Set.copyOf(wanted)).stream())
                     .sorted(Comparator.comparing(mutation -> mutation.key()
                                                                      .asString()))
                     .toList();
    }

    private static List<DeclaredRouteKey> wantedKeys(AppBlueprintValue value) {
        return value.declaredRoutes()
                    .entrySet()
                    .stream()
                    .flatMap(entry -> keysOf(entry).stream())
                    .toList();
    }

    private static List<DeclaredRouteKey> keysOf(Map.Entry<Artifact, List<DeclaredRoute>> entry) {
        return entry.getValue()
                    .stream()
                    .flatMap(route -> keyOf(entry.getKey(),
                                            route).stream())
                    .toList();
    }

    /// The key text round-trips or the factory refuses; a route it refuses (a blank path, a method of non-letters) has no row. Design-out:
    /// `BlueprintService` takes the routes from slice topologies that already passed the route validators.
    private static Option<DeclaredRouteKey> keyOf(Artifact artifact, DeclaredRoute route) {
        return DeclaredRouteKey.declaredRouteKey(artifact,
                                                 route.httpMethod(),
                                                 route.pathTemplate())
                               .option();
    }

    /// A row is written for a declared route when none is committed, another blueprint owns it, or it is `Failed` for an earlier attempt of
    /// this blueprint. A `Declared` or `Published` row of an earlier attempt stays: its state still holds, and a `Declared` one is published
    /// when a node publishes routes for its artifact.
    private static Option<KVCommand.Mutation<AetherKey, AetherValue>> declare(ClusterModel m,
                                                                              BlueprintId id,
                                                                              String attemptId,
                                                                              DeclaredRouteKey key) {
        var committed = m.declaredRoutes.get(key);

        return committed.map(row -> current(row, id, attemptId))
                        .or(false)
               ? publish(m, key)
               : Option.some(new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                            committed.map(row -> (AetherValue) row),
                                                                            Option.some(declared(id, attemptId))));
    }

    /// W2: the committed `Declared` row of a key whose artifact some node publishes routes for becomes `Published` at its own attemptId. The
    /// evidence is the artifact's routes in the mirror (the entry carries no attemptId and its route spelling differs, so there is no
    /// route-level match); the artifact carries its version, so an earlier version's routes never publish this one. The mutation expects the
    /// committed `Declared` row and the draft carries the owner witness, so a flip derived for an earlier attempt is refused. A `Failed` row
    /// is never flipped.
    private static Option<KVCommand.Mutation<AetherKey, AetherValue>> publish(ClusterModel m, DeclaredRouteKey key) {
        return m.declaredRoutes.get(key)
                               .filter(row -> row.state() instanceof RouteState.Declared)
                               .filter(_ -> m.routesByArtifact.containsKey(key.artifact()))
                               .map(row -> new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                          Option.some((AetherValue) row),
                                                                                          Option.some(restated(row,
                                                                                                               RouteState.Published.published()))));
    }

    private static boolean current(DeclaredRouteValue row, BlueprintId id, String attemptId) {
        return owns(row, id) && (row.attemptId()
                                    .equals(attemptId) || !(row.state() instanceof RouteState.Failed));
    }

    private static AetherValue declared(BlueprintId id, String attemptId) {
        return DeclaredRouteValue.declaredRouteValue(id.asString(), attemptId, RouteState.Declared.declared());
    }

    /// The removal of every row `id` owns that no declared route names, each at its committed value.
    private static List<KVCommand.Mutation<AetherKey, AetherValue>> removals(ClusterModel m,
                                                                             BlueprintId id,
                                                                             Set<DeclaredRouteKey> wanted) {
        return Idx.members(m.declaredByOwner, id)
                  .toList()
                  .stream()
                  .flatMap(artifact -> Idx.members(m.declaredByArtifact, artifact)
                                          .toList()
                                          .stream())
                  .filter(key -> !wanted.contains(key))
                  .flatMap(key -> removal(m, id, key).stream())
                  .sorted(Comparator.comparing(mutation -> mutation.key()
                                                                   .asString()))
                  .toList();
    }

    private static Option<KVCommand.Mutation<AetherKey, AetherValue>> removal(ClusterModel m,
                                                                              BlueprintId id,
                                                                              DeclaredRouteKey key) {
        return m.declaredRoutes.get(key)
                               .filter(row -> owns(row, id))
                               .map(row -> new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                          Option.some((AetherValue) row),
                                                                                          Option.none()));
    }

    private static boolean owns(DeclaredRouteValue row, BlueprintId id) {
        return row.blueprintId()
                  .equals(id.asString());
    }

    private static ClusterModel submit(ClusterModel m,
                                       BlueprintId id,
                                       List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
        return mutations.isEmpty()
               ? m
               : Outbox.direct(m,
                               TxDraft.draft(SubmitKind.ROUTES,
                                             mutations.getFirst().key(),
                                             mutations)
                                      .withGuards(List.of(Blueprints.ownerWitness(m, id))));
    }

    /// A ROUTES draft of `id` is queued in the outbox or submitted and unsettled: every such draft carries the witness on `id`'s blueprint row.
    private static boolean inFlight(ClusterModel m, BlueprintId id) {
        var witnessed = new AppBlueprintKey(id);

        return Stream.concat(m.outbox.stream(),
                             m.submitted.values().stream().flatMap(submission -> submission.drafts()
                                                                                           .stream()))
                     .filter(draft -> draft.kind() == SubmitKind.ROUTES)
                     .anyMatch(draft -> draft.guards()
                                             .stream()
                                             .anyMatch(guard -> guard.key()
                                                                     .equals(witnessed)));
    }

    record unused() implements Routes {}
}
