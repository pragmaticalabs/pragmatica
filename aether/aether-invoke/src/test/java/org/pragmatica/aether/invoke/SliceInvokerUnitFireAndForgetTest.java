// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Unit.unit;
import static org.assertj.core.api.Assertions.assertThat;


/// #272 R12: a `Unit` fire-and-forget from a node that hosts NO slice must reach the transport with
/// `Unit`'s fixed wire form — the single VLQ byte `TAG_UNIT` (0) and an empty body — encoded by the node
/// codec, not by a slice bridge. The callee reads the same byte back as `Unit` through the framework
/// codec every slice codec layers over (`FrameworkCodecs.unitCodec`), so nothing new crosses the wire.
/// Any other request type still needs a sender bridge: the control keeps SENDER_BRIDGE_NOT_FOUND for it.
class SliceInvokerUnitFireAndForgetTest {
    private static final NodeId SELF = new NodeId("leader");
    private static final NodeId HOST = new NodeId("host");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:scheduled-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("heartbeat").unwrap();

    private static final byte[] UNIT_WIRE = {(byte) SliceCodec.TAG_UNIT};

    private final CapturingNetwork network = new CapturingNetwork();
    private SliceInvoker invoker;

    @BeforeEach
    void setUp() {
        var registry = EndpointRegistry.endpointRegistry();
        var handler = InvocationHandler.invocationHandler(SELF, network);
        var nodeCodec = FrameworkCodecs.frameworkCodecs();
        // No slice registered locally: this node hosts nothing.
        registry.registerEndpoint(new EndpointKey(ARTIFACT, METHOD, 0), EndpointValue.endpointValue(HOST));
        invoker = SliceInvoker.sliceInvoker(SELF,
                                            network,
                                            registry,
                                            handler,
                                            nodeCodec,
                                            nodeCodec,
                                            200L,
                                            60_000L,
                                            new StubDeploymentManager());
    }

    @AfterEach
    void tearDown() {
        invoker.stop().await();
    }

    @Test
    void unitFireAndForget_fromNonHostingNode_reachesTheTransportWithUnitsWireForm() {
        var result = invoker.invoke(ARTIFACT, METHOD, unit()).await();

        assertThat(result.isSuccess()).as("#272 R12: a Unit fire-and-forget needs no local slice bridge (was: %s)",
                                          result)
                  .isTrue();
        assertThat(network.sent.get()).as("the request must have been handed to the transport").isNotNull();
        assertThat(network.sent.get().payload()).as("the payload is Unit's fixed encoding: the single tag byte TAG_UNIT, empty body")
                  .isEqualTo(UNIT_WIRE);
        assertThat(network.target.get()).isEqualTo(HOST);
        assertThat(FrameworkCodecs.frameworkCodecs().<Object> decode(network.sent.get().payload())).as("control: the framework codec every slice codec layers over reads it back as Unit")
                  .isEqualTo(unit());
    }

    /// Control: the bridge-free path is Unit-only. A request the node codec cannot encode without the
    /// slice's own codec still fails at the sender, never reaching the transport.
    @Test
    void nonUnitFireAndForget_fromNonHostingNode_stillRequiresASenderBridge() {
        var result = invoker.invoke(ARTIFACT, METHOD, new Object()).await();

        assertThat(result.isFailure()).isTrue();
        String message = result.fold(cause -> cause.message(), _ -> "");

        assertThat(message).contains("No local SliceBridge");
        assertThat(network.sent.get()).as("nothing reached the transport").isNull();
    }

    private static final class CapturingNetwork extends StubClusterNetwork {
        private final AtomicReference<InvokeRequest> sent = new AtomicReference<>();
        private final AtomicReference<NodeId> target = new AtomicReference<>();

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            if (message instanceof InvokeRequest request) {
                sent.set(request);
                target.set(nodeId);
            }

            return unit();
        }
    }
}
