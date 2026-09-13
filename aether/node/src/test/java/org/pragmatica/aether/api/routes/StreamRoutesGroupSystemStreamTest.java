// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.routes.StreamRoutes.JoinGroupRequest;
import org.pragmatica.aether.api.routes.StreamRoutes.LeaveGroupRequest;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #742 — the legacy flat `POST /streams/groups/join` / `.../leave` (`StreamRoutes#joinGroup` /
/// `#leaveGroup`) carry their target stream name in the request body, so `ManagementServer`'s
/// pre-auth write-gate structurally cannot see it (gate condition 1: no parallel body parser), and
/// until now nothing between the HTTP boundary and `ConsumerGroupCoordinator` checked it against
/// [org.pragmatica.aether.slice.stream.SystemStreams]. The coordinator's `joinGroup`/`leaveGroup`
/// both `rebalance`, which proposes real, replicated `KVCommand.Put` assignment records under the
/// named stream — so a caller could commit durable consumer-group state against `cluster-events`.
/// Same shape and same predicate as `#createFreshStream`'s guard (`SystemStreams.isForbiddenEngineKey`),
/// pinned the same way: with full privileges, naming a framework stream in the body.
///
/// `ConsumerGroupCoordinator` is sealed, so the harness uses the real `noOp()` coordinator, which
/// answers every call with `NOT_LEADER`. That makes the two outcomes mutually exclusive by cause:
/// the guard's own refusal never mentions the leader, and `NOT_LEADER` proves the coordinator WAS
/// reached — which is what the ordinary-name control asserts.
class StreamRoutesGroupSystemStreamTest {
    private static final String COORDINATOR_REACHED = "not leader";

    private static ManageableNode nodeStub() {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "self" -> NodeId.randomNodeId("test");
                                                           default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method.getName());
                                                       });
    }

    private static StreamRoutes routes() {
        return StreamRoutes.streamRoutes(StreamRoutesGroupSystemStreamTest::nodeStub, ConsumerGroupCoordinator.noOp(), null);
    }

    private static String failureMessage(Result<?> result) {
        return result.fold(Cause::message, _ -> fail("expected a failure, got success"));
    }

    @Test
    void joinGroup_reservedSystemStreamName_isRejectedBeforeTheCoordinatorIsReached() {
        var result = routes().joinGroup(new JoinGroupRequest("g1", "cluster-events", 4, "c1"));

        assertThat(failureMessage(result)).as("the guard must refuse in its own words, BEFORE the coordinator — a "
                                              + "NOT_LEADER here would mean the call reached rebalance")
                                          .containsIgnoringCase("system stream")
                                          .doesNotContainIgnoringCase(COORDINATOR_REACHED);
    }

    @Test
    void leaveGroup_reservedSystemStreamName_isRejectedBeforeTheCoordinatorIsReached() {
        var result = routes().leaveGroup(new LeaveGroupRequest("g1", "cluster-events", "c1"));

        assertThat(failureMessage(result)).containsIgnoringCase("system stream")
                                          .doesNotContainIgnoringCase(COORDINATOR_REACHED);
    }

    @Test
    void joinAndLeave_ordinaryAppStreamName_stillReachTheCoordinator() {
        var join = routes().joinGroup(new JoinGroupRequest("g1", "orders", 4, "c1"));
        var leave = routes().leaveGroup(new LeaveGroupRequest("g1", "orders", "c1"));

        assertThat(failureMessage(join)).as("an ordinary application stream must pass the guard and reach the "
                                            + "coordinator, whose noOp answer is NOT_LEADER")
                                        .containsIgnoringCase(COORDINATOR_REACHED);
        assertThat(failureMessage(leave)).containsIgnoringCase(COORDINATOR_REACHED);
    }
}
