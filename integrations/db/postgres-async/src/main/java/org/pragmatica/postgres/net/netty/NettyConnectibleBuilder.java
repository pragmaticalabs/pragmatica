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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.postgres.PgConnectionPool;
import org.pragmatica.postgres.PgDatabase;
import org.pragmatica.postgres.PgTransactionPool;
import org.pragmatica.postgres.ProtocolStream;
import org.pragmatica.postgres.SqlError;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.ConnectibleBuilder;
import org.pragmatica.postgres.net.PoolMode;
import org.pragmatica.lang.Promise;

import io.netty.channel.EventLoopGroup;


/**
 * Builder for creating {@link Connectible} instances.
 *
 * @author Antti Laisi
 * @author Marat Gainullin
 */
public class NettyConnectibleBuilder extends ConnectibleBuilder {
    private final OwnedEventLoopGroup owned = new OwnedEventLoopGroup(() -> properties.ioThreads());
    private EventLoopGroup suppliedGroup;

    /// Use a shared group: it is never released by this builder or by anything it builds.
    public NettyConnectibleBuilder eventLoopGroup(EventLoopGroup eventLoopGroup) {
        this.suppliedGroup = eventLoopGroup;

        return this;
    }

    /// Without a supplied group, every `pool()`/`plain()` result holds a lease on one builder-owned group, created on
    /// first use; the group is shut down when the last of them is closed, and `close()` resolves on its termination.
    /// Close every Connectible built from this builder (#3635).
    public Connectible pool() {
        return build(obtainStream -> properties.poolMode() == PoolMode.TRANSACTION
                                     ? new PgTransactionPool(properties, obtainStream)
                                     : new PgConnectionPool(properties, obtainStream));
    }

    public Connectible plain() {
        return build(obtainStream -> new PgDatabase(properties, obtainStream));
    }

    /// The group currently owned by this builder, or null: nothing created yet, or the last lease was released.
    EventLoopGroup ownedGroup() {
        return owned.current();
    }

    private Connectible build(Function<Supplier<Promise<ProtocolStream>>, Connectible> factory) {
        if (suppliedGroup != null) {
            return factory.apply(() -> obtainStream(Promise.success(suppliedGroup)));
        }

        var lease = new OwnedConnectible(owned);

        lease.delegate(factory.apply(() -> obtainStream(lease.group())));

        return lease;
    }

    private Promise<ProtocolStream> obtainStream(Promise<EventLoopGroup> group) {
        return group.flatMap(this::openStream);
    }

    private Promise<ProtocolStream> openStream(EventLoopGroup group) {
        try {
            var address = InetAddress.getByName(properties.hostname());
            var sockAddr = new InetSocketAddress(address, properties.port());

            return Promise.success(new NettyPgProtocolStream(sockAddr,
                                                             properties.hostname(),
                                                             properties.sslConfig(),
                                                             Charset.forName(properties.encoding()),
                                                             group));
        } catch (Exception e) {
            return Promise.failure(SqlError.fromThrowable(e));
        }
    }
}
