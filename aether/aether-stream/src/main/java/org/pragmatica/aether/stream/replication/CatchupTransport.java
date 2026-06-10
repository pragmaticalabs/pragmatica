// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;

import java.util.List;

import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;


@FunctionalInterface
public interface CatchupTransport {
    Promise<CatchupResponse> requestCatchup(NodeId target, ReplicationMessage.CatchupRequest request);

    CatchupTransport NOOP = (_, request) -> Promise.success(catchupResponse(request.replicaId(),
                                                                            request.streamName(),
                                                                            request.partition(),
                                                                            request.fromOffset(),
                                                                            request.fromOffset(),
                                                                            List.of(),
                                                                            List.of()));
}
