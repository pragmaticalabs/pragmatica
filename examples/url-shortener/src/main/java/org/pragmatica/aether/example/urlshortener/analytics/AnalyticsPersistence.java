package org.pragmatica.aether.example.urlshortener.analytics;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// Persistence adapter for click analytics.
///
/// Records and counts click events per short code.
@PgSql public interface AnalyticsPersistence {
    @Query("INSERT INTO clicks (short_code) VALUES (:shortCode)") Promise<Unit> insertClick(String shortCode);

    @Query("SELECT COUNT(*) AS click_count FROM clicks WHERE short_code = :shortCode") Promise<Long> countByShortCode(String shortCode);
}
