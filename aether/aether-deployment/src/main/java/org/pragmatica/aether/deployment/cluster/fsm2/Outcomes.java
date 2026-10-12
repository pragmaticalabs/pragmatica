// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.ArtifactIntent;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;


/// The deployment verdict and what a fatal failure does to an apply (spec R-2.1, R-4.1.5, R-4.1.6, R-3.3.6).
///
/// The verdict is **durable**: SUCCEEDED is written together with the removal of the apply record in one fenced
/// transaction, on ACTIVE and on every tick (NT-23), so a lost submission is repeated until it lands. A fatal failure
/// under ALL_OR_NOTHING rolls back each open apply that declares the artifact, unless the artifact is in a rolling
/// update (B-055); under BEST_EFFORT it merges the failure into the outcome record (B-056).
public sealed interface Outcomes {
    // -- the apply ------------------------------------------------------------------------------------------------
    /// An apply is outstanding for `artifact` when a non-registerOnly blueprint that declares it has an open apply of its
    /// committed attempt (the discriminator of #922, R-2.7).
    static boolean applyOutstanding(ClusterModel m, Artifact artifact) {
        return openApplies(m, artifact).stream()
                          .findAny()
                          .isPresent();
    }

    private static List<BlueprintId> openApplies(ClusterModel m, Artifact artifact) {
        return Idx.members(m.blueprintsByArtifact, artifact)
                  .toList()
                  .stream()
                  .filter(id -> m.blueprints.get(id)
                                            .map(value -> !value.registerOnly())
                                            .or(false))
                  .filter(id -> m.applies.get(id)
                                         .map(apply -> Blueprints.isOpen(m, id, apply) && attemptOf(m, id).equals(apply.attemptId()))
                                         .or(false))
                  .sorted(Comparator.comparing(BlueprintId::asString))
                  .toList();
    }

    static String attemptOf(ClusterModel m, BlueprintId id) {
        return m.blueprints.get(id)
                           .map(AppBlueprintValue::attemptId)
                           .or(AppBlueprintValue.NO_ATTEMPT);
    }

    // -- SUCCEEDED (R-4.1.5, NT-23) --------------------------------------------------------------------------------
    /// C10b: an instance of `artifact` is ACTIVE; every open apply that declares it is re-examined.
    static ClusterModel repair(ClusterModel m, Ctx ctx, Artifact artifact) {
        openApplies(m, artifact).forEach(id -> succeedIfComplete(m, ctx, id));

        return m;
    }

    /// Step 9: every open apply is re-examined on every tick, so a SUCCEEDED that was lost is submitted again.
    static ClusterModel sweep(ClusterModel m, Ctx ctx) {
        m.applies.entries()
                 .stream()
                 .sorted(Comparator.comparing(entry -> entry.getKey()
                                                            .asString()))
                 .filter(entry -> Blueprints.isOpen(m,
                                                    entry.getKey(),
                                                    entry.getValue()))
                 .filter(entry -> attemptOf(m,
                                            entry.getKey()).equals(entry.getValue().attemptId()))
                 .forEach(entry -> succeedIfComplete(m,
                                                     ctx,
                                                     entry.getKey()));

        return m;
    }

    /// R-2.1: deployed when every declared, non-registerOnly slice has at least one counted ACTIVE instance.
    static boolean isDeployed(ClusterModel m, BlueprintId id) {
        return m.blueprints.get(id)
                           .filter(value -> !value.registerOnly())
                           .map(value -> value.blueprint()
                                              .loadOrder()
                                              .stream()
                                              .allMatch(slice -> Rows.activeCount(m,
                                                                                  slice.artifact()) > 0))
                           .or(false);
    }

