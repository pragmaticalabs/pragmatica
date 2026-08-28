// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;


/// Version-stable consumer-group identity (durable-pubsub-spec §6): the subscriber group with the
/// artifact VERSION stripped — `groupId:artifactId#method` — so a slice upgrade keeps its cursor
/// and its DLQ attribution instead of reprocessing history on every deploy. The registry's own
/// group key embeds the full versioned artifact; the durable dispatch path maps it through here.
///
/// A cursor orphaned by a RENAME (not an upgrade) is not detectable at this level — it surfaces on
/// the §9 lag surface and is cleaned by operator action, per the spec's stated disposition.
public sealed interface DurableGroupIdentity {
    static String groupId(Artifact subscriber, MethodName method) {
        return subscriber.base()
                         .asString() + "#" + method.name();
    }

    record unused() implements DurableGroupIdentity {}
}
