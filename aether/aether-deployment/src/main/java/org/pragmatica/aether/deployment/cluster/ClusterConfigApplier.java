// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.aether.config.cluster.ApplyRoute;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Validate the complete config plan before its single atomic desired-state commit.
/// Per-source changes must never actuate while the rest of the submitted config is uncommitted.
public sealed interface ClusterConfigApplier {
    Promise<Unit> validate(List<DiffAction> actions);

    /// #686 check 2: validate a plan whose client claims `claimed` as the route of its pool changes. The route of every
    /// pool-changing action is decided here from the committed auto-heal value, never taken from the claim: LEADER for a
    /// worker or spot pool and for a core pool with auto-heal on, APPLY for a core pool with auto-heal off. Refused, before
    /// any route comparison: the actions no route admits (version, runtime, runtime-profile, immutable), worker capacity
    /// changes under explicit communities, a source field change outside a core-only source with auto-heal off (M4), a
    /// worker or spot removal with a committed count above zero (M5), a core role added with nodes while auto-heal is on
    /// (M9), and a core scale-up while auto-heal is on and several sources declare the core role (N2). Then any action whose
    /// route differs from the claim refuses the plan with [ClusterConfigError.RouteMismatch].
    Promise<Unit> validateRoute(ApplyRoute claimed,
                                List<DiffAction> actions,
                                ClusterBootstrapConfig stored,
                                ClusterBootstrapConfig desired);

    static ClusterConfigApplier clusterConfigApplier(ClusterTopologyManager topologyManager) {
        return new ClusterConfigApplierRecord(topologyManager);
    }

    enum NoTopologyManager implements ClusterConfigApplier {
        INSTANCE;
        @Override
        public Promise<Unit> validate(List<DiffAction> actions) {
            return ClusterConfigError.ClusterTopologyManagerUnavailable.INSTANCE.promise();
        }
        @Override
        public Promise<Unit> validateRoute(ApplyRoute claimed,
                                           List<DiffAction> actions,
                                           ClusterBootstrapConfig stored,
                                           ClusterBootstrapConfig desired) {
            return ClusterConfigError.ClusterTopologyManagerUnavailable.INSTANCE.promise();
        }
    }
}

record ClusterConfigApplierRecord(ClusterTopologyManager topologyManager) implements ClusterConfigApplier {
    /// The `ClusterLevelChange` field [ClusterBootstrapConfigDiff] emits for a changed `[cluster] version`.
    private static final String VERSION_FIELD = "version";

    @Override
    public Promise<Unit> validate(List<DiffAction> actions) {
        if (topologyManager.usesExplicitCommunities() && actions.stream()
                                                                .anyMatch(ClusterConfigApplierRecord::workerCountChange)) {
            return new ClusterConfigError.ParseFailed("Explicit communities own worker capacity; change community.target_size instead of source worker count").promise();
        }

        return Option.from(actions.stream()
                                  .map(ClusterConfigApplierRecord::rejection)
                                  .flatMap(Option::stream)
                                  .findFirst()).fold(Promise::unitPromise, cause -> cause.promise());
    }

    @Override
    public Promise<Unit> validateRoute(ApplyRoute claimed,
                                       List<DiffAction> actions,
                                       ClusterBootstrapConfig stored,
                                       ClusterBootstrapConfig desired) {
        var plan = new RoutedPlan(topologyManager.isAutoHealEnabled(), stored, desired);

        return communityRefusal(actions).orElse(() -> firstOf(actions.stream().map(plan::refusal)))
                               .orElse(() -> firstOf(actions.stream().map(action -> plan.mismatch(claimed, action))))
                               .fold(Promise::unitPromise,
                                     cause -> cause.promise());
    }

