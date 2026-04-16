// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.bootstrap;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;


/// Request sent by a bootstrapping worker to obtain KV state snapshot.
///
/// @param requester the NodeId of the worker requesting the snapshot
@Codec public record SnapshotRequest(NodeId requester) implements Message.Wired {
    public static SnapshotRequest snapshotRequest(NodeId requester) {
        return new SnapshotRequest(requester);
    }
}
