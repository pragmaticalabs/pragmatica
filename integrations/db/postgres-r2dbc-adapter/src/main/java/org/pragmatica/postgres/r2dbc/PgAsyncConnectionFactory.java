/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
 *
 */

package org.pragmatica.postgres.r2dbc;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;

import org.pragmatica.postgres.net.Connectible;
import org.reactivestreams.Publisher;

import reactor.core.publisher.Mono;

/// R2DBC [ConnectionFactory] backed by a postgres-async [Connectible].
///
/// Bridges the Pragmatica promise-based connection model to the R2DBC
/// reactive streams API using Reactor's [Mono].
public final class PgAsyncConnectionFactory implements ConnectionFactory {
    private final Connectible connectible;

    private PgAsyncConnectionFactory(Connectible connectible) {
        this.connectible = connectible;
    }

    /// Creates a new connection factory wrapping the given [Connectible].
    ///
    /// @param connectible the postgres-async connection source
    ///
    /// @return a new [PgAsyncConnectionFactory]
    public static PgAsyncConnectionFactory pgAsyncConnectionFactory(Connectible connectible) {
        return new PgAsyncConnectionFactory(connectible);
    }

    @Override
    public Publisher<? extends io.r2dbc.spi.Connection> create() {
        return Mono.create(sink ->
            connectible.getConnection()
                       .onSuccess(conn -> sink.success(new PgAsyncConnection(conn)))
                       .onFailure(cause -> sink.error(new R2dbcAdapterException(cause.message()))));
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
        return PgAsyncConnectionFactoryMetadata.INSTANCE;
    }

    /// Returns the underlying postgres-async [Connectible].
    public Connectible connectible() {
        return connectible;
    }
}