    /// The explicit-community guard on the route path: with a route, a worker `AddRole` is no longer refused as
    /// unsupported, so it joins the worker scale changes this guard covers.
    private Option<ClusterConfigError> communityRefusal(List<DiffAction> actions) {
        return topologyManager.usesExplicitCommunities() && actions.stream()
                                                                   .anyMatch(ClusterConfigApplierRecord::workerCapacityChange)
               ? Option.some(new ClusterConfigError.ParseFailed("Explicit communities own worker capacity; change community.target_size instead of source worker count"))
               : Option.none();
    }

    private static Option<ClusterConfigError> firstOf(Stream<Option<ClusterConfigError>> verdicts) {
        return Option.from(verdicts.flatMap(Option::stream).findFirst());
    }

    private static boolean workerCapacityChange(DiffAction action) {
        return switch (action) {
            case DiffAction.AddRole add -> add.role().equals(NodeRole.WORKER);
            default -> workerCountChange(action);
        };
    }

    private static boolean workerCountChange(DiffAction action) {
        return switch (action) {
            case DiffAction.ScaleUp scale -> scale.role().equals(NodeRole.WORKER);
            case DiffAction.ScaleDown scale -> scale.role().equals(NodeRole.WORKER);
            default -> false;
        };
    }

    private static Option<ClusterConfigError> rejection(DiffAction action) {
        return switch (action) {
            case DiffAction.ScaleUp _, DiffAction.ScaleDown _, DiffAction.CommunityPlacementChange _ -> Option.none();
            case DiffAction.ImmutableFieldChange change -> Option.some(new ClusterConfigError.ImmutableFieldChange(change.field()));
            case DiffAction.ClusterLevelChange change when VERSION_FIELD.equals(change.field()) -> Option.some(new ClusterConfigError.VersionChangeViaApply(change.from(),
                                                                                                                                                            change.to()));
            case DiffAction.RuntimeChange change -> Option.some(new ClusterConfigError.RuntimeChangeViaApply(change.sourceName().value(),
                                                                                                             change.role().value(),
                                                                                                             change.fromRuntime(),
                                                                                                             change.toRuntime()));
            case DiffAction.RuntimeProfileChange change -> Option.some(new ClusterConfigError.RuntimeProfileChangeViaApply(change.profileName(),
                                                                                                                           change.fields()));
            case DiffAction.AddSource _, DiffAction.RemoveSource _, DiffAction.AddRole _, DiffAction.RemoveRole _, DiffAction.SourceFieldChange _, DiffAction.ClusterLevelChange _ -> Option.some(new ClusterConfigError.UnsupportedApplyAction(action));
        };
    }

    /// The committed state check 2 decides against: the auto-heal value names the route of a core pool, the stored config
    /// sizes the roles a removal takes away, and the desired config names the core sources the leader heals from.
    private record RoutedPlan(boolean autoHealEnabled, ClusterBootstrapConfig stored, ClusterBootstrapConfig desired) {
        Option<ClusterConfigError> refusal(DiffAction action) {
            return switch (action) {
                case DiffAction.SourceFieldChange change when autoHealEnabled || declaresLiveNonCore(change.sourceName()) -> Option.some(new ClusterConfigError.SourceReplaceRefused(change.sourceName().value(),
                                                                                                                                                                                     change.field()));
                case DiffAction.RemoveRole remove when!remove.role().equals(NodeRole.CORE) && remove.count() > 0 -> Option.some(new ClusterConfigError.LivePoolRemovalRefused(remove.sourceName().value(),
                                                                                                                                                                              remove.role().value(),
                                                                                                                                                                              remove.count()));
                case DiffAction.RemoveSource remove -> liveNonCoreRemoval(remove.sourceName());
                case DiffAction.AddRole add when add.role().equals(NodeRole.CORE) && add.count() > 0 && autoHealEnabled -> Option.some(new ClusterConfigError.CoreFillRefused(add.sourceName().value(),
                                                                                                                                                                              add.count()));
                case DiffAction.ScaleUp scale when scale.role().equals(NodeRole.CORE) && autoHealEnabled && coreSources().size() > 1 -> Option.some(new ClusterConfigError.MultiSourceCoreScaleRefused(scale.sourceName().value(),
                                                                                                                                                                                                       coreSources()));
                default -> rejection(action).filter(RoutedPlan::refusedWithRoute);
            };
        }

