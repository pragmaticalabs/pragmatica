package org.pragmatica.aether.stream.pg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.pg.PgStreamStore.pgStreamStore;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


class PgStreamStoreTest {

    private static final String STREAM = "test-stream";
    private static final String GROUP = "test-group";
    private static final int PARTITION = 0;

    private StubSqlConnector stub;
    private PgStreamStore store;

    @BeforeEach
    void setUp() {
        stub = new StubSqlConnector();
        store = pgStreamStore(stub);
    }

    @Nested
    class StoreAndReadSegment {

        @Test
        void storeSegment_callsUpdate_withCorrectParams() {
            stub.updateResult = Promise.success(1);
            var data = new byte[]{1, 2, 3, 4};

            var result = store.storeSegment(STREAM, PARTITION, 0, 9, data).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));
            assertThat(stub.lastSql.get()).contains("INSERT INTO aether_stream_segments");
            assertThat(stub.lastParams.get()).containsExactly(STREAM, PARTITION, 0L, 9L, data);
        }

        @Test
        void readSegment_returnsData_whenPresent() {
            var data = new byte[]{10, 20, 30};
            stub.queryOptionalResult = Promise.success(some(data));

            var result = store.readSegment(STREAM, PARTITION, 0).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(opt -> {
                      assertThat(opt.isPresent()).isTrue();
                      opt.onPresent(bytes -> assertThat(bytes).isEqualTo(data));
                  });
        }

        @Test
        void readSegment_returnsNone_whenAbsent() {
            stub.queryOptionalResult = Promise.success(none());

            var result = store.readSegment(STREAM, PARTITION, 999).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }
    }

    @Nested
    class DeleteExpired {

        @Test
        void deleteExpired_returnsDeletedCount() {
            stub.updateResult = Promise.success(5);

            var result = store.deleteExpired(STREAM, Instant.now()).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(count -> assertThat(count).isEqualTo(5));
            assertThat(stub.lastSql.get()).contains("DELETE FROM aether_stream_segments");
        }

        @Test
        void deleteExpired_returnsZero_whenNoneExpired() {
            stub.updateResult = Promise.success(0);

            var result = store.deleteExpired(STREAM, Instant.now()).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(count -> assertThat(count).isEqualTo(0));
        }
    }

    @Nested
    class CursorOperations {

        @Test
        void commitCursor_executesTransactionally() {
            stub.transactionalDelegate = true;
            stub.updateResult = Promise.success(1);

            var result = store.commitCursor(GROUP, STREAM, PARTITION, 42L).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));
            assertThat(stub.transactionalCalled.get()).isTrue();
        }

        @Test
        void fetchCursor_returnsOffset_whenPresent() {
            stub.queryOptionalLongResult = Promise.success(some(42L));

            var result = store.fetchCursor(GROUP, STREAM, PARTITION).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(opt -> {
                      assertThat(opt.isPresent()).isTrue();
                      opt.onPresent(offset -> assertThat(offset).isEqualTo(42L));
                  });
        }

        @Test
        void fetchCursor_returnsNone_whenNotCommitted() {
            stub.queryOptionalLongResult = Promise.success(none());

            var result = store.fetchCursor(GROUP, STREAM, PARTITION).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }

        @Test
        void commitCursor_updatesOffset_onSubsequentCommit() {
            stub.transactionalDelegate = true;
            stub.updateResult = Promise.success(1);

            store.commitCursor(GROUP, STREAM, PARTITION, 10L).await();
            store.commitCursor(GROUP, STREAM, PARTITION, 50L).await();

            assertThat(stub.lastParams.get()).containsExactly(GROUP, STREAM, PARTITION, 50L);
        }
    }

    /// Minimal stub for SqlConnector. Only the methods used by PgStreamStore are implemented.
    @SuppressWarnings("unchecked")
    static final class StubSqlConnector implements SqlConnector {
        final AtomicReference<String> lastSql = new AtomicReference<>();
        final AtomicReference<Object[]> lastParams = new AtomicReference<>();
        final AtomicReference<Boolean> transactionalCalled = new AtomicReference<>(false);

        Promise<Integer> updateResult = Promise.success(0);
        @SuppressWarnings("rawtypes") Promise queryOptionalResult = Promise.success(none());
        Promise<Option<Long>> queryOptionalLongResult = Promise.success(none());
        boolean transactionalDelegate;

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            lastSql.set(sql);
            lastParams.set(params);
            return Promise.success(null);
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            lastSql.set(sql);
            lastParams.set(params);
            if (sql.contains("committed_offset")) {
                return (Promise<Option<T>>) (Promise<?>) queryOptionalLongResult;
            }
            return (Promise<Option<T>>) queryOptionalResult;
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            lastSql.set(sql);
            lastParams.set(params);
            return Promise.success(List.of());
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            lastSql.set(sql);
            lastParams.set(params);
            return updateResult;
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            lastSql.set(sql);
            return Promise.success(new int[0]);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            transactionalCalled.set(true);
            if (transactionalDelegate) {
                return callback.execute(this);
            }
            return (Promise<T>) Promise.success(unit());
        }

        @Override
        public DatabaseConnectorConfig config() {
            return null;
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }
    }
}
