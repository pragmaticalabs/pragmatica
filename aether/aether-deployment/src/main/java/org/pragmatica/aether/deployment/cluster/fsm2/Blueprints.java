// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.AwaitedTarget;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.GoalMode;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.deployment.cluster.fsm2.TimerId.Kind;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;


/// Blueprint, target and routing intake (spec C2 to C9), the committed-target predicate (R-3.3.5, `CommittedSliceTarget`),
/// and the two reconcile steps that guard the target writes: the awaited-target watch (step 1, #1109) and the intake
/// re-drive (step 2, R-4.1.4).
public sealed interface Blueprints {
    String PLACEMENT_DEFAULT = "CORE_ONLY";
    String AWAITING_TARGET = "slice-start-awaiting-target";
    String DEFERRAL_ENDED = "slice-start-deferral-ended";

    // -- the committed-target predicate ----------------------------------------------------------------------------
    /// `CommittedSliceTarget.permits`: the committed target names this exact version, or, during a rolling update, a
    /// `VersionRoutingKey` names it as the old or the new version. An absent target permits nothing.
    static boolean permits(ClusterModel m, Artifact artifact) {
        var base = artifact.base();
        var version = artifact.version();

        return m.targets.get(base)
                        .map(target -> target.currentVersion()
                                             .equals(version) || routingNames(m, base, version))
                        .or(false);
    }

    private static boolean routingNames(ClusterModel m,
                                        ArtifactBase base,
                                        org.pragmatica.aether.artifact.Version version) {
        return m.routings.get(base)
                         .map(routing -> routing.oldVersion()
                                                .equals(version) || routing.newVersion()
                                                                           .equals(version))
                         .or(false);
    }

    // -- C2, C3, C4: AppBlueprintPut -------------------------------------------------------------------------------
    static ClusterModel onBlueprintPut(ClusterModel m, Ctx ctx, BlueprintId id, AppBlueprintValue value) {
        var expanded = value.blueprint();

        if (conflictingOwner(m, id, expanded).isPresent()) {
            return rejected(m, ctx, id, expanded);
        }

        Indexer.indexBlueprint(m, id, value);
        if (value.registerOnly()) {
            return m;
        }

        return declare(m, ctx, id, expanded, true);
    }

    /// C2: another blueprint (of a different base) already owns a slice of this one.
    static Option<BlueprintId> conflictingOwner(ClusterModel m, BlueprintId id, ExpandedBlueprint expanded) {
        return expanded.loadOrder()
                       .stream()
                       .flatMap(slice -> m.ownerByBase.get(slice.artifact().base())
                                                      .stream())
                       .filter(owner -> !owner.base()
                                              .equals(id.base()))
                       .findFirst()
                       .map(Option::some)
                       .orElse(Option.none());
    }

    private static ClusterModel rejected(ClusterModel m, Ctx ctx, BlueprintId id, ExpandedBlueprint expanded) {
        ctx.warn("blueprint-rejected",
                 id.asString(),
                 "Blueprint '" + id.asString()
                + "' rejected: a slice is already owned by blueprint '" + conflictingOwner(m, id, expanded).map(BlueprintId::asString)
                                                                                          .or("")
                + "'");

        return m;
    }

    /// C4: the target of every slice, in one transaction, plus the judgment removes of a republish (NT-27). A re-drive
    /// (`republish == false`) repeats the targets and leaves the judgments alone: a permanent failure survives it.
    private static ClusterModel declare(ClusterModel m,
                                        Ctx ctx,
                                        BlueprintId id,
                                        ExpandedBlueprint expanded,
                                        boolean republish) {
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        for (var slice : expanded.loadOrder()) {
            var target = declaredTarget(m, ctx, id, slice);
            var base = slice.artifact().base();

            m.pendingTargetPuts = m.pendingTargetPuts.put(base, target);
            mutations.add(targetPut(m, base, target));
            if (republish) {
                clearJudgment(m, slice.artifact());
            }
        }

        if (!mutations.isEmpty()) {
            Outbox.direct(m,
                          TxDraft.draft(SubmitKind.TARGETS,
                                        mutations.getFirst().key(),
                                        mutations));
        }

        return m;
    }

    private static void clearJudgment(ClusterModel m, Artifact artifact) {
        if (m.judgments.containsKey(artifact)) {
            Outbox.artifact(m,
                            artifact,
                            intent -> intent.withOp(new JudgmentOp.ClearAll()));
        }
    }

    private static KVCommand.Mutation<AetherKey, AetherValue> targetPut(ClusterModel m,
                                                                        ArtifactBase base,
                                                                        SliceTargetValue target) {
        return new KVCommand.Mutation<>(new SliceTargetKey(base),
                                        m.targets.get(base).map(v -> (AetherValue) v),
                                        Option.some((AetherValue) target));
    }

