package conduit.persistence;

import java.util.List;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// One transaction per write request.
///
/// `SqlConnector.transactional` hands its callback a plain `SqlConnector` bound to the transaction, while the
/// `@PgSql`-generated adapters take a `PgSqlConnector`. This view closes that gap and nothing else: every call goes to
/// the transaction's connector, so the generated adapters' statements run inside the transaction. (The
/// transaction connector is a plain `SqlConnector`, SqlConnector.java:20; Pragmatica offers no `PgSqlConnector` form of it,
/// pragmatica #3014.)
public sealed interface Transaction {
    static <T> Promise<T> inTransaction(PgSqlConnector db, Fn1<Promise<T>, PgSqlConnector> body) {
        return db.transactional(tx -> body.apply(Bound.bound(tx)));
    }

    record Bound(SqlConnector tx) implements PgSqlConnector, Transaction {
        public static Bound bound(SqlConnector tx) {
            return new Bound(tx);
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return tx.queryOne(sql, mapper, params);
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return tx.queryOptional(sql, mapper, params);
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return tx.queryList(sql, mapper, params);
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return tx.update(sql, params);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return tx.batch(sql, paramsList);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return tx.transactional(callback);
        }

        @Override
        public DatabaseConnectorConfig config() {
            return tx.config();
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return tx.isHealthy();
        }
    }
}
