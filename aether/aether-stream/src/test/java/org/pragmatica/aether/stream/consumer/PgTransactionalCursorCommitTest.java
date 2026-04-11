package org.pragmatica.aether.stream.consumer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.consumer.PgTransactionalCursorCommit.pgTransactionalCursorCommit;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.utils.Causes.cause;


class PgTransactionalCursorCommitTest {

    private static final String GROUP = "test-group";
    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;

    @Nested
    class CommitWithLogic {

        @Test
        void commitWithLogic_executesLogicAndCursorInSameTransaction() {
            var recorder = new RecordingSqlConnector();
            var commit = pgTransactionalCursorCommit(recorder);

            var result = commit.commitWithLogic(GROUP, STREAM, PARTITION, 42L,
                                                tx -> tx.update("INSERT INTO orders VALUES (?)", "order-1")
                                                        .map(_ -> "done"))
                               .await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(value -> assertThat(value).isEqualTo("done"));

            // Both business logic update and cursor update should have been called on the transaction connector
            assertThat(recorder.transactionalCalls()).isEqualTo(1);
            var txConnector = recorder.lastTransactionConnector();
            assertThat(txConnector).isNotNull();
            assertThat(txConnector.updateCalls()).hasSize(2);
            assertThat(txConnector.updateCalls().getFirst()).startsWith("INSERT INTO orders");
            assertThat(txConnector.updateCalls().getLast()).startsWith("INSERT INTO aether_stream_cursors");
        }

        @Test
        void commitWithLogic_rollsBackBoth_whenLogicFails() {
            var recorder = new RecordingSqlConnector();
            var commit = pgTransactionalCursorCommit(recorder);
            var expectedError = cause("Business logic failed");

            var result = commit.commitWithLogic(GROUP, STREAM, PARTITION, 10L,
                                                _ -> expectedError.promise())
                               .await();

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                  .onFailure(c -> assertThat(c.message()).isEqualTo("Business logic failed"));

            // Transaction was started but cursor commit should NOT have been called
            var txConnector = recorder.lastTransactionConnector();
            assertThat(txConnector).isNotNull();
            assertThat(txConnector.updateCalls()).isEmpty();
        }

        @Test
        void commitWithLogic_returnsLogicResult_onSuccess() {
            var recorder = new RecordingSqlConnector();
            var commit = pgTransactionalCursorCommit(recorder);

            var result = commit.commitWithLogic(GROUP, STREAM, PARTITION, 5L,
                                                _ -> Promise.success(123))
                               .await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(value -> assertThat(value).isEqualTo(123));
        }
    }

    /// Recording SqlConnector that tracks transactional calls for testing.
    static final class RecordingSqlConnector implements SqlConnector {
        private final AtomicLong transactionalCallCount = new AtomicLong(0);
        private final AtomicReference<RecordingTxConnector> lastTxRef = new AtomicReference<>();

        long transactionalCalls() {
            return transactionalCallCount.get();
        }

        RecordingTxConnector lastTransactionConnector() {
            return lastTxRef.get();
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            transactionalCallCount.incrementAndGet();
            var txConnector = new RecordingTxConnector();
            lastTxRef.set(txConnector);
            return callback.execute(txConnector);
        }

        @Override public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return cause("Not implemented").promise();
        }

        @Override public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return cause("Not implemented").promise();
        }

        @Override public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return cause("Not implemented").promise();
        }

        @Override public Promise<Integer> update(String sql, Object... params) {
            return Promise.success(1);
        }

        @Override public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return cause("Not implemented").promise();
        }

        @Override public DatabaseConnectorConfig config() {
            return null;
        }

        @Override public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }
    }

    static final class RecordingTxConnector implements SqlConnector {
        private final CopyOnWriteArrayList<String> updates = new CopyOnWriteArrayList<>();

        List<String> updateCalls() {
            return List.copyOf(updates);
        }

        @Override public Promise<Integer> update(String sql, Object... params) {
            updates.add(sql);
            return Promise.success(1);
        }

        @Override public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return cause("Not implemented").promise();
        }

        @Override public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return cause("Not implemented").promise();
        }

        @Override public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return cause("Not implemented").promise();
        }

        @Override public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return cause("Not implemented").promise();
        }

        @Override public DatabaseConnectorConfig config() {
            return null;
        }

        @Override public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }
    }
}
