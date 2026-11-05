// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.List;
import java.util.Set;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// The only place that decides which source a replica follows (I-W8; the silence switch goes through it too). The candidates are the node's
/// governor, when it has one it may follow, then its deterministic core uplinks in their fixed order. A node that is itself the governor passes
/// no governor, so it follows a core (the election hand-off, spec 3.3).
///
/// The rule: a governor that is not avoided wins; otherwise keep the current source while it is among the uplinks and not avoided; otherwise
/// the first uplink not avoided; none if every candidate is avoided. Whether the governor is serving, on the same tag, able to verify the
/// anchor and not behind this node is NOT decided here: the governor answers a subscribe with a refusal or a grant, a refusal or a silent
/// governor puts it into `avoided`, and the next call falls through to a core.
public sealed interface UpstreamSelector {
    static Option<NodeId> select(List<NodeId> uplinks,
                                 Option<NodeId> governor,
                                 Option<NodeId> current,
                                 Set<NodeId> avoided) {
        return governor.filter(candidate -> !avoided.contains(candidate))
                       .orElse(() -> currentCore(uplinks, current, avoided))
                       .orElse(() -> firstUnavoided(uplinks, avoided));
    }

    private static Option<NodeId> currentCore(List<NodeId> uplinks, Option<NodeId> current, Set<NodeId> avoided) {
        return current.filter(uplinks::contains)
                      .filter(source -> !avoided.contains(source));
    }

    private static Option<NodeId> firstUnavoided(List<NodeId> uplinks, Set<NodeId> avoided) {
        return Option.from(uplinks.stream().filter(uplink -> !avoided.contains(uplink)).findFirst());
    }

    record unused() implements UpstreamSelector {}
}
