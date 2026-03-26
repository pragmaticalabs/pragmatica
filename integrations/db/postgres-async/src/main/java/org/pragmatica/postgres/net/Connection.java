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

package org.pragmatica.postgres.net;

import org.pragmatica.postgres.Oid;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.function.Consumer;

/**
 * A single physical connection to Postgres backend.
 * Concurrent using of implementations is impossible.
 * {@link Connection} implementations are never thread-safe.
 * They are designed to be used in context of single {@link Promise} completion at a time.
 *
 * @author Antti Laisi
 * @author Marat Gainullin
 */
public interface Connection extends QueryExecutor {

    Promise<? extends PreparedStatement> prepareStatement(String sql, Oid... parametersTypes);

    /**
     * The typical scenario of using notifications is as follows:
     * - {@link #subscribe(String, Consumer)}
     * - Some calls of @onNotification callback
     * - {@link Listening#unlisten()}
     *
     * @param channel Channel name to listen to.
     * @param onNotification Callback, thar is invoked every time notification arrives.
     * @return Promise instance, completed when subscription will be registered at the backend.
     */
    @SuppressWarnings("JavadocDeclaration")
    Promise<Listening> subscribe(String channel, Consumer<String> onNotification);

    /**
     * Subscribe with full notification details including backend PID and channel.
     * Default delegates to the payload-only variant (ignoring extra fields).
     *
     * @param channel        Channel name to listen to.
     * @param onNotification Callback invoked with channel, payload, and backend PID.
     * @return Promise instance, completed when subscription will be registered at the backend.
     */
    default Promise<Listening> subscribe(String channel, NotificationHandler onNotification) {
        return subscribe(channel, payload -> onNotification.onNotification(channel, payload, 0));
    }

    Promise<Transaction> begin();

    Promise<Unit> close();

    boolean isConnected();
}
