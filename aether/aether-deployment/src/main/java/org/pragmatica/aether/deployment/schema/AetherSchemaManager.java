package org.pragmatica.aether.deployment.schema;

import org.pragmatica.aether.deployment.schema.ParsedMigration.MigrationType;
import org.pragmatica.aether.deployment.schema.SchemaHistoryRepository.AppliedMigration;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.blueprint.MigrationEntry;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.pragmatica.aether.deployment.schema.SchemaError.BaselineConflict.baselineConflict;
import static org.pragmatica.aether.deployment.schema.SchemaError.ChecksumMismatch.checksumMismatch;
import static org.pragmatica.aether.deployment.schema.SchemaError.UndoNotAvailable.undoNotAvailable;
import static org.pragmatica.aether.deployment.schema.SchemaHistoryRepository.AppliedMigration.appliedMigration;


/// Engine for executing schema migrations against a database.
public interface AetherSchemaManager {
    Promise<SchemaResult> migrate(String datasource,
                                  List<MigrationEntry> scripts,
                                  SqlConnector connector,
                                  String nodeId);
    Promise<SchemaResult> undo(String datasource,
                               int targetVersion,
                               List<MigrationEntry> scripts,
                               SqlConnector connector,
                               String nodeId);
    Promise<SchemaResult> baseline(String datasource,
                                   int baselineVersion,
                                   List<MigrationEntry> scripts,
                                   SqlConnector connector,
                                   String nodeId);

    static AetherSchemaManager aetherSchemaManager(SchemaPolicy policy) {
        return new DefaultAetherSchemaManager(policy, SchemaHistoryRepository.schemaHistoryRepository());
    }

    record SchemaResult(int appliedCount, int currentVersion, long totalMs) {
        public static SchemaResult schemaResult(int appliedCount, int currentVersion, long totalMs) {
            return new SchemaResult(appliedCount, currentVersion, totalMs);
        }
    }
}

/// Default implementation of AetherSchemaManager.
final class DefaultAetherSchemaManager implements AetherSchemaManager {
    private final SchemaPolicy policy;
    private final SchemaHistoryRepository historyRepo;

    DefaultAetherSchemaManager(SchemaPolicy policy, SchemaHistoryRepository historyRepo) {
        this.policy = policy;
        this.historyRepo = historyRepo;
    }

    @Override public Promise<SchemaResult> migrate(String datasource,
                                                   List<MigrationEntry> scripts,
                                                   SqlConnector connector,
                                                   String nodeId) {
        return parseAll(scripts).async()
                       .flatMap(parsed -> historyRepo.bootstrap(connector).flatMap(_ -> historyRepo.queryApplied(connector))
                                                               .flatMap(applied -> executeMigration(datasource,
                                                                                                    parsed,
                                                                                                    applied,
                                                                                                    connector,
                                                                                                    nodeId)));
    }

    @Override public Promise<SchemaResult> undo(String datasource,
                                                int targetVersion,
                                                List<MigrationEntry> scripts,
                                                SqlConnector connector,
                                                String nodeId) {
        return parseAll(scripts).async()
                       .flatMap(parsed -> historyRepo.bootstrap(connector).flatMap(_ -> historyRepo.queryApplied(connector))
                                                               .flatMap(applied -> executeUndo(datasource,
                                                                                               targetVersion,
                                                                                               parsed,
                                                                                               applied,
                                                                                               connector,
                                                                                               nodeId)));
    }

    @Override public Promise<SchemaResult> baseline(String datasource,
                                                    int baselineVersion,
                                                    List<MigrationEntry> scripts,
                                                    SqlConnector connector,
                                                    String nodeId) {
        return parseAll(scripts).async()
                       .flatMap(parsed -> historyRepo.bootstrap(connector).flatMap(_ -> historyRepo.queryApplied(connector))
                                                               .flatMap(applied -> executeBaseline(datasource,
                                                                                                   baselineVersion,
                                                                                                   parsed,
                                                                                                   applied,
                                                                                                   connector,
                                                                                                   nodeId)));
    }

