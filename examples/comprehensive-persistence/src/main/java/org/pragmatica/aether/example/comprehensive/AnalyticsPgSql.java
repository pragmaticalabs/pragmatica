package org.pragmatica.aether.example.comprehensive;

import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@ResourceQualifier(type = PgSqlConnector.class, config = "database.analytics") @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.PARAMETER, ElementType.TYPE}) public@interface AnalyticsPgSql {}
