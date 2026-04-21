package org.pragmatica.aether.example.comprehensive;

import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Qualifier for the analytics datasource.
///
/// Declares `config = "database.analytics"` so the processor:
/// 1. Provisions the PgSqlConnector using the `[database.analytics]` section
///    of resources.toml.
/// 2. Loads migrations from the `schema/analytics/` classpath folder for
///    compile-time validation.
///
/// Using a dedicated qualifier on a `@PgSql` interface binds ALL queries in
/// that interface to the analytics schema/datasource, so the validator
/// refuses mixing columns from primary and analytics schemas.
@ResourceQualifier(type = PgSqlConnector.class, config = "database.analytics") @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.PARAMETER, ElementType.TYPE}) public@interface AnalyticsPgSql {}
