// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;


/// The Active transition table (spec 3.3, C1 to C23): one arm per input, delegating to the family that owns it.
///
/// A KV notification is first **indexed** (so every handler sees the committed fact) and then **reacted to**: the
/// family decides what follows. Inputs that only change a fact the reconcile reads (a judgment, an outcome) are
/// indexed and queue a reconcile (C22).
public sealed interface Handlers {
    static ClusterModel handle(ClusterModel m, Ctx ctx, ClusterInput input) {
        return switch (input) {
            case ClusterInput.KvPut put -> onPut(m, ctx, put.key(), put.value());
            case ClusterInput.KvRemove remove -> onRemove(m, ctx, remove.key());
            case ClusterInput.LeaderObserved leader -> Lifecycle.onLeader(m, ctx, leader.value());
            case ClusterInput.WitnessStale _ -> Lifecycle.onWitnessStale(m);
            case ClusterInput.SeamsRefreshed seams -> Lifecycle.onSeams(m, ctx, seams.seams());
            case ClusterInput.NodeJoined join -> Joins.onJoin(m, ctx, join.node(), join.admission(), false);
            case ClusterInput.WorkerJoin join -> Joins.onJoin(m, ctx, join.node(), join.admission(), true);
            case ClusterInput.NodeRemoved gone -> DepartureFlow.onDeparture(m,
                                                                            ctx,
                                                                            gone.node(),
                                                                            DepartureCause.NODE_REMOVED);
            case ClusterInput.WorkerLeave gone -> DepartureFlow.onDeparture(m,
                                                                            ctx,
                                                                            gone.node(),
                                                                            DepartureCause.WORKER_LEAVE);
            case ClusterInput.SelfShutdown gone -> DepartureFlow.onDeparture(m,
                                                                             ctx,
                                                                             gone.node(),
                                                                             DepartureCause.SELF_SHUTDOWN);
            case ClusterInput.ProcessKnown known -> Joins.onProcessKnown(m, ctx, known.node(), known.token());
            case ClusterInput.NodeDraining draining -> Drains.onDraining(m, ctx, draining.node());
            case ClusterInput.Reconcile _ -> Reconcile.run(m, ctx);
            case ClusterInput.Flush _ -> Outbox.flush(m, ctx, Lifecycle.isSuspended(m, ctx));
            case ClusterInput.Timer timer -> Lifecycle.onTimer(m, ctx, timer.timer());
            case ClusterInput.WriteSettled settled -> Outbox.settle(m, ctx, settled);
            case ClusterInput.Activate _ -> m;
            case ClusterInput.Deactivate _ -> m;
            case ClusterInput.Rebuild _ -> m;
        };
    }

    // -- committed puts --------------------------------------------------------------------------------------------
    private static ClusterModel onPut(ClusterModel m, Ctx ctx, AetherKey key, AetherValue value) {
        return switch (key) {
            case AppBlueprintKey k when value instanceof AppBlueprintValue v -> Blueprints.onBlueprintPut(m,
                                                                                                          ctx,
                                                                                                          k.blueprintId(),
                                                                                                          v);
            case SliceTargetKey k when value instanceof SliceTargetValue v -> Blueprints.onTargetPut(m, ctx, k, v);
            case VersionRoutingKey k when value instanceof VersionRoutingValue v -> Blueprints.onRoutingPut(m, ctx, k, v);
            case SliceObservedKey k when value instanceof SliceObservedValue v -> Slices.onObservedPut(m,
                                                                                                       ctx,
                                                                                                       SlicePair.slicePair(k.nodeId(),
                                                                                                                           k.artifact()),
                                                                                                       v);
            case SliceDesiredKey k when value instanceof SliceDesiredValue v -> Slices.onDesiredPut(m,
                                                                                                    ctx,
                                                                                                    SlicePair.slicePair(k.nodeId(),
                                                                                                                        k.artifact()),
                                                                                                    v);
            case ActivationDirectiveKey k when value instanceof ActivationDirectiveValue v -> Directory.onDirectivePut(m,
                                                                                                                       ctx,
                                                                                                                       k.nodeId(),
                                                                                                                       v);
            case GovernorAnnouncementKey k when value instanceof GovernorAnnouncementValue v -> Directory.onAnnouncementPut(m,
                                                                                                                            ctx,
                                                                                                                            k.communityId(),
                                                                                                                            v);
            case CommunityKey k when value instanceof CommunityValue v -> Directory.onCommunityPut(m,
                                                                                                   ctx,
                                                                                                   k.communityId(),
                                                                                                   v);
            case NodeDepartureKey k when value instanceof NodeDepartureValue v -> DepartureFlow.onRecordPut(m,
                                                                                                            ctx,
                                                                                                            k.nodeId(),
                                                                                                            v);
            case SchemaVersionKey k when value instanceof SchemaVersionValue v -> Schemas.onPut(m,
                                                                                                ctx,
                                                                                                k.datasourceName(),
                                                                                                v);
            case DeploymentApplyKey k when value instanceof DeploymentApplyValue v -> Reconcile.request(Indexer.indexApply(m,
                                                                                                                           k.blueprintId(),
                                                                                                                           v),
                                                                                                        ctx);
            case DeploymentOutcomeKey k when value instanceof DeploymentOutcomeValue v -> Reconcile.request(Indexer.indexOutcome(m,
                                                                                                                                 k.blueprintId(),
                                                                                                                                 v),
                                                                                                            ctx);
            default -> Judgments.onPut(m, ctx, key, value);
        };
    }

    // -- committed removes -----------------------------------------------------------------------------------------
    private static ClusterModel onRemove(ClusterModel m, Ctx ctx, AetherKey key) {
        return switch (key) {
            case AppBlueprintKey k -> Blueprints.onBlueprintRemove(m, ctx, k.blueprintId());
            case SliceTargetKey k -> Blueprints.onTargetRemove(m, ctx, k);
            case VersionRoutingKey k -> Blueprints.onRoutingRemove(m, ctx, k);
            case SliceObservedKey k -> Slices.onObservedRemove(m,
                                                               ctx,
                                                               SlicePair.slicePair(k.nodeId(), k.artifact()));
            case SliceDesiredKey k -> Slices.onDesiredRemove(m,
                                                             ctx,
                                                             SlicePair.slicePair(k.nodeId(), k.artifact()));
            case ActivationDirectiveKey k -> Directory.onDirectiveRemove(m, ctx, k.nodeId());
            case GovernorAnnouncementKey k -> Directory.onAnnouncementRemove(m, ctx, k.communityId());
            case CommunityKey k -> Indexer.removeCommunity(m, k.communityId());
            case NodeDepartureKey k -> DepartureFlow.onRecordRemove(m, ctx, k.nodeId());
            case SchemaVersionKey k -> Indexer.removeSchema(m, k.datasourceName());
            case DeploymentApplyKey k -> Indexer.removeApply(m, k.blueprintId());
            case DeploymentOutcomeKey k -> Indexer.removeOutcome(m, k.blueprintId());
            default -> Judgments.onRemove(m, ctx, key);
        };
    }

    record unused() implements Handlers {}
}
