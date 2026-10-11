// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.CommittedSliceTarget;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentContext.AwaitedTarget;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Contract;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #1109: what the leader does when its gate ([SliceAllocationEngine#tryAllocate]) refuses LOAD because the target it wants has not committed.
///
/// The gate moved a stall from the node (where a LOAD parked and warned) to the leader, so the visibility and the cure move with it. A target put
/// that was lost (a consensus batch that failed, a leader that changed) leaves the mirror holding a blueprint whose target never commits: nothing
/// would ever allocate it. Once a slice has been refused for the bound, the leader (1) raises `slice-start-awaiting-target`, naming the slice,
/// version and duration, and (2) re-submits the target put while the blueprint is still desired and no committed target names the version. Both
/// repeat at most once per bound, so a lost put costs one extra put per bound, never a storm. The re-submission is a plain put of the value the
/// leader last submitted; it is idempotent. `slice-start-deferral-ended` follows when the target commits or the blueprint is no longer desired.
/// Time is the context clock, never counted ticks.
final class AwaitedTargetWatch {
    private static final Logger log = LoggerFactory.getLogger(AwaitedTargetWatch.class);

    private final ClusterDeploymentState.Active active;

    AwaitedTargetWatch(ClusterDeploymentState.Active active) {
        this.active = active;
    }

    /// The gate refused LOAD for `artifact`. Opens the wait on the first refusal; after the bound warns once and re-submits at most once per bound.
    @Contract
    void refused(Artifact artifact) {
        var ctx = active.ctx();
        var now = ctx.nowMs();
        var bound = ctx.awaitedTargetBound().millis();
        var outcome = new boolean[2];
        var snapshot = new AwaitedTarget[1];

        ctx.awaitedTargets()
           .compute(artifact,
                    (_, existing) -> {
                        var entry = existing == null
                                    ? new AwaitedTarget(now, false, now)
                                    : existing;
                        var due = now - entry.since() >= bound;
                        var warn = due && !entry.warned();
                        var resubmit = due && now - entry.lastResubmitMs() >= bound;

                        outcome[0] = warn;
                        outcome[1] = resubmit;
                        snapshot[0] = new AwaitedTarget(entry.since(),
                                                        entry.warned() || warn,
                                                        resubmit
                                                        ? now
                                                        : entry.lastResubmitMs());

                        return snapshot[0];
                    });
        if (outcome[0]) {
            warn(artifact, now - snapshot[0].since());
        }

        if (outcome[1]) {
            resubmit(artifact);
        }
    }

    /// The wait for `artifact` is over (its LOAD was issued, or a sweep found it committed or no longer desired).
    @Contract
    void ended(Artifact artifact, String why) {
        var removed = active.ctx().awaitedTargets().remove(artifact);

        if (removed != null && removed.warned()) {
            OperatorWarnings.raise(log,
                                   active.ctx().operatorWarnings(),
                                   OperatorWarningCode.SLICE_START_DEFERRAL_ENDED,
                                   subject(artifact),
                                   "Leader {} no longer awaits a committed SliceTarget for {}: {}",
                                   active.ctx().self().id(),
                                   artifact,
                                   why);
        }
    }

    /// Run at the start of every reconcile tick: ends the wait of every slice whose target committed or whose blueprint is gone, and forgets the
    /// submitted value of every target that committed or whose blueprint is gone.
    @Contract
    void sweep() {
        var ctx = active.ctx();

        ctx.awaitedTargets().keySet().stream().toList().forEach(this::sweepOne);
        ctx.pendingTargetPuts()
           .entrySet()
           .removeIf(entry -> pendingIsMoot(entry.getKey().withVersion(entry.getValue().currentVersion())));
    }

    /// A submitted value is forgotten once its target committed, and once its blueprint is no longer desired (rolled back or removed): nothing
    /// would ever resubmit it, so it would only linger.
    private boolean pendingIsMoot(Artifact artifact) {
        return ! active.blueprints()
                       .containsKey(artifact) || CommittedSliceTarget.permits(active.ctx().kvStore(),
                                                                              artifact);
    }

    private void sweepOne(Artifact artifact) {
        if (!active.blueprints().containsKey(artifact)) {
            ended(artifact, "the blueprint is no longer desired");
        } else if (CommittedSliceTarget.permits(active.ctx().kvStore(),
                                                artifact)) {
            ended(artifact, "the committed target now names it");
        }
    }

    private void warn(Artifact artifact, long waitedMs) {
        OperatorWarnings.raise(log,
                               active.ctx().operatorWarnings(),
                               OperatorWarningCode.SLICE_START_AWAITING_TARGET,
                               subject(artifact),
                               "Leader {} has refused LOAD of {} for {} ms: the SliceTarget naming version {} has not committed (#1109). "
                              + "The leader re-submits the target put every {} ms while the blueprint is desired",
                               active.ctx().self().id(),
                               artifact,
                               waitedMs,
                               artifact.version(),
                               active.ctx().awaitedTargetBound().millis());
    }

    private void resubmit(Artifact artifact) {
        var blueprint = active.blueprints().get(artifact);

        if (blueprint == null || CommittedSliceTarget.permits(active.ctx().kvStore(),
                                                              artifact)) {
            return;
        }

        var value = pendingValue(artifact, blueprint);

        log.info("Leader re-submits the SliceTarget put for {}: not committed after {} ms (#1109)",
                 artifact,
                 active.ctx().awaitedTargetBound().millis());
        active.submitBatch(List.of(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(artifact.base()),
                                                       value)));
    }

    /// The value the leader last submitted for this base when it names the version, else one rebuilt from the mirror (a new leader's case).
    private SliceTargetValue pendingValue(Artifact artifact, ClusterDeploymentManager.Blueprint blueprint) {
        var pending = active.ctx().pendingTargetPuts().get(artifact.base());

        return pending != null && pending.currentVersion()
                                         .equals(artifact.version())
               ? pending
               : SliceTargetValue.sliceTargetValue(artifact.version(),
                                                   blueprint.instances(),
                                                   blueprint.minInstances(),
                                                   blueprint.owner());
    }

    private static String subject(Artifact artifact) {
        return artifact + "@leader";
    }
}
