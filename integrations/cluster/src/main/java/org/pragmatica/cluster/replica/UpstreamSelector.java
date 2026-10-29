// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.List;
import java.util.Set;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// The only place that decides which source a replica follows (I-W8; the silence switch goes through it too). W3 knows cores only: the
/// candidates are the node's deterministic core uplinks, in their fixed order. The governor arm arrives with the relay.
///
/// The rule: keep the current source while it is among the uplinks and not avoided (it was silent, refused, or stopped serving); otherwise the
/// first uplink not avoided; none if every uplink is avoided.
public sealed interface UpstreamSelector {
    static Option<NodeId> select(List<NodeId> uplinks, Option<NodeId> current, Set<NodeId> avoided) {
        return current.filter(uplinks::contains)
                      .filter(source -> !avoided.contains(source))
                      .orElse(() -> firstUnavoided(uplinks, avoided));
    }

    private static Option<NodeId> firstUnavoided(List<NodeId> uplinks, Set<NodeId> avoided) {
        return Option.from(uplinks.stream().filter(uplink -> !avoided.contains(uplink)).findFirst());
    }

    record unused() implements UpstreamSelector {}
}