    /// The target a publish proposes: the committed value re-declared by the blueprint when one exists, so the placement
    /// the blueprint cannot express and the scale the slice runs at survive (#983); a fresh value with the declared
    /// count and the default placement otherwise. Pure: `updatedAt` is the step's wall clock.
    static SliceTargetValue declaredTarget(ClusterModel m, Ctx ctx, BlueprintId owner, ResolvedSlice slice) {
        var artifact = slice.artifact();
        var some = Option.some(owner);

        return m.targets.get(artifact.base())
                        .map(committed -> redeclared(committed, ctx, artifact, slice, some))
                        .or(() -> new SliceTargetValue(artifact.version(),
                                                       slice.instances(),
                                                       slice.minAvailable(),
                                                       some,
                                                       PLACEMENT_DEFAULT,
                                                       ctx.nowMs(),
                                                       slice.maxInstances(),
                                                       slice.scaleUpThreshold(),
                                                       slice.scaleDownThreshold()));
    }

    /// The carried count is clamped into the new bounds `[minAvailable, maxInstances]`; where they contradict, the minimum wins.
    private static SliceTargetValue redeclared(SliceTargetValue committed,
                                               Ctx ctx,
                                               Artifact artifact,
                                               ResolvedSlice slice,
                                               Option<BlueprintId> owner) {
        var cappedAtMax = slice.maxInstances()
                               .map(max -> Math.min(committed.targetInstances(),
                                                    max))
                               .or(committed.targetInstances());

        return new SliceTargetValue(artifact.version(),
                                    Math.max(slice.minAvailable(), cappedAtMax),
                                    slice.minAvailable(),
                                    owner,
                                    committed.placement(),
                                    ctx.nowMs(),
                                    slice.maxInstances(),
                                    slice.scaleUpThreshold(),
                                    slice.scaleDownThreshold());
    }

    // -- C8, C9: AppBlueprintRemove --------------------------------------------------------------------------------
    static ClusterModel onBlueprintRemove(ClusterModel m, Ctx ctx, BlueprintId id) {
        return m.blueprints.get(id)
                           .fold(() -> m,
                                 removed -> removeKnown(m,
                                                        ctx,
                                                        id,
                                                        removed.blueprint()));
    }

    private static ClusterModel removeKnown(ClusterModel m, Ctx ctx, BlueprintId id, ExpandedBlueprint expanded) {
        var owned = ownedSlices(m, id, expanded);

        if (owned.stream().anyMatch(slice -> m.routings.containsKey(slice.artifact().base()))) {
            ctx.warn("blueprint-delete-refused",
                     id.asString(),
                     "Cannot delete blueprint '" + id.asString() + "': a rolling update is active");

            return m;
        }

        return removeOwned(m, ctx, id, owned);
    }

    private static List<ResolvedSlice> ownedSlices(ClusterModel m, BlueprintId id, ExpandedBlueprint expanded) {
        return expanded.loadOrder()
                       .stream()
                       .filter(slice -> m.ownerByBase.get(slice.artifact().base())
                                                     .map(id::equals)
                                                     .or(false))
                       .toList();
    }

    private static ClusterModel removeOwned(ClusterModel m, Ctx ctx, BlueprintId id, List<ResolvedSlice> owned) {
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        Indexer.removeBlueprint(m, id);
        for (var slice : owned) {
            var base = slice.artifact().base();

            m.pendingTargetPuts = m.pendingTargetPuts.remove(base);
            deallocateAll(m, ctx, base, "blueprint-removed");
            clearJudgment(m, slice.artifact());
            m.targets.get(base)
                     .onPresent(target -> mutations.add(new KVCommand.Mutation<>(new SliceTargetKey(base),
                                                                                 Option.some((AetherValue) target),
                                                                                 Option.none())));
        }

        m.applies.get(id)
                 .onPresent(apply -> mutations.add(new KVCommand.Mutation<>(new DeploymentApplyKey(id),
                                                                            Option.some((AetherValue) apply),
                                                                            Option.none())));
        if (!mutations.isEmpty()) {
            Outbox.direct(m,
                          TxDraft.draft(SubmitKind.BLUEPRINT_REMOVAL,
                                        mutations.getFirst().key(),
                                        mutations));
        }

        return Lifecycle.schedule(m,
                                  ctx,
                                  TimerId.timerId(Kind.RECONCILE, "blueprint-removed:" + id.asString()),
                                  5_000L);
    }

