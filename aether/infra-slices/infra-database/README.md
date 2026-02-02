# Aether Infrastructure: Database

SQL-like database service for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-database</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides a simple SQL-like API with in-memory implementation for testing and development.

### Key Features

- Table management (create, drop, list)
- CRUD operations with row mapping
- Query by ID or column value
- Batch insert support
- Count operations

### Quick Start

```java
var db = DatabaseService.databaseService();

// Create table
db.createTable("users", List.of("id", "name", "email")).await();

// Insert
var userId = db.insert("users", Map.of(
    "name", "Alice",
    "email", "alice@example.com"
)).await();

// Query with mapper
RowMapper<User> mapper = row -> new User(
    (Long) row.get("id"),
    (String) row.get("name"),
    (String) row.get("email")
);

var users = db.query("users", mapper).await();
var user = db.queryById("users", userId, mapper).await();

// Update
db.updateById("users", userId, Map.of("name", "Alice Smith")).await();

// Delete
db.deleteById("users", userId).await();
```

### API Summary

| Category | Methods |
|----------|---------|
| Tables | `createTable`, `dropTable`, `tableExists`, `listTables` |
| Query | `query`, `queryWhere`, `queryById`, `count`, `countWhere` |
| Insert | `insert`, `insertBatch` |
| Update | `updateById`, `updateWhere` |
| Delete | `deleteById`, `deleteWhere`, `deleteAll` |

### RowMapper

```java
@FunctionalInterface
public interface RowMapper<T> {
    T map(Map<String, Object> row);
}
```

## License

Apache License 2.0
