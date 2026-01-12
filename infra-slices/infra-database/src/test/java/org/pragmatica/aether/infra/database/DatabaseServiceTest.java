package org.pragmatica.aether.infra.database;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseServiceTest {
    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = DatabaseService.databaseService();
    }

    // ========== Table Operations ==========

    @Test
    void createTable_succeeds_withValidColumns() {
        db.createTable("users", List.of("name", "email"))
          .await()
          .onFailureRun(Assertions::fail);
    }

    @Test
    void createTable_fails_whenTableExists() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.createTable("users", List.of("name")))
          .await()
          .onSuccessRun(Assertions::fail)
          .onFailure(cause -> assertThat(cause).isInstanceOf(DatabaseError.DuplicateKey.class));
    }

    @Test
    void dropTable_returnsTrue_whenTableExists() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.dropTable("users"))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(dropped -> assertThat(dropped).isTrue());
    }

    @Test
    void dropTable_returnsFalse_whenTableNotExists() {
        db.dropTable("nonexistent")
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(dropped -> assertThat(dropped).isFalse());
    }

    @Test
    void tableExists_returnsTrue_afterCreate() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.tableExists("users"))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(exists -> assertThat(exists).isTrue());
    }

    @Test
    void tableExists_returnsFalse_whenNotExists() {
        db.tableExists("nonexistent")
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(exists -> assertThat(exists).isFalse());
    }

    @Test
    void listTables_returnsAllTables() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.createTable("orders", List.of("item")))
          .flatMap(unit -> db.listTables())
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(tables -> assertThat(tables).containsExactlyInAnyOrder("users", "orders"));
    }

    // ========== Insert Operations ==========

    @Test
    void insert_returnsGeneratedId() {
        db.createTable("users", List.of("name", "email"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice", "email", "alice@example.com")))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(id -> assertThat(id).isEqualTo(1L));
    }

    @Test
    void insert_fails_whenTableNotExists() {
        db.insert("nonexistent", Map.of("name", "Alice"))
          .await()
          .onSuccessRun(Assertions::fail)
          .onFailure(cause -> assertThat(cause).isInstanceOf(DatabaseError.TableNotFound.class));
    }

    @Test
    void insertBatch_returnsInsertedCount() {
        var rows = List.of(
            Map.<String, Object>of("name", "Alice"),
            Map.<String, Object>of("name", "Bob"),
            Map.<String, Object>of("name", "Charlie")
        );

        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.insertBatch("users", rows))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(count -> assertThat(count).isEqualTo(3));
    }

    // ========== Query Operations ==========

    @Test
    void query_returnsAllRows() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice")))
          .flatMap(id -> db.insert("users", Map.of("name", "Bob")))
          .flatMap(id -> db.query("users", RowMapper.stringColumn("name")))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(names -> assertThat(names).containsExactlyInAnyOrder("Alice", "Bob"));
    }

    @Test
    void queryWhere_returnsMatchingRows() {
        db.createTable("users", List.of("name", "role"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice", "role", "admin")))
          .flatMap(id -> db.insert("users", Map.of("name", "Bob", "role", "user")))
          .flatMap(id -> db.insert("users", Map.of("name", "Charlie", "role", "admin")))
          .flatMap(id -> db.queryWhere("users", "role", "admin", RowMapper.stringColumn("name")))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(names -> assertThat(names).containsExactlyInAnyOrder("Alice", "Charlie"));
    }

    @Test
    void queryById_returnsRow_whenExists() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice")))
          .flatMap(id -> db.queryById("users", id, RowMapper.stringColumn("name")))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(opt -> {
              assertThat(opt.isPresent()).isTrue();
              opt.onPresent(name -> assertThat(name).isEqualTo("Alice"));
          });
    }

    @Test
    void queryById_returnsEmpty_whenNotExists() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.queryById("users", 999L, RowMapper.stringColumn("name")))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(opt -> assertThat(opt.isPresent()).isFalse());
    }

    @Test
    void count_returnsRowCount() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice")))
          .flatMap(id -> db.insert("users", Map.of("name", "Bob")))
          .flatMap(id -> db.count("users"))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(count -> assertThat(count).isEqualTo(2L));
    }

    @Test
    void countWhere_returnsMatchingCount() {
        db.createTable("users", List.of("name", "active"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice", "active", true)))
          .flatMap(id -> db.insert("users", Map.of("name", "Bob", "active", false)))
          .flatMap(id -> db.insert("users", Map.of("name", "Charlie", "active", true)))
          .flatMap(id -> db.countWhere("users", "active", true))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(count -> assertThat(count).isEqualTo(2L));
    }

    // ========== Update Operations ==========

    @Test
    void updateById_updatesRow() {
        db.createTable("users", List.of("name", "email"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice", "email", "old@example.com")))
          .flatMap(id -> db.updateById("users", id, Map.of("email", "new@example.com")).map(count -> id))
          .flatMap(id -> db.queryById("users", id, RowMapper.stringColumn("email")))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(opt -> opt.onPresent(email -> assertThat(email).isEqualTo("new@example.com")));
    }

    @Test
    void updateById_returnsZero_whenNotExists() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.updateById("users", 999L, Map.of("name", "Updated")))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(count -> assertThat(count).isEqualTo(0));
    }

    @Test
    void updateWhere_updatesMatchingRows() {
        db.createTable("users", List.of("name", "status"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice", "status", "pending")))
          .flatMap(id -> db.insert("users", Map.of("name", "Bob", "status", "pending")))
          .flatMap(id -> db.insert("users", Map.of("name", "Charlie", "status", "active")))
          .flatMap(id -> db.updateWhere("users", "status", "pending", Map.of("status", "active")))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(count -> assertThat(count).isEqualTo(2));
    }

    // ========== Delete Operations ==========

    @Test
    void deleteById_deletesRow() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice")))
          .flatMap(id -> db.deleteById("users", id))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(deleted -> assertThat(deleted).isTrue());
    }

    @Test
    void deleteById_returnsFalse_whenNotExists() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.deleteById("users", 999L))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(deleted -> assertThat(deleted).isFalse());
    }

    @Test
    void deleteWhere_deletesMatchingRows() {
        db.createTable("users", List.of("name", "status"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice", "status", "inactive")))
          .flatMap(id -> db.insert("users", Map.of("name", "Bob", "status", "inactive")))
          .flatMap(id -> db.insert("users", Map.of("name", "Charlie", "status", "active")))
          .flatMap(id -> db.deleteWhere("users", "status", "inactive"))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(count -> assertThat(count).isEqualTo(2));
    }

    @Test
    void deleteAll_deletesAllRows() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice")))
          .flatMap(id -> db.insert("users", Map.of("name", "Bob")))
          .flatMap(id -> db.deleteAll("users"))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(count -> assertThat(count).isEqualTo(2));
    }

    // ========== RowMapper Operations ==========

    @Test
    void rowMapper_asMap_returnsEntireRow() {
        db.createTable("users", List.of("name", "email"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice", "email", "alice@example.com")))
          .flatMap(id -> db.queryById("users", id, RowMapper.asMap()))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(opt -> opt.onPresent(row -> {
              assertThat(row.get("name")).isEqualTo("Alice");
              assertThat(row.get("email")).isEqualTo("alice@example.com");
              assertThat(row.get("id")).isEqualTo(1L);
          }));
    }

    @Test
    void rowMapper_longColumn_extractsLong() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice")))
          .flatMap(id -> db.query("users", RowMapper.longColumn("id")))
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(ids -> assertThat(ids).containsExactly(1L));
    }

    @Test
    void rowMapper_fails_whenColumnNotFound() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice")))
          .flatMap(id -> db.query("users", RowMapper.stringColumn("nonexistent")))
          .await()
          .onSuccessRun(Assertions::fail)
          .onFailure(cause -> assertThat(cause.message()).contains("Column not found"));
    }

    @Test
    void rowMapper_fails_whenTypeMismatch() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice")))
          .flatMap(id -> db.query("users", RowMapper.longColumn("name")))
          .await()
          .onSuccessRun(Assertions::fail)
          .onFailure(cause -> assertThat(cause.message()).contains("is not of type"));
    }

    // ========== Config Operations ==========

    @Test
    void databaseConfig_createsDefaults() {
        DatabaseConfig.databaseConfig()
                      .onFailureRun(Assertions::fail)
                      .onSuccess(config -> {
                          assertThat(config.name()).isEqualTo("default");
                          assertThat(config.maxConnections()).isEqualTo(10);
                      });
    }

    @Test
    void databaseConfig_validatesName() {
        DatabaseConfig.databaseConfig("")
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("name cannot be null or empty"));
    }

    @Test
    void databaseConfig_withMethods_createNewConfig() {
        DatabaseConfig.databaseConfig()
                      .map(c -> c.withName("custom").withMaxConnections(20))
                      .onFailureRun(Assertions::fail)
                      .onSuccess(config -> {
                          assertThat(config.name()).isEqualTo("custom");
                          assertThat(config.maxConnections()).isEqualTo(20);
                      });
    }

    // ========== Lifecycle Operations ==========

    @Test
    void stop_clearsAllData() {
        db.createTable("users", List.of("name"))
          .flatMap(unit -> db.insert("users", Map.of("name", "Alice")))
          .flatMap(id -> db.stop())
          .flatMap(unit -> db.listTables())
          .await()
          .onFailureRun(Assertions::fail)
          .onSuccess(tables -> assertThat(tables).isEmpty());
    }
}
