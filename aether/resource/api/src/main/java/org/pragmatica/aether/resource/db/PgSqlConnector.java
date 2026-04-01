package org.pragmatica.aether.resource.db;

/// PostgreSQL-specific SQL connector.
///
/// Marker interface that extends SqlConnector to enable:
/// - Annotation processor detection of PostgreSQL persistence interfaces
/// - Factory routing to async-only PostgreSQL driver (no JDBC/R2DBC fallback)
///
/// Use with @PgSql qualifier for type-safe persistence adapters.
public interface PgSqlConnector extends SqlConnector {}