    private static Result<List<ParsedMigration>> parseAll(List<MigrationEntry> scripts) {
        return Result.allOf(scripts.stream().map(ParsedMigration::parsedMigration)
                                          .toList());
    }

    private Promise<SchemaResult> executeMigration(String datasource,
                                                   List<ParsedMigration> parsed,
                                                   List<AppliedMigration> applied,
                                                   SqlConnector connector,
                                                   String nodeId) {
        var byType = groupByType(parsed);
        var baselines = byType.getOrDefault(MigrationType.BASELINE, List.of());
        var versioned = byType.getOrDefault(MigrationType.VERSIONED, List.of());
        var repeatables = byType.getOrDefault(MigrationType.REPEATABLE, List.of());
        var startNanos = System.nanoTime();
        return applyBaselines(datasource, baselines, applied, connector, nodeId).flatMap(baselineCount -> applyVersioned(datasource,
                                                                                                                         versioned,
                                                                                                                         applied,
                                                                                                                         connector,
                                                                                                                         nodeId).flatMap(versionedCount -> applyRepeatables(repeatables,
                                                                                                                                                                            connector,
                                                                                                                                                                            nodeId).map(repeatableCount -> buildResult(baselineCount + versionedCount + repeatableCount,
                                                                                                                                                                                                                       versioned,
                                                                                                                                                                                                                       applied,
                                                                                                                                                                                                                       startNanos))));
    }

    private Promise<Integer> applyBaselines(String datasource,
                                            List<ParsedMigration> baselines,
                                            List<AppliedMigration> applied,
                                            SqlConnector connector,
                                            String nodeId) {
        if (baselines.isEmpty()) {return Promise.success(0);}
        var maxBaseline = baselines.stream().mapToInt(ParsedMigration::version)
                                          .max()
                                          .orElse(0);
        var appliedVersioned = applied.stream().filter(a -> a.type() == MigrationType.VERSIONED)
                                             .toList();
        if (!appliedVersioned.isEmpty()) {
            var maxApplied = appliedVersioned.stream().mapToInt(AppliedMigration::version)
                                                    .max()
                                                    .orElse(0);
            if (maxApplied > 0) {return baselineConflict(datasource, maxApplied).promise();}
        }
        return recordSyntheticBaselines(maxBaseline, connector, nodeId);
    }

    private Promise<Integer> recordSyntheticBaselines(int maxVersion, SqlConnector connector, String nodeId) {
        var now = System.currentTimeMillis();
        return IntStream.rangeClosed(1, maxVersion).mapToObj(v -> recordSyntheticEntry(v, connector, nodeId, now))
                                    .reduce(Promise.success(0),
                                            this::chainCount,
                                            (a, _) -> a);
    }

    private Promise<Integer> recordSyntheticEntry(int version, SqlConnector connector, String nodeId, long now) {
        var migration = appliedMigration(version, MigrationType.BASELINE, "baseline", "synthetic", 0L, nodeId, now, 0);
        return historyRepo.recordMigration(connector, migration).map(_ -> 1);
    }

    private Promise<Integer> chainCount(Promise<Integer> accumulated, Promise<Integer> next) {
        return accumulated.flatMap(count -> next.map(n -> count + n));
    }

    private Promise<Integer> applyVersioned(String datasource,
                                            List<ParsedMigration> versioned,
                                            List<AppliedMigration> applied,
                                            SqlConnector connector,
                                            String nodeId) {
        var appliedVersions = applied.stream().filter(a -> a.type() == MigrationType.VERSIONED)
                                            .collect(Collectors.toMap(AppliedMigration::version,
                                                                      AppliedMigration::checksum));
        var sorted = versioned.stream().sorted(Comparator.comparingInt(ParsedMigration::version))
                                     .toList();
        return validateAndApplyVersioned(datasource, sorted, appliedVersions, connector, nodeId);
    }

