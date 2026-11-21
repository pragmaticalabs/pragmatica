package org.pragmatica.net.redis;

import java.net.ConnectException;
import java.net.SocketAddress;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.socket.nio.NioSocketChannel;

/// Client channel factories whose connect outcome is fixed by construction (#3558), so a test of how a
/// connect outcome is TYPED does not depend on what the kernel does with a loopback port under load. Both go
/// through the real Bootstrap, listener and promise path; only the socket connect call is replaced.
final class ScriptedConnect {
    private ScriptedConnect() {}

    /// A connect that is refused at once, as the kernel reports an RST.
    static ChannelFactory<Channel> refused() {
        return RefusedChannel::new;
    }

    /// A connect that never completes: only the configured connect timeout can end it.
    static ChannelFactory<Channel> neverCompletes() {
        return PendingChannel::new;
    }

    private static final class RefusedChannel extends NioSocketChannel {
        @Override
        protected boolean doConnect(SocketAddress remote, SocketAddress local) throws Exception {
            throw new ConnectException("Connection refused");
        }
    }

    private static final class PendingChannel extends NioSocketChannel {
        @Override
        protected boolean doConnect(SocketAddress remote, SocketAddress local) {
            return false;
        }
    }
}
