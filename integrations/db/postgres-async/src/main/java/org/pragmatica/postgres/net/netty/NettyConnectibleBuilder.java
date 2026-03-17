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

package org.pragmatica.postgres.net.netty;

import org.pragmatica.postgres.PgConnectionPool;
import org.pragmatica.postgres.PgDatabase;
import org.pragmatica.postgres.PgTransactionPool;
import org.pragmatica.postgres.ProtocolStream;
import org.pragmatica.postgres.SqlError;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.ConnectibleBuilder;
import org.pragmatica.postgres.net.PoolMode;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.pragmatica.lang.Promise;

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
    private EventLoopGroup eventLoopGroup;
    private boolean ownsEventLoopGroup;

    public NettyConnectibleBuilder eventLoopGroup(EventLoopGroup eventLoopGroup) {
        this.eventLoopGroup = eventLoopGroup;
        this.ownsEventLoopGroup = false;
        return this;
    }

    public EventLoopGroup eventLoopGroup() {
        return resolveEventLoopGroup();
    }

    private EventLoopGroup resolveEventLoopGroup() {
        if (eventLoopGroup == null) {
            eventLoopGroup = new MultiThreadIoEventLoopGroup(properties.ioThreads(), NioIoHandler.newFactory());
            ownsEventLoopGroup = true;
        }
        return eventLoopGroup;
    }

    private Promise<ProtocolStream> obtainStream() {
        try {
            var address = InetAddress.getByName(properties.hostname());
            var sockAddr = new InetSocketAddress(address, properties.port());
            return Promise.success(
                new NettyPgProtocolStream(sockAddr, properties.useSsl(),
                    Charset.forName(properties.encoding()), resolveEventLoopGroup()));
        } catch (Exception e) {
            return Promise.failure(SqlError.fromThrowable(e));
        }
    }

    public Connectible pool() {
        return properties.poolMode() == PoolMode.TRANSACTION
            ? new PgTransactionPool(properties, this::obtainStream)
            : new PgConnectionPool(properties, this::obtainStream);
    }

    public Connectible plain() {
        return new PgDatabase(properties, this::obtainStream);
    }

    public boolean ownsEventLoopGroup() {
        return ownsEventLoopGroup;
    }
}
