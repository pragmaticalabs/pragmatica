// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.http.forward.AccessibilityFilter;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Unit.unit;
import static org.assertj.core.api.Assertions.assertThat;


/// #275 (finding 1): slice-to-slice endpoint selection must consult membership liveness. The
/// `EndpointRegistry` is a projection of committed `NodeArtifactKey` rows, which outlive a dead node
/// until the CDM's removal cleanup lands, so without a filter a new invocation is round-robined onto
/// the dead node and hangs until the invocation timeout. The HTTP forward path already narrows its
/// candidates through `AccessibilityFilter` (`MembershipFsm.reachableMembers`); this pins the same
/// narrowing on the invoker's own selection paths: plain round-robin, cache affinity and failover.
///
/// The observable is the target node of the `InvokeRequest` the invoker hands to the transport —
/// selection itself is private. Both endpoints are remote so every path takes the send arm; the
/// sender bridge is the local-slice registry's, as `InvocationDeadlineCapTest` does it. Each call is
/// awaited (nothing answers, so a request/response call settles at the 200ms timeout) so the send
/// count is exact rather than a race with the encode continuation.
class SliceInvokerLivenessFilterTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId DEAD = new NodeId("dead-node");
    private static final NodeId LIVE = new NodeId("live-node");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:liveness-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("handle").unwrap();
    /// Short enough that the failover case fails over within the test, long enough that a send is
    /// never mistaken for a timeout.
    private static final long TIMEOUT_MS = 200L;
    private static final long CLEANUP_INTERVAL_MS = 60_000L;
    private static final int ROUNDS = 6;

    private final CapturingNetwork network = new CapturingNetwork();
    private final EndpointRegistry registry = EndpointRegistry.endpointRegistry();
    private SliceInvoker invoker;

    @BeforeEach
    void setUp() {
        var handler = InvocationHandler.invocationHandler(SELF, network);

        handler.registerSlice(ARTIFACT, silentBridge());
        registry.registerEndpoint(new EndpointKey(ARTIFACT, METHOD, 0), EndpointValue.endpointValue(DEAD));
        registry.registerEndpoint(new EndpointKey(ARTIFACT, METHOD, 1), EndpointValue.endpointValue(LIVE));
        invoker = SliceInvoker.sliceInvoker(SELF,
                                            network,
                                            registry,
                                            handler,
                                            new StubSerializer(),
                                            new StubDeserializer(),
                                            TIMEOUT_MS,
                                            CLEANUP_INTERVAL_MS,
                                            new StubDeploymentManager());
        invoker.setAccessibilityFilter(rejecting(DEAD));
    }

    @AfterEach
    void tearDown() {
        invoker.stop().await();
    }

    /// Round-robin over [DEAD, LIVE] lands on DEAD every other call without the filter.
    @Test
    void invoke_requestResponse_neverSelectsAnInaccessibleNode() {
        for (int i = 0; i < ROUNDS; i++) {
            var _ = invoker.invoke(ARTIFACT, METHOD, "request-" + i, new TypeToken<String>() {}).await();
        }

        assertThat(network.targets()).as("#275: every request/response send must target a node the accessibility filter keeps")
                  .hasSize(ROUNDS)
                  .containsOnly(LIVE);
    }

    /// Fire-and-forget takes the affinity-first selection path.
    @Test
    void invoke_fireAndForget_neverSelectsAnInaccessibleNode() {
        for (int i = 0; i < ROUNDS; i++) {
            var _ = invoker.invoke(ARTIFACT, METHOD, "request-" + i).await();
        }

        assertThat(network.targets()).as("#275: every fire-and-forget send must target a node the accessibility filter keeps")
                  .hasSize(ROUNDS)
                  .containsOnly(LIVE);
    }

    /// A cache-affinity resolver pointing at the dead node must not pin the call to it.
    @Test
    void invoke_affinityToInaccessibleNode_fallsBackToAnAccessibleOne() {
        invoker.registerAffinityResolver(ARTIFACT, METHOD, _ -> org.pragmatica.lang.Option.some(DEAD));
        for (int i = 0; i < ROUNDS; i++) {
            var _ = invoker.invoke(ARTIFACT, METHOD, "request-" + i, new TypeToken<String>() {}).await();
        }

        assertThat(network.targets()).as("#275: affinity to an inaccessible node must yield to an accessible endpoint")
                  .hasSize(ROUNDS)
                  .containsOnly(LIVE);
    }

    /// Failover after a timeout must not retry onto the dead node either: with LIVE timed out and
    /// DEAD inaccessible there is nothing left, so the call fails without a second send.
    @Test
    void invokeWithRetry_failoverNeverRetriesOntoAnInaccessibleNode() {
        var result = invoker.invokeWithRetry(ARTIFACT, METHOD, "request", new TypeToken<String>() {}, 3).await();

        assertThat(result.isFailure()).as("nothing answers, so the retried call must fail rather than hang").isTrue();
        assertThat(network.targets()).as("#275: the failover arm must never send to an inaccessible node")
                  .isNotEmpty()
                  .containsOnly(LIVE);
    }

    private static AccessibilityFilter rejecting(NodeId rejected) {
        return candidates -> candidates.stream()
                                       .filter(node -> !node.equals(rejected))
                                       .toList();
    }

    /// Records the target node of every `InvokeRequest` the invoker sends; nothing ever answers.
    private static final class CapturingNetwork extends StubClusterNetwork {
        private final List<NodeId> targets = new CopyOnWriteArrayList<>();

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            if (message instanceof InvokeRequest) {
                targets.add(nodeId);
            }

            return unit();
        }

        List<NodeId> targets() {
            return List.copyOf(targets);
        }
    }

    /// `encode` yields an empty payload so the send arm reaches the transport; `invoke` is never
    /// reached because every endpoint is remote.
    private static SliceBridge silentBridge() {
        return new SliceBridge() {
            @Override
            public Promise<byte[]> invoke(String methodName, byte[] input) {
                return Promise.promise();
            }

            @Override
            public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<byte[]> encode(Object input) {
                return Promise.success(new byte[0]);
            }

            @Override
            public ClassLoader classLoader() {
                return SliceInvokerLivenessFilterTest.class.getClassLoader();
            }

            @Override
            public List<String> methodNames() {
                return List.of(METHOD.name());
            }
        };
    }
}
