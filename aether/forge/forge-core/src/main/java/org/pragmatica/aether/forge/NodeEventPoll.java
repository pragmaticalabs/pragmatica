// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.pragmatica.aether.forge.api.NodeHttp;
import org.pragmatica.aether.forge.api.OperatorKey;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;


/// Forge's own poll of the cluster event timeline (`ForgeServer.pollNodeEvents`, every 2 s).
///
/// #1105 follow-up (v1533 F1): the poll carries the operator key like every proxied call ([NodeHttp]).
/// `/api/v1/events` has no auth exemption, so under `API_KEY` a keyless poll was refused on every tick and the
/// dashboard's event timeline stayed silently empty.
///
/// v1533 C1: the [NodeHttp], and with it one `HttpClient`, is built ONCE per poll instance, not per fetch. A client
/// per fetch left a live selector thread per 2 s tick until a GC reclaimed it (140 after 150 polls).
@FunctionalInterface
interface NodeEventPoll {
    Result<String> fetch(int port, String since);

    static NodeEventPoll nodeEventPoll(OperatorKey operatorKey) {
        var nodeHttp = NodeHttp.nodeHttp(operatorKey);

        return (port, since) -> fetch(nodeHttp, port, since);
    }

    private static Result<String> fetch(NodeHttp nodeHttp, int port, String since) {
        var path = since.isEmpty()
                   ? "/api/v1/events"
                   : "/api/v1/events?since=" + URLEncoder.encode(since, StandardCharsets.UTF_8);
        var request = nodeHttp.request(port, path).GET().timeout(Duration.ofSeconds(2)).build();

        return nodeHttp.sendString(request)
                       .await(TimeSpan.timeSpan(3).seconds())
                       .flatMap(HttpResult::toResult);
    }
}
