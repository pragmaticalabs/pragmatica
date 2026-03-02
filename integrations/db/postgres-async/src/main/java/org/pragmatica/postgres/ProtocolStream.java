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

package org.pragmatica.postgres;

import org.pragmatica.postgres.message.Message;
import org.pragmatica.postgres.message.backend.Authentication;
import org.pragmatica.postgres.message.backend.CommandComplete;
import org.pragmatica.postgres.message.backend.DataRow;
import org.pragmatica.postgres.message.backend.RowDescription;
import org.pragmatica.postgres.message.frontend.Bind;
import org.pragmatica.postgres.message.frontend.Describe;
import org.pragmatica.postgres.message.frontend.Query;
import org.pragmatica.postgres.message.frontend.StartupMessage;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.function.Consumer;

/**
 * Stream of messages from/to backend server.
 *
 * @author Antti Laisi
 */
public interface ProtocolStream {

    Promise<Message> connect(StartupMessage startup);

    Promise<Message> authenticate(String userName, String password, Authentication authRequired);

    Promise<Message> send(Message message);

    Promise<Unit> send(Query query, Consumer<RowDescription.ColumnDescription[]> onColumns, Consumer<DataRow> onRow, Consumer<CommandComplete> onAffected);

    Promise<Integer> send(Bind bind, Describe describe, Consumer<RowDescription.ColumnDescription[]> onColumns, Consumer<DataRow> onRow);

    Promise<Integer> send(Bind bind, Consumer<DataRow> onRow);

    Runnable subscribe(String channel, Consumer<String> onNotification);

    boolean isConnected();

    Promise<Unit> close();

}