    private static void succeedIfComplete(ClusterModel m, Ctx ctx, BlueprintId id) {
        var apply = m.applies.get(id);

        if (apply.isEmpty() || !isDeployed(m, id) || succeededInFlight(m, id)) {
            return;
        }

        var committed = m.outcomes.get(id);
        var outcome = DeploymentOutcomeValue.succeeded(ctx.nowMs(),
                                                       successorVersion(committed),
                                                       apply.map(DeploymentApplyValue::attemptId).or(""));
        var outcomeKey = new DeploymentOutcomeKey(id);
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        mutations.add(new KVCommand.Mutation<>(outcomeKey,
                                               committed.map(v -> (AetherValue) v),
                                               Option.some((AetherValue) outcome)));
        apply.onPresent(value -> mutations.add(new KVCommand.Mutation<>(new DeploymentApplyKey(id),
                                                                        Option.some((AetherValue) value),
                                                                        Option.none())));
        resetRetries(m, id);
        ctx.emit(new ClusterEffect.Log("deployment " + id.asString() + " deployed: " + coverage(m, id)));
        Outbox.direct(m, TxDraft.draft(SubmitKind.SUCCEEDED, outcomeKey, mutations));
    }

    /// R-4.2.3: the retry counts of the declared slices ride the verdict.
    private static void resetRetries(ClusterModel m, BlueprintId id) {
        m.blueprints.get(id)
                    .map(value -> value.blueprint()
                                       .loadOrder())
                    .or(List.of())
                    .forEach(slice -> m.judgments.get(slice.artifact())
                                                 .filter(judgment -> !judgment.retries()
                                                                              .isEmpty())
                                                 .onPresent(judgment -> judgment.retries()
                                                                                .keySet()
                                                                                .stream()
                                                                                .sorted()
                                                                                .forEach(node -> Outbox.artifact(m,
                                                                                                                 slice.artifact(),
                                                                                                                 intent -> intent.withOp(new JudgmentOp.ResetRetries(node))))));
    }

    private static boolean succeededInFlight(ClusterModel m, BlueprintId id) {
        var key = new DeploymentOutcomeKey(id);

        return m.submitted.values()
                          .stream()
                          .flatMap(submitted -> submitted.drafts()
                                                         .stream())
                          .anyMatch(draft -> draft.kind() == SubmitKind.SUCCEEDED && draft.key()
                                                                                          .equals(key)) || m.outbox.stream()
                                                                                                                   .anyMatch(draft -> draft.kind() == SubmitKind.SUCCEEDED && draft.key()
                                                                                                                                                                                   .equals(key));
    }

    /// "n of m" (R-2.1): the verdict states the coverage and never implies every instance is ACTIVE.
    static String coverage(ClusterModel m, BlueprintId id) {
        return m.blueprints.get(id)
                           .map(value -> value.blueprint()
                                              .loadOrder()
                                              .stream()
                                              .map(slice -> slice.artifact()
                                                                 .asString()
                                                           + " " + Rows.activeCount(m,
                                                                                    slice.artifact())
                                                           + " of " + slice.instances())
                                              .toList()
                                              .toString())
                           .or("");
    }

    static long successorVersion(Option<DeploymentOutcomeValue> committed) {
        return committed.map(outcome -> outcome.outcomeVersion() + 1)
                        .or(DeploymentOutcomeValue.FIRST_VERSION);
    }

    // -- rollback (R-4.1.6, NT-25, NT-26) ---------------------------------------------------------------------------
    /// A fatal failure of `artifact` under ALL_OR_NOTHING: each open apply that declares it is rolled back in one
    /// attempt-fenced transaction, unless the artifact is in a rolling update (B-055).
    static ClusterModel rollbackFor(ClusterModel m, Ctx ctx, Artifact artifact, String cause) {
        if (m.routings.containsKey(artifact.base())) {
            return m;
        }

        openApplies(m, artifact).forEach(id -> rollback(m, ctx, id, artifact, cause));

        return m;
    }

