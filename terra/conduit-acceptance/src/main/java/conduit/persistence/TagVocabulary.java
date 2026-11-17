package conduit.persistence;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Creates missing tag names, inserting them in sorted order so that two writers always take the unique-index locks
/// in the same sequence: without it, concurrent article writes can deadlock on the unique index. Any one total order
/// prevents the deadlock; this is the database collation's order, which every writer of this service shares.
@PgSql
public interface TagVocabulary {
    @Query("INSERT INTO tags (name) SELECT unnest(:names::text[]) AS name ORDER BY name ON CONFLICT (name) DO NOTHING")
    Promise<Unit> insertMissing(String[] names);
}
