// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;

import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffAction.AddRole;
import org.pragmatica.aether.config.cluster.DiffAction.AddSource;
import org.pragmatica.aether.config.cluster.DiffAction.ClusterLevelChange;
import org.pragmatica.aether.config.cluster.DiffAction.ImmutableFieldChange;
import org.pragmatica.aether.config.cluster.DiffAction.RemoveRole;
import org.pragmatica.aether.config.cluster.DiffAction.RemoveSource;
import org.pragmatica.aether.config.cluster.DiffAction.RuntimeChange;
import org.pragmatica.aether.config.cluster.DiffAction.ScaleDown;
import org.pragmatica.aether.config.cluster.DiffAction.ScaleUp;
import org.pragmatica.aether.config.cluster.DiffAction.SourceFieldChange;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public sealed interface ClusterConfigApplier {
    Logger log = LoggerFactory.getLogger(ClusterConfigApplier.class);
    Promise<Unit> apply(List<DiffAction> actions);

    static ClusterConfigApplier clusterConfigApplier(ClusterTopologyManager topologyManager) {
        return new ClusterConfigApplierRecord(topologyManager);
    }

    /// #578 review: this used to unconditionally succeed — `ManagementServer`'s fallback for a node
    /// with no wired `ClusterTopologyManager`, currently dead code (see
    /// [ClusterConfigError.ClusterTopologyManagerUnavailable]) but shipping the same silent-success
    /// shape #578 closes on the live path. Fails loudly instead so the same defect can't resurface
    /// here if that fallback ever becomes reachable.
    enum NoTopologyManager implements ClusterConfigApplier {
        INSTANCE;
        @Override
        public Promise<Unit> apply(List<DiffAction> actions) {
            return ClusterConfigError.ClusterTopologyManagerUnavailable.INSTANCE.promise();
        }
    }
}

record ClusterConfigApplierRecord(ClusterTopologyManager topologyManager) implements ClusterConfigApplier {
    /// #578 review: `classify` is the one place that maps every `DiffAction` variant to either a
    /// live effect or a rejection — splitting that into two switches (a "supported?" check and a
    /// separate "apply" switch) let them drift out of sync. One exhaustive switch, no `default`, is
    /// the only way a future 11th variant fails to compile instead of silently falling through.
    private sealed interface Classification {
        record Scale(SourceName sourceName, NodeRole role, int target, String description) implements Classification {}

        record Reject(ClusterConfigError cause) implements Classification {}
    }

    /// A REJECTED plan (any action fails [#classify]) is guaranteed zero side effects — nothing in it
    /// is actuated. An ACCEPTED plan carries no equivalent guarantee once actuation starts: `applyScales`
    /// folds the plan's scales through `topologyManager.setDesiredCount` one at a time, and if a write
    /// partway through the fold fails, the writes before it already landed with no compensation to
    /// undo them. #578 review Testing Gap T3 — documented rather than built: no caller has produced a
    /// mixed-scale plan where one write can fail after another already succeeded, so compensating
    /// rollback stayed out of scope pending a real case.
    @Override
    public Promise<Unit> apply(List<DiffAction> actions) {
        var classified = actions.stream().map(ClusterConfigApplierRecord::classify).toList();
        // #578 review Issue 6: validate the WHOLE plan before actuating anything, but report only the
        // FIRST rejection even when several actions in the same plan are individually rejectable — a
        // deliberate fail-fast choice, not an oversight. This mirrors the route layer's own
        // `hasImmutableChanges()` short-circuit (`ClusterConfigRoutes.executeDiff`), which likewise
        // never reaches this applier for that case. Aggregating every rejected action into one report
        // (as `ClusterConfigError.ValidationFailed` does for bootstrap-time validation) would need a
        // list-returning classification instead of first-match — left for whoever needs multi-error
        // apply reporting, since no caller has asked for it yet.
        return firstRejection(classified).fold(() -> applyScales(scalesOf(classified)), this::rejectPlan);
    }

    // #578 review: validate the WHOLE plan before actuating anything. A diff mixing a scale with an
    // unsupported action used to apply the scale (a live desired-count write) and only then fail — the
    // cluster was mutated but the stored config still held the old value and the operator was told the
    // apply failed. Reject up front so a rejected plan never has a partially-applied side effect to
    // reconcile.
    private static Option<ClusterConfigError> firstRejection(List<Classification> classified) {
        return Option.from(classified.stream()
                                     .filter(Classification.Reject.class::isInstance)
                                     .map(Classification.Reject.class::cast)
                                     .map(Classification.Reject::cause)
                                     .findFirst());
    }