    private Promise<Integer> validateAndApplyVersioned(String datasource,
                                                       List<ParsedMigration> sorted,
                                                       Map<Integer, Long> appliedVersions,
                                                       SqlConnector connector,
                                                       String nodeId) {
        var initial = Promise.success(0);
        for (var migration : sorted) {initial = initial.flatMap(count -> applyOrValidateVersioned(datasource,
                                                                                                  migration,
                                                                                                  appliedVersions,
                                                                                                  connector,
                                                                                                  nodeId).map(n -> count + n));}
        return initial;
    }

    private Promise<Integer> applyOrValidateVersioned(String datasource,
                                                      ParsedMigration migration,
                                                      Map<Integer, Long> appliedVersions,
                                                      SqlConnector connector,
                                                      String nodeId) {
        var version = migration.version();
        return Option.option(appliedVersions.get(version))
                            .fold(() -> executeSingle(migration, connector, nodeId),
                                  checksum -> checksum != migration.entry().checksum()
                                             ? checksumMismatch(datasource,
                                                                version,
                                                                checksum,
                                                                migration.entry().checksum()).promise()
                                             : Promise.success(0));
    }

    private Promise<Integer> applyRepeatables(List<ParsedMigration> repeatables,
                                              SqlConnector connector,
                                              String nodeId) {
        var initial = Promise.success(0);
        for (var migration : repeatables) {initial = initial.flatMap(count -> checkAndApplyRepeatable(migration,
                                                                                                      connector,
                                                                                                      nodeId).map(n -> count + n));}
        return initial;
    }

    private Promise<Integer> checkAndApplyRepeatable(ParsedMigration migration, SqlConnector connector, String nodeId) {
        return historyRepo.queryRepeatableChecksum(connector,
                                                   migration.description())
        .flatMap(existing -> shouldApplyRepeatable(existing, migration)
                            ? executeSingle(migration, connector, nodeId)
                            : Promise.success(0));
    }

    private static boolean shouldApplyRepeatable(Option<Long> existing, ParsedMigration migration) {
        return existing.fold(() -> true,
                             checksum -> checksum != migration.entry().checksum());
    }

    private Promise<Integer> executeSingle(ParsedMigration migration, SqlConnector connector, String nodeId) {
        var startNanos = System.nanoTime();
        return connector.transactional(tx -> executeStatements(tx,
                                                               migration.entry().sql())).flatMap(_ -> recordExecution(migration,
                                                                                                                      connector,
                                                                                                                      nodeId,
                                                                                                                      startNanos))
                                      .map(_ -> 1);
    }

    private static Promise<Unit> executeStatements(SqlConnector tx, String sql) {
        var stripped = java.util.Arrays.stream(sql.split("\n")).filter(line -> !line.strip().startsWith("--"))
                                              .collect(java.util.stream.Collectors.joining("\n"));
        var statements = java.util.Arrays.stream(stripped.split(";")).map(String::strip)
                                                .filter(s -> !s.isEmpty())
                                                .toList();
        var result = Promise.unitPromise();
        for (var stmt : statements) {result = result.flatMap(_ -> tx.update(stmt).mapToUnit());}
        return result;
    }

    private Promise<Unit> recordExecution(ParsedMigration migration,
                                          SqlConnector connector,
                                          String nodeId,
                                          long startNanos) {
        var elapsedMs = (int)((System.nanoTime() - startNanos) / 1_000_000);
        var record = appliedMigration(migration.version(),
                                      migration.type(),
                                      migration.description(),
                                      migration.entry().filename(),
                                      migration.entry().checksum(),
                                      nodeId,
                                      System.currentTimeMillis(),
                                      elapsedMs);
        return historyRepo.recordMigration(connector, record);
    }

