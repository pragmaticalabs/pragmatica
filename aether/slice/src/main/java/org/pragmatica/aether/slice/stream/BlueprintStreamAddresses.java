// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// Resolves a slice's local stream alias to the engine key its declaration was deployed under (#1040).
///
/// WHY THIS LOOKS THE ADDRESS UP RATHER THAN RE-DERIVING IT. The blueprint-derived namespace is
/// computable from the slice's own Maven coordinates — `TopicAddressResolver` does exactly that for
/// pub/sub topics — and re-deriving here would have been a smaller change. It would also have been
/// wrong twice over, silently:
///
///  - **the version is not derivable.** `StreamConfig` has no version component and the config binder
///    drops the `resources.toml` `version` key, so a re-derivation can only pin
///    `ResourceVersion.defaultVersion()` (`1.0.0`). A declaration carrying `version = "2.0.0"` would
///    get `ns:name:1.0.0` from this side while the catalog holds `ns:name:2.0.0` — the #1040
///    divergence relocated, not removed. It would also be invisible in this repo, where no blueprint
///    declares an explicit stream version, so every fixture would agree while the general case broke.
///  - **`External` resources are not in the local namespace at all.** A `source = "other:stream:1.0.0"`
///    declaration deliberately points outside the declaring blueprint; namespacing it locally would
///    aim a cross-namespace consumer at a ring nobody writes.
///
/// So the address comes from [BlueprintStreamBindingsValue] — the alias→[ResourceAddress] map
/// `BlueprintService` publishes at deploy time from the SAME `StreamResourceValidator` output the
/// catalog identity descends from, handling `Owned`-with-explicit-version and `External` alike. Both
/// sides now read one map instead of computing two answers, which is what makes the agreement
/// structural rather than a convention two modules are each asked to remember.
///
/// The two-hop lookup (artifact → owning blueprint → bindings) mirrors
/// `NodeDeploymentState.lookupStreamBindings`, which already consumes this map for stream refcounting.
public sealed interface BlueprintStreamAddresses {
    /// The engine key for `alias` as declared by the blueprint owning `artifact`.
    ///
    /// EXACTLY ONE STATE FALLS BACK TO THE BARE ALIAS, and the rest fail. The distinction is whether a
    /// catalog spelling exists for this slice at all:
    ///
    ///  - **no owning blueprint** — a unit test, a programmatic stream, or a slice not deployed under a
    ///    blueprint. There is no second spelling to disagree with and the bare name is the only identity
    ///    in play, so this returns it and says nothing. Forge/Ember is NOT this case: it deploys through
    ///    a blueprint publish, so its slices have an owning blueprint and need its bindings (#1066).
    ///  - **owning blueprint present, alias unresolvable** — [StreamAddressError], which fails
    ///    provisioning. Falling back here would hand the caller the bare key while every management
    ///    route addressed the qualified one: a consumer polling a ring no producer writes to, forever,
    ///    with no error. That is the silent failure #1040 exists to remove, re-created on a new path,
    ///    so it is refused instead. See [StreamAddressError] for why this is fatal and not retried.
    static Result<String> engineKeyFor(KVStore<AetherKey, AetherValue> kvStore, Artifact artifact, String alias) {
        return owningBlueprint(kvStore, artifact).fold(() -> success(alias),
                                                       blueprintId -> resolveWithinBlueprint(kvStore, blueprintId, alias));
    }

    /// The catalog address `alias` was deployed under, or [Option#none] when the owning blueprint or
    /// its bindings are not visible in the local KV snapshot. Absence-tolerant counterpart to
    /// [#engineKeyFor], for callers that are asking whether a binding exists rather than acting on it.
    static Option<ResourceAddress> addressFor(KVStore<AetherKey, AetherValue> kvStore,
                                              Artifact artifact,
                                              String alias) {
        return owningBlueprint(kvStore, artifact).flatMap(blueprintId -> bindings(kvStore, blueprintId))
                              .flatMap(value -> value.addressFor(alias));
    }

    private static Result<String> resolveWithinBlueprint(KVStore<AetherKey, AetherValue> kvStore,
                                                         BlueprintId blueprintId,
                                                         String alias) {
        return bindings(kvStore, blueprintId).toResult(StreamAddressError.UnresolvedStreamBindings.FACTORY.apply(alias,
                                                                                                                 blueprintId))
                       .flatMap(value -> addressWithin(value, blueprintId, alias))
                       .map(StreamEngineKey::engineKey);
    }

    private static Result<ResourceAddress> addressWithin(BlueprintStreamBindingsValue value,
                                                         BlueprintId blueprintId,
                                                         String alias) {
        return value.addressFor(alias)
                    .toResult(StreamAddressError.UnboundStreamAlias.FACTORY.apply(alias, blueprintId));
    }

    private static Option<BlueprintId> owningBlueprint(KVStore<AetherKey, AetherValue> kvStore, Artifact artifact) {
        return kvStore.get(SliceTargetKey.sliceTargetKey(artifact.base()))
                      .filter(SliceTargetValue.class::isInstance)
                      .map(SliceTargetValue.class::cast)
                      .flatMap(SliceTargetValue::owningBlueprint);
    }

    private static Option<BlueprintStreamBindingsValue> bindings(KVStore<AetherKey, AetherValue> kvStore,
                                                                 BlueprintId blueprintId) {
        return kvStore.get(BlueprintStreamBindingsKey.blueprintStreamBindingsKey(blueprintId))
                      .filter(BlueprintStreamBindingsValue.class::isInstance)
                      .map(BlueprintStreamBindingsValue.class::cast);
    }

    record unused() implements BlueprintStreamAddresses {}
}
