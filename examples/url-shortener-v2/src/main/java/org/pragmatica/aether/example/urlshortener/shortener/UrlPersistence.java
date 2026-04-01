package org.pragmatica.aether.example.urlshortener.shortener;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// Persistence adapter for URL shortener v2.
///
/// Manages short code to URL mappings in PostgreSQL.
@PgSql
public interface UrlPersistence {

    record UrlRow(String shortCode, String originalUrl) {}

    @Query("SELECT short_code, original_url FROM urls WHERE original_url = :originalUrl")
    Promise<Option<UrlRow>> findByOriginalUrl(String originalUrl);

    @Query("SELECT short_code, original_url FROM urls WHERE short_code = :shortCode")
    Promise<Option<UrlRow>> findByShortCode(String shortCode);

    @Query("INSERT INTO urls (short_code, original_url) VALUES (:shortCode, :originalUrl) ON CONFLICT (short_code) DO NOTHING")
    Promise<Unit> insertUrl(String shortCode, String originalUrl);
}
