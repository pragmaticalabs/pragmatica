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

import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

import static org.pragmatica.lang.Unit.unit;


/**
 * Event-loop group a builder creates for itself, shared by every {@link org.pragmatica.postgres.net.Connectible}
 * the builder hands out and released with the last of them (#3635).
 * <p>
 * Guarantee: the group exists only between the first {@link #group()} call after a lease was taken and the release
 * of the last lease (every Connectible holds one, and so does every open stream until its channel closes); that release shuts it down (quiet period 0, bounded by {@link #SHUTDOWN_TIMEOUT_SECONDS}) and
 * resolves once the group has terminated. A later lease starts a fresh group, a terminated one is never handed out.
 */
final class OwnedEventLoopGroup {
    static final long SHUTDOWN_TIMEOUT_SECONDS = 2;

    private final IntSupplier threads;
    private EventLoopGroup group;
    private int leases;

    OwnedEventLoopGroup(IntSupplier threads) {
        this.threads = threads;
    }

    synchronized void lease() {
        leases++;
    }

    synchronized int leases() {
        return leases;
    }

    synchronized EventLoopGroup group() {
        if (group == null) {
            group = new MultiThreadIoEventLoopGroup(threads.getAsInt(), NioIoHandler.newFactory());
        }

        return group;
    }

    /// The live group, or null when none was started (or the last lease released it).
    synchronized EventLoopGroup current() {
        return group;
    }

    Promise<Unit> release() {
        var toStop = releaseLease();

        if (toStop == null) {
            return Promise.success(unit());
        }

        var terminated = Promise.<Unit> promise();

        toStop.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .addListener(_ -> terminated.succeed(unit()));

        return terminated;
    }

    private synchronized EventLoopGroup releaseLease() {
        if (--leases > 0) {
            return null;
        }

        leases = 0;
        var stopping = group;

        group = null;

        return stopping;
    }
}
