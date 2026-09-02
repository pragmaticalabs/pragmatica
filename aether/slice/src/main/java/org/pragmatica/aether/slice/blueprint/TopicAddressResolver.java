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
///  - a bare/legacy topic name derives its namespace from the owning slice's blueprint Maven
///    coordinates via [BlueprintNamespace#deriveNamespace(Artifact)] and defaults the version to
///    [ResourceVersion#defaultVersion] (`1.0.0`).
public final class TopicAddressResolver {
    private TopicAddressResolver() {}

    /// Resolve a raw declared topic string against the owning slice's [Artifact].
    public static Result<ResourceAddress> resolve(Artifact artifact, String declared) {
        return isNamespaced(declared)
               ? ResourceAddress.resourceAddress(declared)
               : BlueprintNamespace.deriveNamespace(artifact).flatMap(namespace -> ResourceAddress.resourceAddress(namespace,
                                                                                                                   declared,
                                                                                                                   ResourceVersion.defaultVersion()));
    }

    private static boolean isNamespaced(String declared) {
        return Option.option(declared)
                     .map(value -> value.contains(":"))
                     .or(false);
    }
}
