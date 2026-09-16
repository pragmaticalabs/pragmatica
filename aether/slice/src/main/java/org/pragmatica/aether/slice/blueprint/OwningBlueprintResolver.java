// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;


/// Resolves the [Artifact] of the blueprint that OWNS a deployed slice (#1216).
///
/// WHY THIS EXISTS. A bare pub/sub topic name is scoped to a blueprint, exactly as a stream alias is
/// (`BlueprintStreamAddresses`). Both ends of a co-deployed pair must therefore name the same
/// blueprint, and neither end holds one: the publisher has the provisioning context's slice id and the
/// subscriber has its `SliceNodeKey`. This is the one hop that turns a slice identity into the
/// blueprint identity both ends share, read from the SAME `SliceTargetValue.owningBlueprint` (#698)
/// the stream path already resolves through — so agreement is structural rather than a convention two
/// modules are each asked to remember.
///
/// WHY THE TOPIC PATH LOOKS UP THE BLUEPRINT AND THEN RE-DERIVES, WHERE THE STREAM PATH LOOKS UP THE
/// WHOLE ADDRESS. `BlueprintStreamAddresses` states two reasons a stream alias cannot be re-derived
/// locally; NEITHER holds for topics, which is why this is one hop and not two:
///
///  - **the version is derivable here.** `StreamConfig` drops the `resources.toml` `version` key, so a
///    stream re-derivation could only ever pin `1.0.0`. A topic declaration carrying an explicit
///    version is written fully namespaced (`ns:topic:version`) and [TopicAddressResolver] parses it
///    verbatim, never reaching namespace derivation at all.
///  - **a cross-namespace topic is already expressible.** `External` streams need the catalog because
///    a local derivation would aim them at the wrong ring; the topic equivalent is again the
///    fully-namespaced spelling, handled by the same verbatim branch.
///
/// So the topic path needs the blueprint IDENTITY, not a published address map, and publishing a
/// second per-blueprint bindings map for topics would add a deploy-time write with nothing to carry.
///
/// ABSENT IN UNIT-TEST AND MINIMAL RUNTIMES, BY DESIGN, and the absence is benign here in a way it is
/// not for streams. `BlueprintStreamAddresses.engineKeyFor` must REFUSE an alias it cannot resolve
/// under a known blueprint, because a bare fallback would silently address a ring nobody writes. This
/// resolver has no such failure mode: it answers "which blueprint owns this slice", and [Option#none]
/// means there is no deployment behind this runtime — no second spelling exists to disagree with, so
/// [TopicAddressResolver#resolve(Option, Artifact, String)] scopes to the slice's own coordinates and
/// both ends still agree. Forge/Ember is NOT this case: its nodes deploy through a blueprint publish,
/// so its slices have an owning blueprint like any cluster's.
@FunctionalInterface
public interface OwningBlueprintResolver {
    /// The owning blueprint's [Artifact] for the slice deployed under `sliceId`
    /// (`groupId:artifactId:version`), or [Option#none] when the slice has no owning blueprint or the
    /// id does not parse.
    Option<Artifact> owningBlueprintOf(String sliceId);

    /// Cluster-backed resolver over the deploy-time `SliceTargetValue` written by the blueprint apply.
    ///
    /// THE ORDERING IS CAUSAL, NOT BATCH CO-LOCATION. An earlier version of this note defended it by
    /// claiming the `owningBlueprint` Put sits in an apply batch preceding any `SliceNodeKey`
    /// assignment. That is false: `SliceNodeKey` is never written to consensus at all. What actually
    /// holds is stronger — allocation is TRIGGERED BY the committed `SliceTargetKey` Put, which
    /// `ClusterDeploymentState.handleSliceTargetChange` reacts to and only then emits the
    /// `NodeArtifactKey` Put that tells a node to load the slice. The leader cannot issue the load
    /// trigger before observing the owner committed, and every node applies the log in the same order,
    /// so both the provisioning-time (publisher) and activation-time (subscriber) reads see the owner.
    /// Verified structurally by reading the call chain, not by a runtime probe (#1216 review).
    static OwningBlueprintResolver kvBacked(KVStore<AetherKey, AetherValue> kvStore) {
        return sliceId -> Artifact.artifact(sliceId)
                                  .option()
                                  .flatMap(artifact -> owningBlueprintOf(kvStore, artifact));
    }

    /// The owning blueprint's [Artifact] for `sliceArtifact`, read directly from the KV snapshot.
    /// For callers that already hold the store and the artifact (the deployment FSM).
    static Option<Artifact> owningBlueprintOf(KVStore<AetherKey, AetherValue> kvStore, Artifact sliceArtifact) {
        return kvStore.get(SliceTargetKey.sliceTargetKey(sliceArtifact.base()))
                      .filter(SliceTargetValue.class::isInstance)
                      .map(SliceTargetValue.class::cast)
                      .flatMap(SliceTargetValue::owningBlueprint)
                      .map(BlueprintId::artifact);
    }
}
