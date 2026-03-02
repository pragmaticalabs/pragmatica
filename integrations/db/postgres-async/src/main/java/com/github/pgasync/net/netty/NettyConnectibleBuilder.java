/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.pgasync.net.netty;

import com.github.pgasync.PgConnectionPool;
import com.github.pgasync.PgDatabase;
import com.github.pgasync.ProtocolStream;
import com.github.pgasync.async.ThrowableCause;
import com.github.pgasync.async.ThrowingPromise;
import com.github.pgasync.net.Connectible;
import com.github.pgasync.net.ConnectibleBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;

/**
 * Builder for creating {@link Connectible} instances.
 *
 * @author Antti Laisi
 * @author Marat Gainullin
 */
public class NettyConnectibleBuilder extends ConnectibleBuilder {
    private final EventLoopGroup eventLoopGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

    private ThrowingPromise<ProtocolStream> obtainStream() {
        try {
            var address = InetAddress.getByName(properties.hostname());
            var sockAddr = new InetSocketAddress(address, properties.port());
            return ThrowingPromise.successful(
                new NettyPgProtocolStream(sockAddr, properties.useSsl(),
                    Charset.forName(properties.encoding()), eventLoopGroup));
        } catch (Exception e) {
            return ThrowingPromise.failed(ThrowableCause.asCause(e));
        }
    }

    public Connectible pool() {
        return new PgConnectionPool(properties, this::obtainStream);
    }

    public Connectible plain() {
        return new PgDatabase(properties, this::obtainStream);
    }

    public EventLoopGroup eventLoopGroup() {
        return eventLoopGroup;
    }
}
