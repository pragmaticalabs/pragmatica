/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The incident, on real `SwimProtocol` instances over an in-memory datagram network with real timers:
/// cores A, B, C; replacement R1 joins with seeds A, B, C; later replacement R2 joins with seeds A, B, C
/// (NOT R1) — two replacements minted by different leaders, neither ever receiving the other's ANNOUNCE.
/// Each must end up holding the OTHER's `role=core` label. The steady-state gossip about a node is its OWN
/// self-ALIVE, so that update has to carry the node's labels; labelled relays on state changes alone are
/// not enough (measured by v1757 before the fix: R2 saw R1 with no labels for as long as it ran).
class SwimLateJoinerLabelsTest {
    private final Map<InetSocketAddress, SwimTransport.SwimMessageHandler> net = new ConcurrentHashMap<>();
    private final ExecutorService wire = Executors.newSingleThreadExecutor();
    private final List<SwimProtocol> started = new ArrayList<>();

    @AfterEach
    void tearDown() {
        started.forEach(SwimProtocol::stop);
        net.clear();
        wire.shutdownNow();
    }

    @Test
    void lateReplacements_neverAnnouncedToEachOther_learnEachOthersRoleFromSelfGossip() {
        node("core-a", 29001, List.of(29002, 29003));
        node("core-b", 29002, List.of(29001, 29003));
        node("core-c", 29003, List.of(29001, 29002));
        var r1 = node("repl-1", 29011, List.of(29001, 29002, 29003));

        await().pollDelay(2, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(r1.members().values()).hasSizeGreaterThanOrEqualTo(3));
        var r2 = node("repl-2", 29012, List.of(29001, 29002, 29003));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(labelsOf(r2, "repl-1")).as("R2's view of R1").containsEntry(NodeInfo.LABEL_ROLE, "core");
            assertThat(labelsOf(r1, "repl-2")).as("R1's view of R2").containsEntry(NodeInfo.LABEL_ROLE, "core");
        });
    }

    private static Map<String, String> labelsOf(SwimProtocol viewer, String subject) {
        var member = viewer.members().get(new NodeId(subject));

        return member == null ? Map.of("absent", subject) : member.labels();
    }

    private SwimProtocol node(String id, int port, List<Integer> seeds) {
        var config = SwimConfig.swimConfig(timeSpan(100).millis(),
                                           timeSpan(80).millis(),
                                           3,
                                           timeSpan(3).seconds(),
                                           8,
                                           timeSpan(2000).millis(),
                                           "c",
                                           0);
        var address = new InetSocketAddress("127.0.0.1", port);
        var transport = new MemoryTransport(address);
        var protocol = SwimProtocol.swimProtocol(config, transport, new SwimProtocolTest.RecordingListener(), new NodeId(id), address)
                                   .unwrap();
        var seedAddresses = new ArrayList<InetSocketAddress>();

        transport.start(port, protocol::onMessage);
        protocol.start();
        seeds.forEach(seed -> seedAddresses.add(new InetSocketAddress("127.0.0.1", seed)));
        seeds.forEach(seed -> protocol.addSeedMember(new NodeId(seedName(seed)), new InetSocketAddress("127.0.0.1", seed)));
        protocol.announceJoin(selfInfo(id, port), "c", 1L, 1000L + port, seedAddresses);
        started.add(protocol);

        return protocol;
    }

    private static String seedName(int port) {
        return switch (port) {
            case 29001 -> "core-a";
            case 29002 -> "core-b";
            default -> "core-c";
        };
    }

    private static NodeInfo selfInfo(String id, int port) {
        return NodeInfo.nodeInfo(new NodeId(id),
                                 NodeAddress.nodeAddress("127.0.0.1", port).unwrap(),
                                 Map.of(NodeInfo.LABEL_ROLE, "core", NodeInfo.LABEL_SOURCE, "src-" + id));
    }

    /// Delivers on one wire thread, so protocols never re-enter each other's handlers.
    private final class MemoryTransport implements SwimTransport {
        private final InetSocketAddress self;

        MemoryTransport(InetSocketAddress self) {
            this.self = self;
        }

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            wire.submit(() -> deliver(target, message));

            return Promise.success(Unit.unit());
        }

        private void deliver(InetSocketAddress target, SwimMessage message) {
            var handler = net.get(new InetSocketAddress("127.0.0.1", target.getPort()));

            if (handler != null) {
                handler.onMessage(self, message);
            }
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            net.put(self, handler);

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            net.remove(self);

            return Promise.success(Unit.unit());
        }
    }
}
