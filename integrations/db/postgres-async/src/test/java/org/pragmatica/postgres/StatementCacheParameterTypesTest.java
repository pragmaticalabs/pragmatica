package org.pragmatica.postgres;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.conversion.DataConverter;
import org.pragmatica.postgres.message.Message;
import org.pragmatica.postgres.message.backend.Authentication;
import org.pragmatica.postgres.message.backend.CommandComplete;
import org.pragmatica.postgres.message.backend.DataRow;
import org.pragmatica.postgres.message.backend.RowDescription;
import org.pragmatica.postgres.message.frontend.Bind;
import org.pragmatica.postgres.message.frontend.Close;
import org.pragmatica.postgres.message.frontend.Describe;
import org.pragmatica.postgres.message.frontend.Parse;
import org.pragmatica.postgres.message.frontend.Query;
import org.pragmatica.postgres.message.frontend.StartupMessage;
import org.pragmatica.postgres.net.NotificationHandler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The statement cache is keyed by SQL text, but a server-side statement is bound to the parameter types it was
/// parsed with. A cache hit with different types must not return the statement parsed for other types, otherwise
/// whether a bind is accepted depends on which call prepared the SQL first. Runs against a recording fake stream.
class StatementCacheParameterTypesTest {
    private static final String SQL = "select $1";

    private final RecordingStream stream = new RecordingStream();

    private final PgConnection connection = new PgConnection(stream,
                                                             new DataConverter(List.of(), StandardCharsets.UTF_8),
                                                             4);

    @Test
    void prepareStatement_reparses_whenCachedStatementHasOtherParameterTypes() {
        cache(Oid.INT4);
        connection.prepareStatement(SQL, Oid.TEXT).await();
        assertThat(stream.parsedTypes()).containsExactly(List.of(Oid.INT4), List.of(Oid.TEXT));
    }

    @Test
    void prepareStatement_closesStaleStatementOnServer_whenParameterTypesChange() {
        cache(Oid.INT4);
        connection.prepareStatement(SQL, Oid.TEXT).await();
        assertThat(stream.closedStatements()).hasSize(1);
    }

    @Test
    void prepareStatement_reusesCachedStatement_whenParameterTypesMatch() {
        cache(Oid.INT4);
        connection.prepareStatement(SQL, Oid.INT4).await();
        assertThat(stream.parsedTypes()).containsExactly(List.of(Oid.INT4));
    }

    @Test
    void prepareStatement_closesEvictedStatementOnce_whenReplacementIsClosed() {
        cache(Oid.INT4);
        connection.prepareStatement(SQL, Oid.TEXT).flatMap(statement -> statement.close()).await();
        assertThat(stream.closedStatements()).hasSize(1);
    }

    @Test
    void prepareStatement_doesNotReturnEvictedStatement_beforeReplacementIsClosed() {
        cache(Oid.INT4);
        connection.prepareStatement(SQL, Oid.TEXT).await();
        connection.prepareStatement(SQL, Oid.INT4).await();
        assertThat(stream.parsedTypes()).containsExactly(List.of(Oid.INT4), List.of(Oid.TEXT), List.of(Oid.INT4));
    }

    @Test
    void prepareStatement_keepsCachedTypes_whenCallerMutatesItsTypesArray() {
        var types = new Oid[] {Oid.INT4};

        connection.prepareStatement(SQL, types).flatMap(statement -> statement.close()).await();
        types[0] = Oid.TEXT;
        connection.prepareStatement(SQL, Oid.INT4).await();
        // size, not content: the recorded Parse shares the caller's array, which the test has just overwritten
        assertThat(stream.parsedTypes()).hasSize(1);
    }

    private void cache(Oid type) {
        connection.prepareStatement(SQL, type).flatMap(statement -> statement.close()).await();
    }

    private static final class RecordingStream implements ProtocolStream {
        private final List<Message> sent = new ArrayList<>();

        List<List<Oid>> parsedTypes() {
            return sent.stream()
                       .filter(Parse.class::isInstance)
                       .map(message -> List.of(((Parse) message).types()))
                       .toList();
        }

        List<Message> closedStatements() {
            return sent.stream()
                       .filter(Close.class::isInstance)
                       .toList();
        }

        @Override
        public Promise<Message> send(Message message) {
            sent.add(message);

            return Promise.success(message);
        }

        @Override
        public Promise<Message> connect(StartupMessage startup) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Message> authenticate(String userName, String password, Authentication authRequired) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> send(Query query,
                                  Consumer<RowDescription.ColumnDescription[]> onColumns,
                                  Consumer<DataRow> onRow,
                                  Consumer<CommandComplete> onAffected) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Integer> send(Bind bind,
                                     Describe describe,
                                     Consumer<RowDescription.ColumnDescription[]> onColumns,
                                     Consumer<DataRow> onRow) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Integer> send(Bind bind, Consumer<DataRow> onRow) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Runnable subscribe(String channel, Consumer<String> onNotification) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Runnable subscribeWithDetails(String channel, NotificationHandler onNotification) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public Promise<Unit> close() {
            return Promise.success(Unit.unit());
        }
    }
}