    private static void rollback(ClusterModel m, Ctx ctx, BlueprintId id, Artifact failed, String cause) {
        if (rollbackInFlight(m, id)) {
            return;
        }

        var blueprint = m.blueprints.get(id);
        var apply = m.applies.get(id);

        if (blueprint.isEmpty() || apply.isEmpty()) {
            return;
        }

        var committed = m.outcomes.get(id);
        var attempt = apply.map(DeploymentApplyValue::attemptId).or("");
        var outcome = DeploymentOutcomeValue.rolledBack(List.of(failed.asString()),
                                                        cause,
                                                        ctx.nowMs(),
                                                        successorVersion(committed),
                                                        attempt);
        var outcomeKey = new DeploymentOutcomeKey(id);
        var previous = apply.flatMap(DeploymentApplyValue::previous);
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        mutations.addAll(restoration(m, id, blueprint, previous, attempt));
        mutations.add(new KVCommand.Mutation<>(outcomeKey,
                                               committed.map(v -> (AetherValue) v),
                                               Option.some((AetherValue) outcome)));
        apply.onPresent(value -> mutations.add(new KVCommand.Mutation<>(new DeploymentApplyKey(id),
                                                                        Option.some((AetherValue) value),
                                                                        Option.none())));
        Outbox.direct(m, TxDraft.draft(SubmitKind.ROLLBACK, new AppBlueprintKey(id), mutations));
    }

    /// R-4.1.2: `previous` is the same-id value the attempt overwrote, or the blueprint of the prior committed version
    /// (a different id), or none, which means delete. A same-id restore carries the rolled-back attempt's id so the
    /// ROLLED_BACK terminal attributes to the committed blueprint and closes it (#972); a different-id restore keeps its own
    /// committed attempt id and the rolled-back blueprint is removed. Targets of slices the attempt added go with it.
    private static List<KVCommand.Mutation<AetherKey, AetherValue>> restoration(ClusterModel m,
                                                                                BlueprintId id,
                                                                                Option<AppBlueprintValue> rolledBack,
                                                                                Option<AppBlueprintValue> previous,
                                                                                String attempt) {
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();
        var key = new AppBlueprintKey(id);

        previous.fold(() -> mutations.add(new KVCommand.Mutation<>(key,
                                                                   rolledBack.map(v -> (AetherValue) v),
                                                                   Option.none())),
                      restored -> restore(m, id, rolledBack, restored, attempt, mutations));

        return mutations;
    }

    private static boolean restore(ClusterModel m,
                                   BlueprintId id,
                                   Option<AppBlueprintValue> rolledBack,
                                   AppBlueprintValue restored,
                                   String attempt,
                                   List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
        var restoredId = restored.blueprint().id();

        if (restoredId.equals(id)) {
            var value = new AppBlueprintValue(restored.blueprint(), restored.registerOnly(), attempt);

            mutations.add(new KVCommand.Mutation<>(new AppBlueprintKey(id),
                                                   rolledBack.map(v -> (AetherValue) v),
                                                   Option.some((AetherValue) value)));
        } else {
            mutations.add(new KVCommand.Mutation<>(new AppBlueprintKey(restoredId),
                                                   m.blueprints.get(restoredId).map(v -> (AetherValue) v),
                                                   Option.some((AetherValue) restored)));
            mutations.add(new KVCommand.Mutation<>(new AppBlueprintKey(id),
                                                   rolledBack.map(v -> (AetherValue) v),
                                                   Option.none()));
        }

        rolledBack.onPresent(rolled -> mutations.addAll(removedTargets(m, rolled, restored)));

        return true;
    }

    /// The slices the rolled-back attempt added (their base is not declared by the restored blueprint): their targets go.
    private static List<KVCommand.Mutation<AetherKey, AetherValue>> removedTargets(ClusterModel m,
                                                                                   AppBlueprintValue rolledBack,
                                                                                   AppBlueprintValue restored) {
        var kept = restored.blueprint().loadOrder().stream().map(slice -> slice.artifact()
                                                                               .base()).toList();

        return rolledBack.blueprint()
                         .loadOrder()
                         .stream()
                         .map(slice -> slice.artifact()
                                            .base())
                         .filter(base -> !kept.contains(base))
                         .distinct()
                         .flatMap(base -> m.targets.get(base)
                                                   .stream()
                                                   .map(target -> removal(base, target)))
                         .toList();
    }

