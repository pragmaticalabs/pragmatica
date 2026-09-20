// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;

import org.pragmatica.aether.api.routes.StreamRoutes.JoinGroupRequest;
import org.pragmatica.aether.api.routes.StreamRoutes.LeaveGroupRequest;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #742 — the legacy flat `POST /streams/groups/join` / `.../leave` (`StreamRoutes#joinGroup` /
/// `#leaveGroup`) carry their target stream name in the request body, so `ManagementServer`'s
/// pre-auth write-gate structurally cannot see it (gate condition 1: no parallel body parser), and
/// until now nothing between the HTTP boundary and `ConsumerGroupCoordinator` checked it against
/// [org.pragmatica.aether.slice.stream.SystemStreams]. The coordinator's `joinGroup`/`leaveGroup`
/// both `rebalance`, which proposes real, replicated `KVCommand.Put` assignment records under the
/// named stream — so a caller could commit durable consumer-group state against `cluster-events`.
/// Same shape and same predicate as `#createStreamWithConfig`'s guard (`SystemStreams.isForbiddenEngineKey`),
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
        return StreamRoutes.streamRoutes(StreamRoutesGroupSystemStreamTest::nodeStub,
                                         ConsumerGroupCoordinator.noOp(),
                                         null);
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

    /// #742 review SF-2: the versioned gate canonicalizes `system:cluster-events:1.0.0` to the engine
    /// key `cluster-events` before the predicate; the legacy guard applied the predicate to the raw
    /// body string, so the catalog spelling walked past it and the coordinator proposed the very
    /// `ConsumerGroupKey(…, "system:cluster-events:1.0.0", i)` records the versioned gate refuses.
    @Test
    void joinAndLeave_catalogSpellingOfASystemStream_areRejectedBeforeTheCoordinatorIsReached() {
        var join = routes().joinGroup(new JoinGroupRequest("g1", "system:cluster-events:1.0.0", 4, "c1"));
        var leave = routes().leaveGroup(new LeaveGroupRequest("g1", "system:cluster-events:1.0.0", "c1"));

        assertThat(failureMessage(join)).containsIgnoringCase("system stream")
                  .doesNotContainIgnoringCase(COORDINATOR_REACHED);
        assertThat(failureMessage(leave)).containsIgnoringCase("system stream")
                  .doesNotContainIgnoringCase(COORDINATOR_REACHED);
    }

    /// #742 review SF-3: a plain `Causes.cause` renders as HTTP 500 through `ProblemResponses`; the
    /// versioned gate answers 405. The guard's refusal must carry the same status.
    @Test
    void guardRefusal_carriesTheVersionedGatesStatus_notA500() {
        var result = routes().joinGroup(new JoinGroupRequest("g1", "cluster-events", 4, "c1"));

        result.onSuccess(_ -> fail("must be refused"));
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(HttpStatusAware.class);
            assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        });
    }

    /// #742 review N-2: a missing `streamName` passed the predicate (`equals(null)` is false) and
    /// reached the coordinator; CREATE already refuses it as `Missing stream name`.
    @Test
    void joinAndLeave_missingStreamName_areRefused_notForwarded() {
        var join = routes().joinGroup(new JoinGroupRequest("g1", null, 4, "c1"));
        var leave = routes().leaveGroup(new LeaveGroupRequest("g1", " ", "c1"));

        assertThat(failureMessage(join)).containsIgnoringCase("missing stream name");
        assertThat(failureMessage(leave)).containsIgnoringCase("missing stream name");
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
