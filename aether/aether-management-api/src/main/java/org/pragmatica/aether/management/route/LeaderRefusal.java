// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.management.route;

/// The stable wording of the two typed refusals the management API answers (HTTP 409) when a drain or a shutdown targets
/// the current leader. The problem body carries no machine-readable code, so the server builds its message from these
/// constants and a client recognises the refusal by the same constants: one definition, two readers.
public interface LeaderRefusal {
    /// Ends the node id in the refusal of a leader drain: `Cannot drain node <id>` + this text.
    String DRAIN_REASON = ": it is the current leader or the node handling this request, and such a node is not drained.";

    /// Ends the node id in the refusal of a leader shutdown: `Cannot shut down node <id>` + this text.
    String SHUTDOWN_REASON = ": it is the current leader or the node handling this request, and such a node is not shut down through the API.";

    /// True when a 409 problem body is the typed refusal of a drain or a shutdown of the leader.
    static boolean isLeaderRefusal(String body) {
        return body.contains(DRAIN_REASON) || body.contains(SHUTDOWN_REASON);
    }

    record unused() implements LeaderRefusal {}
}
