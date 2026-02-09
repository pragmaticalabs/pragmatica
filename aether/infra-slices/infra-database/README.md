# Aether Infrastructure: Database

SQL-like database service for the Aether distributed runtime.

## Overview

Provides a simple SQL-like API with in-memory implementation for testing and development. Supports table management (create, drop, list), CRUD operations with row mapping, query by ID or column value, batch insert, and count operations.

## Usage

```java
var db = DatabaseService.databaseService();

// Create table
db.createTable("users", List.of("id", "name", "email")).await();

// Insert
var userId = db.insert("users", Map.of("name", "Alice", "email", "alice@example.com")).await();

// Query with mapper
RowMapper<User> mapper = row -> new User(
    (Long) row.get("id"), (String) row.get("name"), (String) row.get("email"));

var users = db.query("users", mapper).await();
var user = db.queryById("users", userId, mapper).await();

// Update and delete
db.updateById("users", userId, Map.of("name", "Alice Smith")).await();
db.deleteById("users", userId).await();
```

## Dependencies

- `pragmatica-lite-core`