    // -- C5, C6: SliceTarget ---------------------------------------------------------------------------------------
    static ClusterModel onTargetPut(ClusterModel m, Ctx ctx, SliceTargetKey key, SliceTargetValue value) {
        var base = key.artifactBase();

        Indexer.indexTarget(m, key, value);
        m.pendingTargetPuts.get(base)
                           .filter(pending -> pending.currentVersion()
                                                     .equals(value.currentVersion()))
                           .onPresent(_ -> m.pendingTargetPuts = m.pendingTargetPuts.remove(base));
        endAwaited(m,
                   ctx,
                   base.withVersion(value.currentVersion()),
                   "the committed target now names it");
        if (!m.routings.containsKey(base)) {
            otherVersions(m, base, value.currentVersion()).forEach(old -> deallocate(m, ctx, old, "superseded-version"));
        }

        return Reconcile.request(m, ctx);
    }

    /// C6: drop the target, the view entries and the pending put (NT-11: nothing is resubmitted after a foreign remove).
    static ClusterModel onTargetRemove(ClusterModel m, Ctx ctx, SliceTargetKey key) {
        var base = key.artifactBase();

        Indexer.removeTarget(m, key);
        m.pendingTargetPuts = m.pendingTargetPuts.remove(base);
        Idx.members(m.versionsByBase, base)
           .toList()
           .forEach(artifact -> endAwaited(m, ctx, artifact, "the target was removed"));
        m.awaitedTargets.keys()
                        .stream()
                        .filter(artifact -> artifact.base()
                                                    .equals(base))
                        .forEach(artifact -> endAwaited(m, ctx, artifact, "the target was removed"));
        deallocateAll(m, ctx, base, "target-removed");

        return Reconcile.request(m, ctx);
    }

    // -- C7: VersionRouting ----------------------------------------------------------------------------------------
    static ClusterModel onRoutingPut(ClusterModel m, Ctx ctx, VersionRoutingKey key, VersionRoutingValue value) {
        Indexer.indexRouting(m, key, value);

        return Reconcile.request(m, ctx);
    }

    /// The rolling update is complete: every version of the base that is not the target's is deallocated (NT-12).
    static ClusterModel onRoutingRemove(ClusterModel m, Ctx ctx, VersionRoutingKey key) {
        var base = key.artifactBase();

        Indexer.removeRouting(m, key);
        m.targets.get(base)
                 .onPresent(target -> otherVersions(m,
                                                    base,
                                                    target.currentVersion()).forEach(old -> deallocate(m,
                                                                                                       ctx,
                                                                                                       old,
                                                                                                       "rolling-update-complete")));

        return Reconcile.request(m, ctx);
    }

    // -- deallocation ----------------------------------------------------------------------------------------------
    private static List<Artifact> otherVersions(ClusterModel m,
                                                ArtifactBase base,
                                                org.pragmatica.aether.artifact.Version keep) {
        return Idx.members(m.versionsByBase, base)
                  .toList()
                  .stream()
                  .filter(artifact -> !artifact.version()
                                               .equals(keep))
                  .sorted(Comparator.comparing(Artifact::asString))
                  .toList();
    }

    /// Every instance of every version of the base gets goal UNLOADED.
    static ClusterModel deallocateAll(ClusterModel m, Ctx ctx, ArtifactBase base, String why) {
        Idx.members(m.versionsByBase, base)
           .toList()
           .stream()
           .sorted(Comparator.comparing(Artifact::asString))
           .forEach(artifact -> deallocate(m, ctx, artifact, why));

        return m;
    }

    /// Goal UNLOADED, at a fresh generation, for every instance of `artifact` that is not already heading there.
    static ClusterModel deallocate(ClusterModel m, Ctx ctx, Artifact artifact, String why) {
        Rows.allInstances(m, artifact).forEach(pair -> Outbox.goal(m,
                                                                   pair,
                                                                   SliceGoal.UNLOADED,
                                                                   GoalMode.FRESH,
                                                                   Option.none(),
                                                                   why));

        return m;
    }

    // -- step 1: the awaited-target watch (#1109, B-036) ------------------------------------------------------------
    /// A target put that has not committed is watched. Once the artifact has waited for the bound, the operator is told
    /// once, and the put is re-submitted at most once per bound while the blueprint is still desired (6.2: 1 per 2 min).
    /// A put whose target committed, or whose blueprint is gone, is forgotten and the wait ends.
    static ClusterModel sweepAwaited(ClusterModel m, Ctx ctx) {
        var bound = ctx.settings().timing().awaitedTarget();

        m.pendingTargetPuts.entries()
                           .stream()
                           .sorted(Comparator.comparing(entry -> entry.getKey()
                                                                      .toString()))
                           .forEach(entry -> watch(m,
                                                   ctx,
                                                   entry.getKey(),
                                                   entry.getValue(),
                                                   bound));

        return m;
    }