    private Promise<SchemaResult> executeUndo(String datasource,
                                              int targetVersion,
                                              List<ParsedMigration> parsed,
                                              List<AppliedMigration> applied,
                                              SqlConnector connector,
                                              String nodeId) {
        var undoScripts = parsed.stream().filter(p -> p.type() == MigrationType.UNDO)
                                       .collect(Collectors.toMap(ParsedMigration::version, p -> p));
        var toUndo = applied.stream().filter(a -> a.type() == MigrationType.VERSIONED)
                                   .filter(a -> a.version() > targetVersion)
                                   .sorted(Comparator.comparingInt(AppliedMigration::version).reversed())
                                   .toList();
        var startNanos = System.nanoTime();
        return executeUndoSequence(datasource, toUndo, undoScripts, connector, nodeId).map(count -> buildUndoResult(count,
                                                                                                                    targetVersion,
                                                                                                                    startNanos));
    }

    private Promise<Integer> executeUndoSequence(String datasource,
                                                 List<AppliedMigration> toUndo,
                                                 Map<Integer, ParsedMigration> undoScripts,
                                                 SqlConnector connector,
                                                 String nodeId) {
        var initial = Promise.success(0);
        for (var applied : toUndo) {initial = initial.flatMap(count -> Option.option(undoScripts.get(applied.version())).async(undoNotAvailable(datasource,
                                                                                                                                                applied.version()))
                                                                                    .flatMap(script -> executeUndoStep(script,
                                                                                                                       applied.version(),
                                                                                                                       connector,
                                                                                                                       nodeId))
                                                                                    .map(n -> count + n));}
        return initial;
    }

    private Promise<Integer> executeUndoStep(ParsedMigration undoScript,
                                             int version,
                                             SqlConnector connector,
                                             String nodeId) {
        return connector.transactional(tx -> tx.update(undoScript.entry().sql()).mapToUnit()).flatMap(_ -> historyRepo.removeMigration(connector,
                                                                                                                                       version,
                                                                                                                                       MigrationType.VERSIONED))
                                      .map(_ -> 1);
    }

    private Promise<SchemaResult> executeBaseline(String datasource,
                                                  int baselineVersion,
                                                  List<ParsedMigration> parsed,
                                                  List<AppliedMigration> applied,
                                                  SqlConnector connector,
                                                  String nodeId) {
        var existingVersioned = applied.stream().filter(a -> a.type() == MigrationType.VERSIONED)
                                              .toList();
        if (!existingVersioned.isEmpty()) {
            var maxApplied = existingVersioned.stream().mapToInt(AppliedMigration::version)
                                                     .max()
                                                     .orElse(0);
            return baselineConflict(datasource, maxApplied).promise();
        }
        var startNanos = System.nanoTime();
        return recordSyntheticBaselines(baselineVersion, connector, nodeId).map(count -> buildBaselineResult(count,
                                                                                                             baselineVersion,
                                                                                                             startNanos));
    }

    private static SchemaResult buildResult(int appliedCount,
                                            List<ParsedMigration> versioned,
                                            List<AppliedMigration> applied,
                                            long startNanos) {
        var maxVersionedScript = versioned.stream().mapToInt(ParsedMigration::version)
                                                 .max()
                                                 .orElse(0);
        var maxAppliedVersion = applied.stream().filter(a -> a.type() == MigrationType.VERSIONED)
                                              .mapToInt(AppliedMigration::version)
                                              .max()
                                              .orElse(0);
        var currentVersion = Math.max(maxVersionedScript, maxAppliedVersion);
        return SchemaResult.schemaResult(appliedCount, currentVersion, elapsedMs(startNanos));
    }

    private static SchemaResult buildUndoResult(int appliedCount, int targetVersion, long startNanos) {
        return SchemaResult.schemaResult(appliedCount, targetVersion, elapsedMs(startNanos));
    }

    private static SchemaResult buildBaselineResult(int appliedCount, int baselineVersion, long startNanos) {
        return SchemaResult.schemaResult(appliedCount, baselineVersion, elapsedMs(startNanos));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static Map<MigrationType, List<ParsedMigration>> groupByType(List<ParsedMigration> parsed) {
        return parsed.stream().collect(Collectors.groupingBy(ParsedMigration::type));
    }
}
