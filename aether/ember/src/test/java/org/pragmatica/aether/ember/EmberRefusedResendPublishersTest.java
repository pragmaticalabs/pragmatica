// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2916: the warm-up publish of [EmberReplicateRefusedResendTest] retries the owner's transient 503 "not yet promoted", the
/// armed publish never does. A stub management endpoint answers that 503 for the first N requests, so both halves are
/// pinned without a cluster: a retry on the armed publish would hide the refusal the scenario is about.
class EmberRefusedResendPublishersTest {
    private static final String NOT_PROMOTED = "refused before writing, retry: Stream partition x[0] is not yet promoted on this node (fresh ownership view and catch-up pending)";

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void warmUpPublish_retriesTheNotYetPromotedRefusal_untilAccepted() throws Exception {
        var port = startStub(1);

        assertThat(EmberReplicateRefusedResendTest.warmUpPublish(port, "warm-0")).startsWith("2");
        assertThat(requests.get()).as("one refusal, then the accepted retry").isEqualTo(2);
    }

    @Test
    void armedPublish_isOneAttempt_andSurfacesTheNotYetPromotedRefusal() throws Exception {
        var port = startStub(1);

        assertThat(EmberReplicateRefusedResendTest.publishResponse(port, "refused-0")).startsWith("503").contains("not yet promoted");
        assertThat(requests.get()).as("no retry on the armed publish").isEqualTo(1);
    }

    private int startStub(int refusals) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var refuse = requests.incrementAndGet() <= refusals;
            var body = (refuse ? NOT_PROMOTED : "{}").getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(refuse ? 503 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        return server.getAddress().getPort();
    }
}
