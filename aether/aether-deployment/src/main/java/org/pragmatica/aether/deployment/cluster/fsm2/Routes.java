// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
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
///
/// A declared row speaks only for its owner blueprint's current attemptId (#3836). [#standing] is the one place that compares a row's attemptId
/// with the owner's: the gate, DECLARE, PUBLISH and FAIL all ask it, and every row any of them writes carries the owner's current attemptId.
public sealed interface Routes {
    /// Where a committed row stands against the blueprint that owns it. `STALE` is a row of another owner or of an earlier attempt, whatever its
    /// state: it speaks for nobody until it is re-declared at the current attempt. The other three are the row's state at the current attempt.
    enum Standing {
        DECLARED,
        PUBLISHED,
        FAILED,
        STALE
    }

    // -- W1, W4: the diff against the committed blueprint ----------------------------------------------------------
    /// Draft the difference between the routes blueprint `id` declares and the rows it owns: a row for each declared route that has none (or
    /// whose row another blueprint owns, or whose row is of an earlier attempt, in any state, which becomes `Declared` on the new attemptId), a
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

    /// W3: every row of `artifact` not yet `Failed` at its owner's current attempt becomes `Failed(reason)` at that attempt, each mutation
    /// expecting the row as the mirror holds it. The mutations join the artifact's FAILURE transaction (`Outbox.artifactDraft`), so the judgment
    /// and the rows commit or are refused together.
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
                               .flatMap(row -> failure(m, key, row, reason));
    }

    /// The owner is the blueprint the row names: a row whose owner is gone is removed by the next reconcile, so there is no attempt to fail it at.
    private static Option<KVCommand.Mutation<AetherKey, AetherValue>> failure(ClusterModel m,
                                                                              DeclaredRouteKey key,
                                                                              DeclaredRouteValue row,
                                                                              String reason) {
        return BlueprintId.blueprintId(row.blueprintId())
                          .option()
                          .flatMap(id -> failure(m, key, row, id, reason));
    }

    private static Option<KVCommand.Mutation<AetherKey, AetherValue>> failure(ClusterModel m,
                                                                              DeclaredRouteKey key,
                                                                              DeclaredRouteValue row,
                                                                              BlueprintId id,
                                                                              String reason) {
        return m.blueprints.get(id)
                           .filter(owner -> standing(row, id, owner) != Standing.FAILED)
                           .map(owner -> replacement(key,
                                                     row,
                                                     written(id,
                                                             owner,
                                                             RouteState.Failed.failed(reason))));
    }

    /// The mutation that replaces the committed `row` with `value`.
    private static KVCommand.Mutation<AetherKey, AetherValue> replacement(DeclaredRouteKey key,
                                                                          DeclaredRouteValue row,
                                                                          AetherValue value) {
        return new KVCommand.Mutation<>(key, Option.some((AetherValue) row), Option.some(value));
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

        return Stream.concat(wanted.stream().flatMap(key -> declare(m, id, value, key).stream()),
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

    /// A row is written for a declared route when none is committed or its standing is `STALE`: another blueprint owns it, or it is of an earlier
    /// attempt of this blueprint, whatever its state. A row at the current attempt stays as it is: `Declared` is published when a node publishes
    /// routes for its artifact, `Published` holds, and `Failed` stays `Failed` until the next attempt.
    private static Option<KVCommand.Mutation<AetherKey, AetherValue>> declare(ClusterModel m,
                                                                              BlueprintId id,
                                                                              AppBlueprintValue owner,
                                                                              DeclaredRouteKey key) {
        var committed = m.declaredRoutes.get(key);

        return committed.filter(row -> standing(row, id, owner) != Standing.STALE)
                        .fold(() -> Option.some(declaration(key, committed, id, owner)),
                              row -> publish(m, id, owner, key, row));
    }

    /// The mutation that writes `key`'s row `Declared` at the owner's current attemptId over what is committed (nothing, or a `STALE` row).
    private static KVCommand.Mutation<AetherKey, AetherValue> declaration(DeclaredRouteKey key,
                                                                          Option<DeclaredRouteValue> committed,
                                                                          BlueprintId id,
                                                                          AppBlueprintValue owner) {
        return new KVCommand.Mutation<>(key,
                                        committed.map(row -> (AetherValue) row),
                                        Option.some(written(id, owner, RouteState.Declared.declared())));
    }

    /// W2: the committed `Declared` row of a key whose artifact some node publishes routes for becomes `Published` at the owner's current
    /// attemptId. The evidence is the artifact's routes in the mirror (the entry carries no attemptId and its route spelling differs, so there is
    /// no route-level match); the artifact carries its version, so an earlier version's routes never publish this one. The mutation expects the
    /// committed row and the draft carries the owner witness, so a flip derived for an earlier attempt is refused; a row of an earlier attempt
    /// is `STALE` and is re-declared instead. A `Failed` row is never flipped.
    private static Option<KVCommand.Mutation<AetherKey, AetherValue>> publish(ClusterModel m,
                                                                              BlueprintId id,
                                                                              AppBlueprintValue owner,
                                                                              DeclaredRouteKey key,
                                                                              DeclaredRouteValue row) {
        return standing(row, id, owner) == Standing.DECLARED && m.routesByArtifact.containsKey(key.artifact())
               ? Option.some(replacement(key, row, written(id, owner, RouteState.Published.published())))
               : Option.none();
    }

    // -- the goal gate ---------------------------------------------------------------------------------------------
    /// The goal gate of #3389 A2 part 2: every declared row of `artifact` is in the mirror, at the attempt its owner blueprint is committed at, and
    /// `Declared` or `Published` ([#standing]). An artifact with no owner in the mirror, an owner that declares no route for it, or a
    /// `registerOnly` owner has no rows to wait for, so its goal is never held. A row of an earlier attempt, in any state, is not committed: the
    /// new attempt's DECLARE re-declares it first. The gate decides on what [GateReads] read, the same reading [#goalWitnesses] witnesses.
    static boolean rowsCommitted(ClusterModel m, Artifact artifact) {
        var reads = GateReads.gateReads(mirror(m), artifact);

        return reads.owner()
                    .flatMap(id -> reads.blueprint()
                                        .map(value -> allCurrent(reads, id, value)))
                    .or(true);
    }

    /// #3836: the witnesses of a goal transaction, one per fact the goal gate reads and at the value `read` gives for it, in the order the gate reads
    /// them: the SliceTarget row (the owner id), the owner's `AppBlueprintValue` and the declared rows. A fact that changes or goes between the goal's
    /// derivation and its commit refuses the transaction. The list is derived from [GateReads], never written beside the gate, so the gate and its
    /// witnesses cannot drift; a resubmitted pair derives it again over what its transaction recorded ([Outbox]'s `recorded`) and keeps every kind.
    static List<KVCommand.ReadWitness<AetherKey>> goalWitnesses(Function<AetherKey, Option<AetherValue>> read,
                                                                Artifact artifact) {
        var reads = GateReads.gateReads(read, artifact);

        return reads.keys()
                    .stream()
                    .map(key -> new KVCommand.ReadWitness<AetherKey>(key,
                                                                     read.apply(key).map(value -> (Object) value)))
                    .toList();
    }

    /// The mirror as the one place the gate reads from.
    static Function<AetherKey, Option<AetherValue>> mirror(ClusterModel m) {
        return key -> Outbox.mirrored(m, key);
    }

    /// What the goal gate of one artifact read: the owner its SliceTarget row names, that owner's blueprint, and the declared rows of the artifact
    /// the blueprint lists. Each is read once through `read`; the gate and the witnesses both take it from here.
    record GateReads(AetherKey targetKey,
                     Function<AetherKey, Option<AetherValue>> read,
                     Option<BlueprintId> owner,
                     Option<AppBlueprintValue> blueprint,
                     List<DeclaredRouteKey> rows) {
        static GateReads gateReads(Function<AetherKey, Option<AetherValue>> read, Artifact artifact) {
            var targetKey = new AetherKey.SliceTargetKey(artifact.base());
            var owner = typed(read.apply(targetKey), SliceTargetValue.class).flatMap(SliceTargetValue::owningBlueprint);
            var blueprint = owner.flatMap(id -> typed(read.apply(AppBlueprintKey.appBlueprintKey(id)),
                                                      AppBlueprintValue.class));

            return new GateReads(targetKey,
                                 read,
                                 owner,
                                 blueprint,
                                 blueprint.map(value -> declaredKeys(value, artifact)).or(List.of()));
        }

        /// The keys read, in reading order: the target row, the owner's blueprint (when the target names one) and the rows.
        List<AetherKey> keys() {
            return Stream.concat(Stream.concat(Stream.of(targetKey),
                                               owner.stream().<AetherKey> map(id -> AppBlueprintKey.appBlueprintKey(id))),
                                 rows.stream())
                         .toList();
        }
    }

    private static <T extends AetherValue> Option<T> typed(Option<AetherValue> value, Class<T> type) {
        return value.filter(type::isInstance)
                    .map(type::cast);
    }

    private static boolean allCurrent(GateReads reads, BlueprintId id, AppBlueprintValue value) {
        return reads.rows()
                    .stream()
                    .allMatch(key -> typed(reads.read().apply(key),
                                           DeclaredRouteValue.class).map(row -> standing(row, id, value))
                                          .map(Routes::counts)
                                          .or(false));
    }

    private static List<DeclaredRouteKey> declaredKeys(AppBlueprintValue value, Artifact artifact) {
        return value.registerOnly()
               ? List.of()
               : keysOf(Map.entry(artifact,
                                  value.declaredRoutes().getOrDefault(artifact, List.of())));
    }

    /// The one comparison of a row's attemptId with its owner's. A row of another owner or of an earlier attempt is `STALE`, whatever its state.
    private static Standing standing(DeclaredRouteValue row, BlueprintId id, AppBlueprintValue owner) {
        return owns(row, id) && row.attemptId()
                                   .equals(owner.attemptId())
               ? stateOf(row.state())
               : Standing.STALE;
    }

    /// The gate counts a row only at the current attempt and `Declared` or `Published`.
    private static boolean counts(Standing standing) {
        return standing == Standing.DECLARED || standing == Standing.PUBLISHED;
    }

    private static Standing stateOf(RouteState state) {
        return switch (state) {
            case RouteState.Declared _ -> Standing.DECLARED;
            case RouteState.Published _ -> Standing.PUBLISHED;
            case RouteState.Failed _ -> Standing.FAILED;
        };
    }

    /// The row every writer produces: `id`'s, in `state`, at the attemptId its blueprint is committed at.
    private static AetherValue written(BlueprintId id, AppBlueprintValue owner, RouteState state) {
        return DeclaredRouteValue.declaredRouteValue(id.asString(), owner.attemptId(), state);
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