    private static List<Classification.Scale> scalesOf(List<Classification> classified) {
        return classified.stream()
                         .filter(Classification.Scale.class::isInstance)
                         .map(Classification.Scale.class::cast)
                         .toList();
    }

    private Promise<Unit> rejectPlan(ClusterConfigError cause) {
        ClusterConfigApplier.log.warn("Rejected apply plan: {}", cause.message());

        return cause.promise();
    }

    // Sequential fold, not `Promise.allOf` — desired-count writes for the same plan go to
    // `topologyManager` one at a time, matching the ordering an operator reading the plan top-to-bottom
    // expects. The combiner is never invoked (this stream is never made parallel); it exists only to
    // satisfy the 3-arg `reduce` signature needed to fold a `List<Scale>` into a single `Promise<Unit>`.
    private Promise<Unit> applyScales(List<Classification.Scale> scales) {
        return scales.stream()
                     .reduce(Promise.unitPromise(),
                             (promise, scale) -> promise.flatMap(_ -> applyScale(scale.sourceName(),
                                                                                 scale.role(),
                                                                                 scale.target(),
                                                                                 scale.description())),
                             (first, _) -> first);
    }

    private static Classification classify(DiffAction action) {
        return switch (action) {
            case ScaleUp scale -> new Classification.Scale(scale.sourceName(),
                                                           scale.role(),
                                                           scale.to(),
                                                           scale.description());
            case ScaleDown scale -> new Classification.Scale(scale.sourceName(),
                                                             scale.role(),
                                                             scale.to(),
                                                             scale.description());
            // #578 review Issue 1: kept distinct from the shared `UnsupportedApplyAction` rejection
            // below — this one has a dedicated `Cause` (409 CONFLICT; "destroy and re-bootstrap" is
            // actually true here) rather than the generic 501. This status is NOT end-to-end
            // consistent, though: the live HTTP route (`ClusterConfigRoutes.executeDiff`) intercepts
            // `plan.hasImmutableChanges()` before this applier ever runs and answers via
            // `ClusterConfigError.ValidationFailed`, which does not override `httpStatus()` and so
            // returns the interface default 400 for the identical input. This applier's 409 is
            // therefore unreachable from the live route today — it only fires if a caller invokes
            // `apply()` directly with an unfiltered plan. Fixing the divergence means changing
            // `ValidationFailed`'s status propagation, which affects every other validator that wraps
            // it — out of scope for #578. Tracked here as a known, deferred inconsistency, not claimed
            // as fixed.
            case ImmutableFieldChange change -> new Classification.Reject(new ClusterConfigError.ImmutableFieldChange(change.field()));
            case AddSource _ -> new Classification.Reject(new ClusterConfigError.UnsupportedApplyAction(action));
            case RemoveSource _ -> new Classification.Reject(new ClusterConfigError.UnsupportedApplyAction(action));
            case AddRole _ -> new Classification.Reject(new ClusterConfigError.UnsupportedApplyAction(action));
            case RemoveRole _ -> new Classification.Reject(new ClusterConfigError.UnsupportedApplyAction(action));
            case RuntimeChange _ -> new Classification.Reject(new ClusterConfigError.UnsupportedApplyAction(action));
            case SourceFieldChange _ -> new Classification.Reject(new ClusterConfigError.UnsupportedApplyAction(action));
            case ClusterLevelChange _ -> new Classification.Reject(new ClusterConfigError.UnsupportedApplyAction(action));
        };
    }

    /// RFC-0017 stage 5 — ALL roles route through the same fenced desired-count write (closing
    /// #241's worker-provisioning gap; the wider community-topology epic stays open). The typed topology (stage 2) made non-core counts expressible, the successor fence
    /// (stage 3) made the write safe, and the worker reconciler acts on the committed value via the
    /// `ClusterConfigKey` fan-out — this applier never provisions directly, for any role.
    private Promise<Unit> applyScale(SourceName sourceName, NodeRole role, int target, String description) {
        return topologyManager.setDesiredCount(sourceName, role, target)
                              .onSuccess(_ -> ClusterConfigApplier.log.info("Applied scale: {}", description))
                              .mapToUnit();
    }
}
