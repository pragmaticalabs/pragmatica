// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.FrameworkCodecs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #1723: a REMOTE scheduled-task fire used to resolve the moment the request was handed to the transport, so a
/// message the transport lost, or a callee that failed, still read as a successful execution and was counted in the
/// task's history. `invokeAwaitingCompletion` asks the callee for a response and settles with it: success only when the
/// callee answered success, failure on a callee failure and on a response that never arrives.
class SliceInvokerAwaitCompletionTest {
    private static final NodeId SELF = new NodeId("leader");
    private static final NodeId HOST = new NodeId("host");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:scheduled-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("heartbeat").unwrap();
    /// Short, so the dropped-message case settles quickly.
    private static final long TIMEOUT_MS = 300L;

    private final CapturingNetwork network = new CapturingNetwork();
    private SliceInvoker invoker;

    @BeforeEach
    void setUp() {
        var registry = EndpointRegistry.endpointRegistry();
        var handler = InvocationHandler.invocationHandler(SELF, network);
        var nodeCodec = FrameworkCodecs.frameworkCodecs();

        registry.registerEndpoint(new EndpointKey(ARTIFACT, METHOD, 0), EndpointValue.endpointValue(HOST));
        invoker = SliceInvoker.sliceInvoker(SELF, network, registry, handler, nodeCodec, nodeCodec, TIMEOUT_MS, 60_000L, new StubDeploymentManager());
    }

    @AfterEach
    void tearDown() {
        invoker.stop().await();
    }

    @Test
    @Timeout(30)
    void remoteFire_isNotCompleteWhenOnlyEnqueued_andSucceedsWhenTheCalleeAnswersSuccess() {
        var completion = invoker.invokeAwaitingCompletion(ARTIFACT, METHOD, unit());

        awaitSent();
        assertThat(network.sent.get()).as("the request reached the transport").isNotNull();
        assertThat(network.sent.get().expectResponse()).as("it asks the callee for a response").isTrue();
        assertThat(completion.isResolved()).as("handing the request to the transport is not completion").isFalse();

        invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST, network.sent.get().correlationId(), "r", true, new byte[0]));

        assertThat(completion.await().isSuccess()).as("the callee answered success").isTrue();
    }

    @Test
    @Timeout(30)
    void remoteFire_failsWhenTheCalleeFails() {
        var completion = invoker.invokeAwaitingCompletion(ARTIFACT, METHOD, unit());

        awaitSent();
        invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST,
                                                               network.sent.get().correlationId(),
                                                               "r",
                                                               false,
                                                               "callee blew up".getBytes(StandardCharsets.UTF_8)));

        completion.await().onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a callee failure must fail the fire"))
                  .onFailure(cause -> assertThat(cause.message()).contains("callee blew up"));
    }

    /// A lost message (a transport that silently discards on a peer reset, as seen in #1677) produces no response at
    /// all: the fire must fail on the invocation timeout rather than read as executed.
    @Test
    @Timeout(30)
    void remoteFire_failsWhenTheMessageIsLost() {
        invoker.invokeAwaitingCompletion(ARTIFACT, METHOD, unit())
               .await()
               .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a fire whose request was lost must not read as executed"));
    }

    /// Control: the plain fire-and-forget `invoke` (durable-topic publish and the like) is unchanged and still resolves
    /// when the request is handed to the transport.
    @Test
    @Timeout(30)
    void plainInvoke_stillResolvesOnEnqueue() {
        assertThat(invoker.invoke(ARTIFACT, METHOD, unit()).await().isSuccess()).isTrue();
        awaitSent();
        assertThat(network.sent.get().expectResponse()).isFalse();
    }

    private void awaitSent() {
        var deadline = System.currentTimeMillis() + 5_000L;

        while (network.sent.get() == null && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static final class CapturingNetwork extends StubClusterNetwork {
        private final AtomicReference<InvokeRequest> sent = new AtomicReference<>();

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            if (message instanceof InvokeRequest request) {
                sent.set(request);
            }

            return unit();
        }
    }
}
