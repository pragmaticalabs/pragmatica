// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;

import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;


@FunctionalInterface
public interface CatchupTransport {
    Promise<CatchupResponse> requestCatchup(NodeId target, ReplicationMessage.CatchupRequest request);

    /// [#requestCatchup(NodeId, ReplicationMessage.CatchupRequest)], reporting each page received through `onPage`
    /// (#1638 B2), so a long catch-up that keeps moving is seen as progressing. A transport that does not page reports
    /// nothing until its one response.
    default Promise<CatchupResponse> requestCatchup(NodeId target,
                                                    ReplicationMessage.CatchupRequest request,
                                                    Runnable onPage) {
        return requestCatchup(target, request);
    }

    /// ONE page of the source's log from `request.fromOffset()`, never the whole suffix: a read that only needs the page start (#3080,
    /// the activation gate learning where a responder's committed lineage ends) must not cost the source's full tail. A transport that
    /// does not page answers exactly as [#requestCatchup(NodeId, ReplicationMessage.CatchupRequest)].
    default Promise<CatchupResponse> requestPage(NodeId target, ReplicationMessage.CatchupRequest request) {
        return requestCatchup(target, request);
    }

    CatchupTransport NOOP = (_, request) -> Promise.success(catchupResponse(request.replicaId(),
                                                                            request.streamName(),
                                                                            request.partition(),
                                                                            request.fromOffset(),
                                                                            request.fromOffset(),
                                                                            List.of(),
                                                                            List.of()));
}
