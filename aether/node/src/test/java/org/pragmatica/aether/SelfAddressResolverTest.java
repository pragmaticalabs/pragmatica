// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimTransport;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

class SelfAddressResolverTest {

    private static final NodeId SELF = NodeId.nodeId("node-self").expect("valid id");
    private static final String FALLBACK_HOST = "fallback.invalid";

    private static NodeInfo seed(String id, String host, int port) {
        return NodeInfo.nodeInfo(NodeId.nodeId(id).expect("valid id"),
                                 nodeAddress(host, port).expect("valid address"));
    }

    private static SelfAddressResolver resolverWith(SelfAddressResolver.Transports transports) {
        var serializer = new NeverInvokedSerializer();

        return SelfAddressResolver.selfAddressResolver(serializer, serializer, GossipEncryptor.none(), transports);
    }

    @Nested
    class OverridePath {

        @Test
        void resolve_returnsOverride_withoutTouchingTransport() {
            var transportTouched = new AtomicBoolean(false);
            SelfAddressResolver.Transports transports = (s, d, e) -> {
                transportTouched.set(true);
                return Result.success(new EchoingTransport());
            };

            var host = resolverWith(transports).resolve(SELF,
                                                        8090,
                                                        List.of(seed("node-a", "10.0.0.1", 8090)),
                                                        Option.some("203.0.113.7"),
                                                        () -> FALLBACK_HOST);

            assertEquals("203.0.113.7", host);
            assertFalse(transportTouched.get(), "override path must not construct a transport");
        }

        @Test
        void resolve_ignoresBlankOverride_fallsThrough() {
            var host = resolverWith((s, d, e) -> Result.success(new EchoingTransport()))
                .resolve(SELF, 8090, List.of(), Option.some("   "), () -> FALLBACK_HOST);

            assertEquals(FALLBACK_HOST, host, "blank override is not an override; with no seeds, fallback wins");
        }
    }

    @Nested
    class ReflectionPath {

        @Test
        void resolve_returnsObservedIp_whenSeedEchoesReply() {
            var observed = new InetSocketAddress("198.51.100.42", 40000);
            SelfAddressResolver.Transports transports = (s, d, e) -> Result.success(new EchoingTransport(observed));

            var host = resolverWith(transports).resolve(SELF,
                                                        8090,
                                                        List.of(seed("node-a", "10.0.0.1", 8090)),
                                                        Option.none(),
                                                        () -> FALLBACK_HOST);

            assertEquals("198.51.100.42", host, "reflection yields the seed-observed IP, not the local hostname");
        }

        @Test
        void resolve_sendsWhoAmIToSeedSwimPort() {
            var transport = new EchoingTransport(new InetSocketAddress("198.51.100.42", 40000));

            resolverWith((s, d, e) -> Result.success(transport)).resolve(SELF,
                                                                         8090,
                                                                         List.of(seed("node-a", "10.0.0.1", 8090)),
                                                                         Option.none(),
                                                                         () -> FALLBACK_HOST);

            var sent = transport.lastTarget.get();
            assertEquals(8090 + SelfAddressResolver.SWIM_PORT_OFFSET, sent.getPort(),
                         "WhoAmI must target the seed's SWIM port (clusterPort + offset)");
            assertEquals("10.0.0.1", sent.getHostString());
        }

        @Test
        void resolve_stopsTransport_afterReflection() {
            var transport = new EchoingTransport(new InetSocketAddress("198.51.100.42", 40000));

            resolverWith((s, d, e) -> Result.success(transport)).resolve(SELF,
                                                                         8090,
                                                                         List.of(seed("node-a", "10.0.0.1", 8090)),
                                                                         Option.none(),
                                                                         () -> FALLBACK_HOST);

            assertTrue(transport.stopped.get(), "the transient transport must always be stopped");
        }
    }

    @Nested
    class FallbackPath {

        @Test
        void resolve_returnsHostname_whenNoSeedReplies() {
            SelfAddressResolver.Transports transports = (s, d, e) -> Result.success(new SilentTransport());

            var host = resolverWith(transports).resolve(SELF,
                                                        8090,
                                                        List.of(seed("node-a", "10.0.0.1", 8090)),
                                                        Option.none(),
                                                        () -> FALLBACK_HOST);

            assertEquals(FALLBACK_HOST, host, "no reply within the timeout falls back to the local hostname");
        }

        @Test
        void resolve_returnsHostname_whenTransportFailsToStart() {
            var stopped = new AtomicBoolean(false);
            SelfAddressResolver.Transports transports = (s, d, e) -> Result.success(new FailingStartTransport(stopped));

            var host = resolverWith(transports).resolve(SELF,
                                                        8090,
                                                        List.of(seed("node-a", "10.0.0.1", 8090)),
                                                        Option.none(),
                                                        () -> FALLBACK_HOST);

            assertEquals(FALLBACK_HOST, host);
        }

        @Test
        void resolve_returnsHostname_whenNoSeeds() {
            var transportTouched = new AtomicBoolean(false);
            SelfAddressResolver.Transports transports = (s, d, e) -> {
                transportTouched.set(true);
                return Result.success(new EchoingTransport());
            };

            var host = resolverWith(transports).resolve(SELF, 8090, List.of(), Option.none(), () -> FALLBACK_HOST);

            assertEquals(FALLBACK_HOST, host);
            assertFalse(transportTouched.get(), "no seeds means no transport is constructed");
        }
    }

    // -- Test infrastructure --

    /// Transport double that echoes a [SwimMessage.WhoAmIReply] back through the captured handler
    /// the moment a [SwimMessage.WhoAmI] is sent, simulating a live seed answering the reflection.
    private static final class EchoingTransport implements SwimTransport {
        private final AtomicReference<SwimMessageHandler> handler = new AtomicReference<>();
        private final AtomicReference<InetSocketAddress> lastTarget = new AtomicReference<>();
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final InetSocketAddress observed;

        private EchoingTransport() {
            this(new InetSocketAddress("198.51.100.1", 40000));
        }

        private EchoingTransport(InetSocketAddress observed) {
            this.observed = observed;
        }

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            lastTarget.set(target);
            Option.option(handler.get())
                  .onPresent(h -> h.onMessage(target, SwimMessage.WhoAmIReply.whoAmIReply(observed)));
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler messageHandler) {
            handler.set(messageHandler);
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            stopped.set(true);
            return Promise.unitPromise();
        }
    }

    /// Accepts the probe but never replies — drives the per-attempt timeout, hence the fallback.
    private static final class SilentTransport implements SwimTransport {
        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }
    }

    /// Fails to bind — exercises the transport-start-failure arm of the reflection path.
    private record FailingStartTransport(AtomicBoolean stopped) implements SwimTransport {
        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            return Causes.cause("bind refused").promise();
        }

        @Override
        public Promise<Unit> stop() {
            stopped.set(true);
            return Promise.unitPromise();
        }
    }

    /// Serializer/Deserializer the doubles never invoke (they echo via the handler directly).
    private static final class NeverInvokedSerializer implements Serializer, Deserializer {
        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            // never invoked: the test transports do not serialize
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(ByteBuf byteBuf) {
            return (T) SwimMessage.WhoAmI.whoAmI(SELF);
        }
    }
}