    private static void watch(ClusterModel m, Ctx ctx, ArtifactBase base, SliceTargetValue pending, long bound) {
        var artifact = base.withVersion(pending.currentVersion());

        if (permits(m, artifact) || !m.blueprintsByArtifact.containsKey(artifact)) {
            endAwaited(m,
                       ctx,
                       artifact,
                       permits(m, artifact)
                       ? "the committed target now names it"
                       : "the blueprint is no longer desired");
            m.pendingTargetPuts = m.pendingTargetPuts.remove(base);

            return;
        }

        var existing = m.awaitedTargets.get(artifact).or(new AwaitedTarget(ctx.nowMs(), false, ctx.nowMs()));
        var due = ctx.nowMs() - existing.sinceMs() >= bound;
        var warn = due && !existing.warned();
        var resubmit = due && ctx.nowMs() - existing.lastResubmitMs() >= bound;

        m.awaitedTargets = m.awaitedTargets.put(artifact,
                                                new AwaitedTarget(existing.sinceMs(),
                                                                  existing.warned() || warn,
                                                                  resubmit
                                                                  ? ctx.nowMs()
                                                                  : existing.lastResubmitMs()));
        if (warn) {
            ctx.warn(AWAITING_TARGET,
                     artifact.asString(),
                     "Leader " + ctx.self().id()
                    + " has awaited a committed SliceTarget for " + artifact.asString()
                    + " for " + (ctx.nowMs() - existing.sinceMs())
                    + " ms");
        }

        if (resubmit) {
            var mutation = targetPut(m, base, pending);

            Outbox.direct(m,
                          TxDraft.draft(SubmitKind.TARGETS, mutation.key(), List.of(mutation)));
        }
    }

    private static void endAwaited(ClusterModel m, Ctx ctx, Artifact artifact, String why) {
        m.awaitedTargets.get(artifact)
                        .onPresent(awaited -> {
                                       m.awaitedTargets = m.awaitedTargets.remove(artifact);
                                       if (awaited.warned()) {
                                       ctx.warn(DEFERRAL_ENDED,
                                                artifact.asString(),
                                                "Leader " + ctx.self()
                                                               .id()
                                               + " no longer awaits a committed SliceTarget for " + artifact.asString()
                                               + ": " + why);
                                   }
                                   });
    }

    // -- step 2: the intake re-drive (R-4.1.4, NT-83) ----------------------------------------------------------------
    /// R-2.7: an apply is open while its record exists and no terminal outcome for that attempt is committed.
    static boolean isOpen(ClusterModel m, BlueprintId id, DeploymentApplyValue apply) {
        return m.outcomes.get(id)
                         .filter(outcome -> outcome.describesAttempt(apply.attemptId()))
                         .map(outcome -> outcome.status() == DeploymentOutcomeStatus.IN_PROGRESS)
                         .or(true);
    }

    /// An open apply whose blueprint is committed but whose targets are not all committed gets C4's batch again, at most
    /// once per bound per blueprint.
    static ClusterModel redrive(ClusterModel m, Ctx ctx) {
        var bound = ctx.settings().timing().awaitedTarget() * 1_000_000L;

        m.applies.entries()
                 .stream()
                 .sorted(Comparator.comparing(entry -> entry.getKey()
                                                            .asString()))
                 .filter(entry -> isOpen(m,
                                         entry.getKey(),
                                         entry.getValue()))
                 .forEach(entry -> redriveOne(m,
                                              ctx,
                                              entry.getKey(),
                                              entry.getValue(),
                                              bound));

        return m;
    }

    private static void redriveOne(ClusterModel m, Ctx ctx, BlueprintId id, DeploymentApplyValue apply, long boundNs) {
        var throttled = m.redriveAt.get(id).map(at -> ctx.monoNs() - at < boundNs).or(false);

        if (!throttled) {
            m.blueprints.get(id)
                        .filter(value -> !value.registerOnly())
                        .map(AppBlueprintValue::blueprint)
                        .filter(expanded -> hasUncommittedTarget(m, expanded))
                        .onPresent(expanded -> redeclare(m, ctx, id, expanded));
        }
    }

    private static boolean hasUncommittedTarget(ClusterModel m, ExpandedBlueprint expanded) {
        return expanded.loadOrder()
                       .stream()
                       .anyMatch(slice -> !m.targets.get(slice.artifact().base())
                                                    .map(target -> target.currentVersion()
                                                                         .equals(slice.artifact().version()))
                                                    .or(false));
    }

    private static void redeclare(ClusterModel m, Ctx ctx, BlueprintId id, ExpandedBlueprint expanded) {
        m.redriveAt = m.redriveAt.put(id, ctx.monoNs());
        declare(m, ctx, id, expanded, false);
    }

    record unused() implements Blueprints {}
}