    private static KVCommand.Mutation<AetherKey, AetherValue> removal(org.pragmatica.aether.artifact.ArtifactBase base,
                                                                      AetherValue.SliceTargetValue target) {
        return new KVCommand.Mutation<>(new SliceTargetKey(base), Option.some((AetherValue) target), Option.none());
    }

    private static boolean rollbackInFlight(ClusterModel m, BlueprintId id) {
        var key = new AppBlueprintKey(id);

        return m.submitted.values()
                          .stream()
                          .flatMap(submitted -> submitted.drafts()
                                                         .stream())
                          .anyMatch(draft -> draft.kind() == SubmitKind.ROLLBACK && draft.key()
                                                                                         .equals(key)) || m.outbox.stream()
                                                                                                                  .anyMatch(draft -> draft.kind() == SubmitKind.ROLLBACK && draft.key()
                                                                                                                                                                                 .equals(key));
    }

    // -- BEST_EFFORT (B-056) ----------------------------------------------------------------------------------------
    /// BEST_EFFORT: the failure joins the FAILED outcome record of the owning blueprint, in the artifact's own
    /// transaction. The failing slices of one drain merge into one record.
    static ClusterModel recordBestEffortFailure(ClusterModel m, Ctx ctx, Artifact artifact, String cause) {
        outcomeOwner(m, artifact).onPresent(id -> mergeFailure(m, ctx, id, artifact, cause));

        return m;
    }

    /// The owning blueprint of a failure: the blueprint mirror first, the committed target's owner when the mirror cannot
    /// answer (#805).
    private static Option<BlueprintId> outcomeOwner(ClusterModel m, Artifact artifact) {
        return Idx.members(m.blueprintsByArtifact, artifact)
                  .toList()
                  .stream()
                  .sorted(Comparator.comparing(BlueprintId::asString))
                  .findFirst()
                  .map(Option::some)
                  .orElseGet(() -> Schemas.owner(m, artifact));
    }

    private static void mergeFailure(ClusterModel m, Ctx ctx, BlueprintId id, Artifact artifact, String cause) {
        var key = new DeploymentOutcomeKey(id);
        var committed = m.outcomes.get(id);
        var attempt = attemptOf(m, id);
        var slices = new ArrayList<String>(committed.filter(outcome -> outcome.describesAttempt(attempt))
                                                    .map(DeploymentOutcomeValue::failingSlices)
                                                    .or(List.of()));

        slices.addAll(drainFailures(m, key));
        if (!slices.contains(artifact.asString())) {
            slices.add(artifact.asString());
        }

        var value = DeploymentOutcomeValue.failed(slices, cause, ctx.nowMs(), successorVersion(committed), attempt);
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                      committed.map(v -> (AetherValue) v),
                                                                      Option.some((AetherValue) value));

        Outbox.artifact(m, artifact, intent -> intent.withExtra(mutation));
    }

    /// The failing slices already merged into this drain's outcome mutation (removed here, re-added merged).
    private static List<String> drainFailures(ClusterModel m, AetherKey key) {
        var merged = new ArrayList<String>();

        for (var entry : m.artifactIntents.entries()) {
            var kept = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

            for (var extra : entry.getValue().extras()) {
                if (extra.key().equals(key)) {
                    extra.replacement()
                         .onPresent(value -> merged.addAll(((DeploymentOutcomeValue) value).failingSlices()));
                } else {
                    kept.add(extra);
                }
            }

            if (kept.size() != entry.getValue().extras().size()) {
                m.artifactIntents = m.artifactIntents.put(entry.getKey(),
                                                          new ArtifactIntent(entry.getValue().ops(),
                                                                             entry.getValue().goals(),
                                                                             kept));
            }
        }

        return merged;
    }

    record unused() implements Outcomes {}
}
