// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.lang.reflect.Proxy;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.NettySwimTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/// #3021 probe 9: the production SWIM transport binds its UDP port exclusively, so a foreign socket that sets
/// `SO_REUSEADDR` cannot share it (`NettySwimTransport` sets no `SO_REUSEADDR` on its `Bootstrap`, and the JDK default is
/// off). Must be run on the CI platform: Linux and BSD UDP reuse rules differ. The control proves the instrument can go
/// red: two sockets that BOTH set `SO_REUSEADDR` share a port, so the bind succeeds.
///
/// The transport is started alone, on a claimed block's SWIM port; the "after a full cluster start" arm of the design
/// belongs with the extension (step 4).
class SwimUdpBindExclusivityProbeTest {
    @Test
    @Timeout(60)
    void swimPort_afterStart_cannotBeSharedByASocketThatSetsReuseAddress() throws Exception {
        try (var claim = EmberPortAllocator.emberPortAllocator().claim(1).unwrap()) {
            var port = claim.block().swimBase();
            var transport = NettySwimTransport.nettySwimTransport(stub(Serializer.class), stub(Deserializer.class)).unwrap();

            try {
                assertThat(transport.start(port, (_, _) -> {}).await().isSuccess()).isTrue();

                assertThatThrownBy(() -> bindWithReuse(port)).isInstanceOf(BindException.class);
            } finally {
                transport.stop().await();
            }
        }
    }

    @Test
    @Timeout(60)
    void control_twoSocketsThatBothSetReuseAddress_shareTheUdpPort() throws Exception {
        try (var claim = EmberPortAllocator.emberPortAllocator().claim(1).unwrap();
             var first = new DatagramSocket(null)) {
            var port = claim.block().swimBase();

            first.setReuseAddress(true);
            first.bind(new InetSocketAddress(port));

            try (var second = bindWithReuse(port)) {
                assertThat(second.socket().getLocalPort()).isEqualTo(port);
            }
        }
    }

    private static DatagramChannel bindWithReuse(int port) throws Exception {
        var channel = DatagramChannel.open();

        try {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            return channel.bind(new InetSocketAddress(port));
        } catch (Exception e) {
            channel.close();
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (_, _, _) -> null);
    }
}
