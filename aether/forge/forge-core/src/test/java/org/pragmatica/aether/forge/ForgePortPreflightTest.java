// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #1008 — the QUIC base port was hard-coded AND its collision was invisible. These tests pin the
/// second half: a Forge whose QUIC range is already held must say so and name the port.
///
/// The mechanism under test is subtle enough to be worth stating. `QuicClusterServer` binds with
/// `SO_REUSEADDR`, and two UDP sockets that BOTH set it share a port without error — so the second
/// Forge never sees a bind failure at all. The preflight works only because it OMITS that option.
/// [SocketMechanism] pins that omission as a property of the OS rather than of our code, so a future
/// edit that "helpfully" adds `SO_REUSEADDR` to the probe has a test standing against it.
class ForgePortPreflightTest {
    private static final int NODES = 5;
    private static final Random RANDOM = new Random();

    /// A base port whose whole `NODES`-wide range is bindable right now. Scans from a random high
    /// base so concurrent modules in a parallel reactor do not converge on one range (#939).
    private static int freeBase() {
        for (var attempt = 0; attempt < 200; attempt++) {
            var candidate = 20_000 + RANDOM.nextInt(40_000);

            if (rangeIsBindable(candidate)) {
                return candidate;
            }
        }

        return fail("no free " + NODES + "-port UDP range found in 200 attempts");
    }

    private static boolean rangeIsBindable(int base) {
        var held = new ArrayList<DatagramChannel>();

        try {
            for (var offset = 0; offset < NODES; offset++) {
                held.add(bind(base + offset, false));
            }

            return true;
        } catch (Exception e) {
            return false;
        } finally {
            closeAll(held);
        }
    }

    private static DatagramChannel bind(int port, boolean reuseAddr) throws Exception {
        var channel = DatagramChannel.open(StandardProtocolFamily.INET);

        if (reuseAddr) {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        }

        try {
            channel.bind(new InetSocketAddress(port));

            return channel;
        } catch (Exception e) {
            channel.close();

            throw e;
        }
    }

    private static void closeAll(List<DatagramChannel> channels) {
        channels.forEach(ForgePortPreflightTest::closeQuietly);
    }

    private static void closeQuietly(DatagramChannel channel) {
        try {
            channel.close();
        } catch (Exception e) {
            // Test teardown: a channel that cannot be closed cannot affect the assertion already made.
        }
    }

    @Nested
    class FreeRange {
        @Test
        void ensureQuicPortsFree_succeeds_whenWholeRangeIsFree() {
            var base = freeBase();

            ForgePortPreflight.ensureQuicPortsFree(base, NODES)
                              .onFailure(cause -> fail("free range " + base + " rejected: " + cause.message()));
        }
    }

    @Nested
    class OccupiedRange {
        @Test
        void ensureQuicPortsFree_fails_whenAPortInRangeIsHeld() throws Exception {
            var base = freeBase();
            var holder = bind(base + 2, false);

            try {
                ForgePortPreflight.ensureQuicPortsFree(base, NODES)
                                  .onSuccess(_ -> fail("held port " + (base + 2) + " was not detected"));
            } finally {
                holder.close();
            }
        }

        /// THE case that matters. A real Forge holds the port with `SO_REUSEADDR` set, which is
        /// precisely the configuration under which a second `SO_REUSEADDR` bind SUCCEEDS. A probe
        /// that copied `QuicClusterServer`'s options would report this range as free.
        @Test
        void ensureQuicPortsFree_fails_whenHolderSetsReuseAddr() throws Exception {
            var base = freeBase();
            var holder = bind(base, true);

            try {
                ForgePortPreflight.ensureQuicPortsFree(base, NODES)
                                  .onSuccess(_ -> fail("SO_REUSEADDR holder on " + base + " was not detected"));
            } finally {
                holder.close();
            }
        }

        @Test
        void ensureQuicPortsFree_namesEveryOccupiedPort() throws Exception {
            var base = freeBase();
            var first = bind(base + 1, true);
            var second = bind(base + 3, false);

            try {
                var message = messageOf(ForgePortPreflight.ensureQuicPortsFree(base, NODES));

                assertThat(message).contains(String.valueOf(base + 1))
                                   .contains(String.valueOf(base + 3))
                                   .contains(base + "-" + (base + NODES - 1));
            } finally {
                first.close();
                second.close();
            }
        }

        /// `activePeerCount=1` is produced by BOTH a QUIC collision and stale on-disk `forge-data`.
        /// Once the cluster is up the two are indistinguishable from the output, which is what cost
        /// the debugging time in #1008, so the message must rule the other one out explicitly.
        @Test
        void ensureQuicPortsFree_messageDiscriminatesFromStaleForgeData() throws Exception {
            var base = freeBase();
            var holder = bind(base, true);

            try {
                var message = messageOf(ForgePortPreflight.ensureQuicPortsFree(base, NODES));

                assertThat(message).contains("PORT COLLISION")
                                   .contains("forge-data")
                                   .contains("no node was started")
                                   .contains("base_port");
            } finally {
                holder.close();
            }
        }

        private String messageOf(Result<Unit> result) {
            return result.fold(cause -> cause.message(),
                               _ -> fail("expected a failure, got success"));
        }
    }

    /// The OS behaviour the preflight is built on, pinned here so a platform or JDK change that
    /// invalidates it fails loudly instead of silently disarming the guard.
    @Nested
    class SocketMechanism {
        /// The mechanism the probe relies on, and it holds on every platform: a plain bind is
        /// refused while ANY holder exists, including one that set `SO_REUSEADDR`.
        @Test
        void plainBind_isRefused_whileReuseAddrHolderExists() throws Exception {
            var port = freeBase();
            var holder = bind(port, true);

            try {
                var duplicate = bind(port, false);

                duplicate.close();
                fail("plain bind on " + port + " succeeded despite a SO_REUSEADDR holder - "
                     + "the preflight's detection mechanism no longer works on this platform");
            } catch (Exception e) {
                assertThat(e).hasMessageContaining("Address already in use");
            } finally {
                holder.close();
            }
        }

        /// POSITIVE CONTROL for the test above: the same plain bind on a free port must succeed, so
        /// a refusal there is evidence about the holder rather than about a broken instrument.
        @Test
        void plainBind_succeeds_whenNoHolderExists() throws Exception {
            var port = freeBase();
            var channel = bind(port, false);

            channel.close();
        }
    }
}