        Option<ClusterConfigError> mismatch(ApplyRoute claimed, DiffAction action) {
            return route(action).filter(required -> required != claimed)
                        .map(required -> new ClusterConfigError.RouteMismatch(claimed, required, action));
        }

        /// The route of a pool-changing action; none for an action that changes no pool (a config-only change, or a
        /// removal of nodes the stored config already sizes at zero, or an addition of a zero-size role).
        private Option<ApplyRoute> route(DiffAction action) {
            return switch (action) {
                case DiffAction.ScaleUp scale -> Option.some(routeOf(scale.role()));
                case DiffAction.ScaleDown scale -> Option.some(routeOf(scale.role()));
                case DiffAction.AddRole add when add.count() > 0 -> Option.some(routeOf(add.role()));
                case DiffAction.RemoveRole remove when remove.count() > 0 -> Option.some(routeOf(remove.role()));
                case DiffAction.RemoveSource remove when storedRoles(remove.sourceName()).anyMatch(role -> ClusterBootstrapConfigDiff.roleSize(role.getValue()) > 0) -> Option.some(routeOf(NodeRole.CORE));
                case DiffAction.SourceFieldChange _ -> Option.some(routeOf(NodeRole.CORE));
                default -> Option.none();
            };
        }

        private ApplyRoute routeOf(NodeRole role) {
            return role.equals(NodeRole.CORE) && !autoHealEnabled
                   ? ApplyRoute.APPLY
                   : ApplyRoute.LEADER;
        }

        /// A route admits what `rejection` refuses only as unsupported without one (config-only and structural changes,
        /// which check 2 judges above); a version, runtime, runtime-profile or immutable change stays refused.
        private static boolean refusedWithRoute(ClusterConfigError cause) {
            return ! (cause instanceof ClusterConfigError.UnsupportedApplyAction);
        }

        private Option<ClusterConfigError> liveNonCoreRemoval(SourceName source) {
            return Option.from(storedRoles(source).filter(role -> !role.getKey()
                                                                       .equals(NodeRole.CORE))
                                          .filter(role -> ClusterBootstrapConfigDiff.roleSize(role.getValue()) > 0)
                                          .findFirst()).map(role -> new ClusterConfigError.LivePoolRemovalRefused(source.value(),
                                                                                                                  role.getKey()
                                                                                                                      .value(),
                                                                                                                  ClusterBootstrapConfigDiff.roleSize(role.getValue())));
        }

        private boolean declaresLiveNonCore(SourceName source) {
            return Stream.concat(storedRoles(source), rolesOf(desired, source)).anyMatch(role -> !role.getKey()
                                                                                                      .equals(NodeRole.CORE) && ClusterBootstrapConfigDiff.roleSize(role.getValue()) > 0);
        }

        private List<String> coreSources() {
            return desired.sources()
                          .entrySet()
                          .stream()
                          .filter(source -> source.getValue()
                                                  .roles()
                                                  .containsKey(NodeRole.CORE))
                          .map(Map.Entry::getKey)
                          .sorted()
                          .toList();
        }

        private Stream<Map.Entry<NodeRole, RoleSubTable>> storedRoles(SourceName source) {
            return rolesOf(stored, source);
        }

        private static Stream<Map.Entry<NodeRole, RoleSubTable>> rolesOf(ClusterBootstrapConfig config,
                                                                         SourceName source) {
            return Option.option(config.sources().get(source.value()))
                         .stream()
                         .flatMap(profile -> profile.roles()
                                                    .entrySet()
                                                    .stream());
        }
    }
}
