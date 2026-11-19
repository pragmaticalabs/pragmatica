// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.Schemas;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.BlueprintParser;
import org.pragmatica.aether.slice.blueprint.DeploymentConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;

/// The schema-hold predicate: whether a schema record holds a slice back from activation, shared by the activation gate and the
/// management API. The blocking status set is [Schemas#BLOCKING].
public interface SchemaHold {
    /// #760 review BLOCKING 1: the SAME predicate the activation gate uses
    /// (`Active#collectIfBlocking`) to decide whether a schema record holds a slice, exposed so
    /// `SchemaRoutes.heldSlices` reads live per-node state instead of re-deriving a parallel
    /// ownership-only check that ignores [SliceState] entirely.
    ///
    /// A slice is held only while it sits in [SliceState#LOADED] — i.e. it has not yet passed the
    /// gate. An [SliceState#ACTIVE] slice already passed it and has no transition path back
    /// through this check ([SliceState#validTransitions]), so re-arming a COMPLETED record to
    /// MIGRATING must not retroactively report a serving slice as held: `sliceState == LOADED` is
    /// load-bearing, not incidental.
    static boolean blocksSliceActivation(SliceState sliceState,
                                         Option<BlueprintId> sliceOwner,
                                         SchemaVersionValue schema,
                                         KVStore<AetherKey, AetherValue> kvStore) {
        return sliceState == SliceState.LOADED
               && Schemas.BLOCKING.contains(schema.status())
               && sliceOwner.map(owner -> owner.base()
                                               .equals(schema.owningBlueprint().base()) && resolveSchemaRequired(kvStore,
                                                                                                                 owner))
                            .or(false);
    }

    /// #760 review round 2 item a: `heldSlices` (the management-API view) and the FSM gate
    /// (`blockingSchemaRecords`) previously called schemaRequired resolution through two different
    /// paths — the gate via the in-memory `Active#blueprints` map, the route not at all — so a
    /// slice owned by a `schema_required = false` blueprint could be reported held even though the
    /// gate itself never blocked it. `resolveDeclaredSchemaRequired` is a pure function of the shared
    /// [KVStore], so both call sites now go through this one static method instead of diverging.
    /// Returns [Option#empty()] when nothing could be resolved (missing blueprint entry,
    /// resourcesConfig, or unparsable/incomplete resources.toml) — the caller decides how to log
    /// that and what to default to; see [#resolveSchemaRequired(KVStore, BlueprintId)] and
    /// `Active#resolveSchemaRequired(BlueprintId)`.
    static Option<Boolean> resolveDeclaredSchemaRequired(KVStore<AetherKey, AetherValue> kvStore,
                                                         BlueprintId blueprintId) {
        var blueprintKey = AppBlueprintKey.appBlueprintKey(blueprintId);

        return kvStore.get(blueprintKey)
                      .filter(AppBlueprintValue.class::isInstance)
                      .map(AppBlueprintValue.class::cast)
                      .flatMap(value -> value.blueprint()
                                             .resourcesConfig())
                      .flatMap(toml -> BlueprintParser.parse(toml).option())
                      .flatMap(org.pragmatica.aether.slice.blueprint.Blueprint::deploymentConfig)
                      .map(DeploymentConfig::schemaRequired);
    }

    /// Deliberately silent, unlike `Active#resolveSchemaRequired(BlueprintId)`: this is invoked once
    /// per candidate record inside [#blocksSliceActivation] (from both `blockingSchemaRecords`'s
    /// forEach and `heldSlices`'s forEach), so logging here would fire once per slice-node/schema
    /// pair per gate pass or status request instead of once per blueprint change.
    static boolean resolveSchemaRequired(KVStore<AetherKey, AetherValue> kvStore, BlueprintId blueprintId) {
        return resolveDeclaredSchemaRequired(kvStore, blueprintId).or(true);
    }

    /// #805 item 1: the SINGLE slice-owner resolution path for both callers of
    /// [#blocksSliceActivation] — the activation gate (`Active.blockingSchemaRecords`) and
    /// `SchemaRoutes.heldSlices` — reading the committed `SliceTargetKey`/`SliceTargetValue` record
    /// that both already treat as the ownership authority.
    ///
    /// #760's first round made the two share this PREDICATE; they still fed it from two different
    /// sources. The gate resolved the owner from `Active#blueprints`, a node-local mirror rebuilt
    /// from KV notifications and therefore lagging them, and additionally pre-filtered on that
    /// mirror's `schemaRequired` flag; the route read the KV record directly and pre-filtered on
    /// nothing. A stale mirror entry naming an owner the committed record no longer names made the
    /// gate hold a slice the route simultaneously reported as NOT held — the same divergence #760
    /// closed, running the other way. Sharing a predicate is not sharing a decision while its INPUTS
    /// disagree.
    ///
    /// The dropped `schemaRequired` pre-filter is not a lost check: [#blocksSliceActivation] already
    /// resolves `schemaRequired` per candidate record from this same [KVStore] via
    /// [#resolveSchemaRequired(KVStore, BlueprintId)], so the mirror's copy was strictly a second,
    /// divergent answer to a question the shared predicate was already asking.
    static Option<BlueprintId> resolveSliceOwner(KVStore<AetherKey, AetherValue> kvStore, Artifact artifact) {
        return kvStore.get(SliceTargetKey.sliceTargetKey(artifact.base()))
                      .filter(SliceTargetValue.class::isInstance)
                      .map(SliceTargetValue.class::cast)
                      .flatMap(SliceTargetValue::owningBlueprint);
    }
}
