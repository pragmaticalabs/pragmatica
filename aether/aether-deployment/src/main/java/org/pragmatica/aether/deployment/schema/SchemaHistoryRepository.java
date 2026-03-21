package org.pragmatica.aether.deployment.schema;

import org.pragmatica.aether.deployment.schema.ParsedMigration.MigrationType;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

import static org.pragmatica.lang.Result.all;

/// Repository for the `aether_schema_history` table tracking applied migrations.
public interface SchemaHistoryRepository {
    /// Creates the schema history table if it does not already exist.
    Promise<Unit> bootstrap(SqlConnector connector);

    /// Returns all previously applied migrations ordered by version.
    Promise<List<AppliedMigration>> queryApplied(SqlConnector connector);

    /// Records a successfully applied migration in the history table.
    Promise<Unit> recordMigration(SqlConnector connector, AppliedMigration migration);

    /// Removes a migration record by version and type (used during undo).
    Promise<Unit> removeMigration(SqlConnector connector, int version, MigrationType type);

    /// Returns the checksum of the last applied repeatable migration with the given description.
    Promise<Option<Long>> queryRepeatableChecksum(SqlConnector connector, String description);

    /// Creates a new SchemaHistoryRepository instance.
    static SchemaHistoryRepository schemaHistoryRepository() {
        return new DefaultSchemaHistoryRepository();
    }

    /// A record of a migration that has been applied to the database.
    ///
    /// @param version     migration version number
    /// @param type        migration type
    /// @param description human-readable description
    /// @param script      filename of the migration script
    /// @param checksum    CRC32 checksum of the SQL content
    /// @param appliedBy   node identifier that applied the migration
    /// @param appliedAt   epoch millis when the migration was applied
    /// @param executionMs time in milliseconds the migration took to execute
    record AppliedMigration(int version,
                            MigrationType type,
                            String description,
                            String script,
                            long checksum,
                            String appliedBy,
                            long appliedAt,
                            int executionMs) {
        public static AppliedMigration appliedMigration(int version,
                                                        MigrationType type,
                                                        String description,
                                                        String script,
                                                        long checksum,
                                                        String appliedBy,
                                                        long appliedAt,
                                                        int executionMs) {
            return new AppliedMigration(version, type, description, script, checksum, appliedBy, appliedAt, executionMs);
        }
    }
}

/// Default implementation of SchemaHistoryRepository using SqlConnector.
final class DefaultSchemaHistoryRepository implements SchemaHistoryRepository {
    private static final String BOOTSTRAP_SQL = """
        CREATE TABLE IF NOT EXISTS aether_schema_history (
            version        INTEGER      NOT NULL,
            type           VARCHAR(16)  NOT NULL DEFAULT 'VERSIONED',
            description    VARCHAR(256) NOT NULL,
            script         VARCHAR(512) NOT NULL,
            checksum       BIGINT       NOT NULL,
            applied_by     VARCHAR(128) NOT NULL,
            applied_at     BIGINT       NOT NULL,
            execution_ms   INTEGER      NOT NULL,
            PRIMARY KEY (version, type)
        )""";

    private static final String QUERY_APPLIED_SQL = "SELECT version, type, description, script, checksum, applied_by, applied_at, execution_ms "
                                                   + "FROM aether_schema_history ORDER BY version";

    private static final String INSERT_SQL = "INSERT INTO aether_schema_history (version, type, description, script, checksum, applied_by, applied_at, execution_ms) "
                                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String DELETE_SQL = "DELETE FROM aether_schema_history WHERE version = ? AND type = ?";

    private static final String QUERY_REPEATABLE_SQL = "SELECT checksum FROM aether_schema_history WHERE type = 'REPEATABLE' AND description = ?";

    private static final RowMapper<SchemaHistoryRepository.AppliedMigration> APPLIED_MIGRATION_MAPPER = row -> all(row.getInt("version"),
                                                                                                                   row.getString("type")
                                                                                                                      .map(MigrationType::valueOf),
                                                                                                                   row.getString("description"),
                                                                                                                   row.getString("script"),
                                                                                                                   row.getLong("checksum"),
                                                                                                                   row.getString("applied_by"),
                                                                                                                   row.getLong("applied_at"),
                                                                                                                   row.getInt("execution_ms")).map(SchemaHistoryRepository.AppliedMigration::new);

    private static final RowMapper<Long> CHECKSUM_MAPPER = row -> row.getLong("checksum");

    @Override
    public Promise<Unit> bootstrap(SqlConnector connector) {
        return connector.update(BOOTSTRAP_SQL)
                        .mapToUnit();
    }

    @Override
    public Promise<List<SchemaHistoryRepository.AppliedMigration>> queryApplied(SqlConnector connector) {
        return connector.queryList(QUERY_APPLIED_SQL, APPLIED_MIGRATION_MAPPER);
    }

    @Override
    public Promise<Unit> recordMigration(SqlConnector connector, SchemaHistoryRepository.AppliedMigration migration) {
        return connector.update(INSERT_SQL,
                                migration.version(),
                                migration.type()
                                         .name(),
                                migration.description(),
                                migration.script(),
                                migration.checksum(),
                                migration.appliedBy(),
                                migration.appliedAt(),
                                migration.executionMs())
                        .mapToUnit();
    }

    @Override
    public Promise<Unit> removeMigration(SqlConnector connector, int version, MigrationType type) {
        return connector.update(DELETE_SQL,
                                version,
                                type.name())
                        .mapToUnit();
    }

    @Override
    public Promise<Option<Long>> queryRepeatableChecksum(SqlConnector connector, String description) {
        return connector.queryOptional(QUERY_REPEATABLE_SQL, CHECKSUM_MAPPER, description);
    }
}
