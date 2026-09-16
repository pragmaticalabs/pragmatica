// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Single source of truth for resolving a declared pub/sub topic string to its canonical,
/// blueprint-namespaced [ResourceAddress].
///
/// Both ends of a co-deployed pub/sub pair MUST resolve the same declared topic to the SAME
/// address, or routing silently fails. The subscriber side (deployment FSM, when it publishes a
/// subscription to the KV store) and the publisher side ([org.pragmatica.aether.invoke.PublisherFactory])
/// both delegate here so the namespace derivation can never diverge. Callers pass the raw declared
/// topic string (`TopicConfig.topicName()`); this resolver does not depend on the config type.
///
/// Resolution rule (mirrors stream addressing and [PubSubValidator]):
///  - an already fully-namespaced declaration (`namespace:topic:version`) is parsed verbatim;
///  - a bare/legacy topic name derives its namespace from the OWNING BLUEPRINT's Maven coordinates
///    via [BlueprintNamespace#deriveNamespace(Artifact)] and defaults the version to
///    [ResourceVersion#defaultVersion] (`1.0.0`).
///
/// THE BLUEPRINT IS AN INPUT, NOT SOMETHING THIS CLASS CAN FIND (#1216). Until then this class
/// documented "the owning slice's blueprint Maven coordinates" while [BlueprintNamespace] read
/// nothing blueprint-scoped — it namespaced by whatever artifact it was handed, and both ends handed
/// it their OWN slice. Matching in `TopicSubscriptionRegistry` is exact string equality, so a
/// co-deployed pair of two DISTINCT slices could never agree and `TopicPublisher` returned success
/// with zero deliveries and zero log lines. The producer disagreeing with its own docstring is why
/// the docstring was never evidence; the fix is that callers must now supply the blueprint, which
/// [OwningBlueprintResolver] is the one way to obtain.
public final class TopicAddressResolver {
    private TopicAddressResolver() {}

    /// Resolve a raw declared topic string for a slice, scoping a bare name to the blueprint that
    /// OWNS that slice. This is the form both ends of a pub/sub pair MUST use.
    ///
    /// Falls back to the slice's own coordinates when `owningBlueprint` is [Option#none] — a unit
    /// test, a programmatic publisher, or a slice not deployed under a blueprint. There is no
    /// deployment behind those and therefore no second spelling to disagree with, so the slice's own
    /// identity is the only one in play and both ends still derive it identically. See
    /// [OwningBlueprintResolver] for why this absence is benign here and fatal on the stream path.
    public static Result<ResourceAddress> resolve(Option<Artifact> owningBlueprint,
                                                  Artifact sliceArtifact,
                                                  String declared) {
        return resolve(owningBlueprint.or(sliceArtifact), declared);
    }

    /// Resolve a raw declared topic string against an explicit namespacing `scope`.
    ///
    /// The namespace comes from the artifact handed in and NOTHING ELSE — this method cannot tell a
    /// blueprint artifact from a slice artifact, which is exactly how #1216 stayed invisible. Prefer
    /// [#resolve(Option, Artifact, String)], which names both and makes the scope a decision rather
    /// than an accident. Direct use is correct only where the caller already holds a genuine blueprint
    /// artifact.
    public static Result<ResourceAddress> resolve(Artifact scope, String declared) {
        return isNamespaced(declared)
               ? ResourceAddress.resourceAddress(declared)
               : BlueprintNamespace.deriveNamespace(scope).flatMap(namespace -> ResourceAddress.resourceAddress(namespace,
                                                                                                                declared,
                                                                                                                ResourceVersion.defaultVersion()));
    }

    private static boolean isNamespaced(String declared) {
        return Option.option(declared)
                     .map(value -> value.contains(":"))
                     .or(false);
    }
}
