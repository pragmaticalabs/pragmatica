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

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgColumn;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.SqlError;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.Connectible;

import io.netty.channel.EventLoopGroup;


/**
 * A {@link Connectible} holding one lease on its builder's {@link OwnedEventLoopGroup}.
 * <p>
 * The first {@link #close()} closes the delegate and, once the delegate's own close has settled (success or failure),
 * releases the lease; it resolves with the delegate's outcome after the release completed. A repeated close is passed
 * to the delegate untouched and releases nothing. After the release the delegate cannot start a stream, so it can
 * never revive the group.
 */
final class OwnedConnectible implements Connectible {
    private final OwnedEventLoopGroup owned;
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private boolean released;  // guarded by owned, so a stream cannot start a group after the last release
    private Connectible delegate;

    OwnedConnectible(OwnedEventLoopGroup owned) {
        this.owned = owned;
        owned.lease();
    }

    void delegate(Connectible delegate) {
        this.delegate = delegate;
    }

    /// The group for a new stream; fails once the lease was released.
    Promise<EventLoopGroup> group() {
        synchronized (owned) {
            return released
                   ? Promise.failure(new SqlError.ConnectionPoolClosed("Connectible is closed"))
                   : Promise.success(owned.group());
        }
    }

    @Override
    public Promise<Connection> getConnection() {
        return delegate.getConnection();
    }

    @Override
    public Promise<Unit> script(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                Consumer<PgRow> onRow,
                                Consumer<Integer> onAffected,
                                String sql) {
        return delegate.script(onColumns, onRow, onAffected, sql);
    }

    @Override
    public Promise<Integer> query(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                  Consumer<PgRow> onRow,
                                  String sql,
                                  Object... params) {
        return delegate.query(onColumns, onRow, sql, params);
    }

    @Override
    public Promise<Unit> close() {
        if (!closeStarted.compareAndSet(false, true)) {
            return delegate.close();
        }

        var result = Promise.<Unit> promise();

        delegate.close().onResult(outcome -> {
            markReleased();
            owned.release()
                 .onResult(_ -> result.resolve(outcome));
        });

        return result;
    }

    private void markReleased() {
        synchronized (owned) {
            released = true;
        }
    }
}
