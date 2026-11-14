/*
 *  Copyright (c) 2022-2025 Sergiy Yevtushenko.
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
package org.pragmatica.net.redis;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Redis/Valkey client configuration.
///
/// `poolSize` is the maximum number of connections the client opens (lazily, one more only when every
/// open connection already has a command in flight). `maxInFlight` is the pipeline depth bound of ONE
/// connection: a command beyond it fails with [RedisError.PoolSaturated] instead of queueing without
/// limit. `commandTimeout` bounds the wait for one reply and is enforced with a connection close, see
/// [RedisError.Timeout].
public record RedisConfig(String host,
                          int port,
                          Option<String> username,
                          Option<String> password,
                          int database,
                          boolean tls,
                          TimeSpan connectTimeout,
                          TimeSpan commandTimeout,
                          int poolSize,
                          int maxInFlight) {
    /// Per-field defaults, read by the config binder by name (`DEFAULT_<COMPONENT>`); `host` has none.
    public static final int DEFAULT_PORT = 6379;
    public static final int DEFAULT_DATABASE = 0;
    public static final boolean DEFAULT_TLS = false;
    public static final TimeSpan DEFAULT_CONNECT_TIMEOUT = timeSpan(5).seconds();
    public static final TimeSpan DEFAULT_COMMAND_TIMEOUT = timeSpan(10).seconds();
    public static final int DEFAULT_POOL_SIZE = 4;
    public static final int DEFAULT_MAX_IN_FLIGHT = 1024;

    private static final int MAX_PORT = 65535;

    /// Defaults for everything but the host: port 6379, no auth, database 0, no TLS.
    public static Result<RedisConfig> redisConfig(String host) {
        return redisConfig(host, DEFAULT_PORT);
    }

    public static Result<RedisConfig> redisConfig(String host, int port) {
        return redisConfig(host,
                           port,
                           none(),
                           none(),
                           DEFAULT_DATABASE,
                           DEFAULT_TLS,
                           DEFAULT_CONNECT_TIMEOUT,
                           DEFAULT_COMMAND_TIMEOUT,
                           DEFAULT_POOL_SIZE,
                           DEFAULT_MAX_IN_FLIGHT);
    }

    public static Result<RedisConfig> redisConfig(String host,
                                                  int port,
                                                  Option<String> username,
                                                  Option<String> password,
                                                  int database,
                                                  boolean tls,
                                                  TimeSpan connectTimeout,
                                                  TimeSpan commandTimeout,
                                                  int poolSize,
                                                  int maxInFlight) {
        return Result.all(Verify.ensure(host, Verify.Is::present),
                          Verify.ensure(port, Verify.Is::between, 1, MAX_PORT),
                          Verify.ensure(database, Verify.Is::nonNegative),
                          Verify.ensure(poolSize, Verify.Is::positive),
                          Verify.ensure(maxInFlight, Verify.Is::positive))
                     .map((validHost, validPort, validDatabase, validPool, validDepth) -> new RedisConfig(validHost,
                                                                                                         validPort,
                                                                                                         username,
                                                                                                         password,
                                                                                                         validDatabase,
                                                                                                         tls,
                                                                                                         connectTimeout,
                                                                                                         commandTimeout,
                                                                                                         validPool,
                                                                                                         validDepth));
    }

    /// AUTH with a password only (the `default` ACL user).
    public Result<RedisConfig> withPassword(String secret) {
        return withCredentials(none(), some(secret));
    }

    /// AUTH with an ACL user name and password.
    public Result<RedisConfig> withCredentials(Option<String> user, Option<String> secret) {
        return redisConfig(host,
                           port,
                           user,
                           secret,
                           database,
                           tls,
                           connectTimeout,
                           commandTimeout,
                           poolSize,
                           maxInFlight);
    }

    /// SELECT this logical database on every new connection.
    public Result<RedisConfig> withDatabase(int index) {
        return redisConfig(host,
                           port,
                           username,
                           password,
                           index,
                           tls,
                           connectTimeout,
                           commandTimeout,
                           poolSize,
                           maxInFlight);
    }

    /// Connect over TLS, verifying the server certificate against the JVM trust store.
    public Result<RedisConfig> withTls() {
        return redisConfig(host,
                           port,
                           username,
                           password,
                           database,
                           true,
                           connectTimeout,
                           commandTimeout,
                           poolSize,
                           maxInFlight);
    }

    public Result<RedisConfig> withPool(int size, int inFlightLimit) {
        return redisConfig(host,
                           port,
                           username,
                           password,
                           database,
                           tls,
                           connectTimeout,
                           commandTimeout,
                           size,
                           inFlightLimit);
    }

    public Result<RedisConfig> withTimeouts(TimeSpan connect, TimeSpan command) {
        return redisConfig(host, port, username, password, database, tls, connect, command, poolSize, maxInFlight);
    }
}
