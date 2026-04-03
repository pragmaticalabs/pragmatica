package org.pragmatica.aether.resource.db;

import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Qualifier for PostgreSQL persistence adapters.
///
/// When used on a persistence interface, marks it for compile-time SQL validation
/// and code generation by the QueryAnnotationProcessor.
///
/// When used on a slice factory parameter, triggers PgSqlConnector provisioning
/// from the "database" config section.
///
/// For multiple datasources, create custom annotations:
/// {@code @ResourceQualifier(type = PgSqlConnector.class, config = "database.analytics")}
@ResourceQualifier(type = PgSqlConnector.class, config = "database") @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.PARAMETER, ElementType.TYPE}) public@interface PgSql {}
