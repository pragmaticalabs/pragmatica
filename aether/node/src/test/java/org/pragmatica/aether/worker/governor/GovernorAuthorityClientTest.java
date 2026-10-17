// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GovernorAuthorityClientTest {
    private static final NodeId CORE = new NodeId("core-1");
    private static final NodeId OTHER_CORE = new NodeId("core-2");
    private static final NodeId WORKER = new NodeId("worker-1");

    @Test
    void response_requiresOriginalLeaderCommunityAndRequestId() {
        var sent = new ArrayList<NodeId>();
        var client = GovernorAuthorityClient.governorAuthorityClient(() -> Option.some(CORE),
                                                                      (target, _) -> sent.add(target));
        var promise = client.request(request(10));
        var accepted = new AtomicReference<GovernorAuthorityMessage.Response>();
        promise.onSuccess(accepted::set);
        client.onResponse(response(OTHER_CORE, "community", 10));
        client.onResponse(response(CORE, "another-community", 10));
        client.onResponse(response(CORE, "community", 11));
        assertThat(accepted.get()).isNull();
        var expected = response(CORE, "community", 10);
        client.onResponse(expected);
        assertThat(promise.await().unwrap()).isEqualTo(expected);
        assertThat(sent).containsExactly(CORE);
    }

    @Test
    void absentLeader_rejectsWithoutSending() {
        var sent = new ArrayList<NodeId>();
        var client = GovernorAuthorityClient.governorAuthorityClient(Option::none,
                                                                      (target, _) -> sent.add(target));
        assertThat(client.request(request(20)).await().isFailure()).isTrue();
        assertThat(sent).isEmpty();
    }

    @Test
    void duplicatePendingId_doesNotReplaceOriginalCorrelation() {
        var sent = new ArrayList<NodeId>();
        var client = GovernorAuthorityClient.governorAuthorityClient(() -> Option.some(CORE),
                                                                      (target, _) -> sent.add(target));
        var original = client.request(request(30));
        assertThat(client.request(request(30)).await().isFailure()).isTrue();
        var expected = response(CORE, "community", 30);
        client.onResponse(expected);
        assertThat(original.await().unwrap()).isEqualTo(expected);
        assertThat(sent).containsExactly(CORE);
    }

    private static GovernorAuthorityMessage.Request request(long id) {
        return new GovernorAuthorityMessage.Request(WORKER, "community", id, 0, "host:9000");
    }

    private static GovernorAuthorityMessage.Response response(NodeId sender, String community, long id) {
        return new GovernorAuthorityMessage.Response(sender, community, id, Option.none());
    }
}
