package conduit.persistence;

import java.util.List;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;

import conduit.domain.ArticleId;
import conduit.domain.Tag;


/// The `tags` vocabulary and the `article_tags` links.
@PgSql
public interface TagPersistence {
    record ArticleTagRow(ArticleId articleId, String name) {
        /// Invariant: a non-blank name (B-26 rejects a blank tag on write).
        ///
        /// Generated code (target/) still builds this through `::new`, bypassing the factory: a known generator bug (pragmatica #3018).
        public static Result<ArticleTagRow> articleTagRow(ArticleId articleId, String name) {
            return Verify.ensure(name, Verify.Is::notBlank, Invalid.BLANK).map(_ -> new ArticleTagRow(articleId, name));
        }

        /// The invariant violations of this record, each a typed cause.
        public enum Invalid implements Cause {
            BLANK("tag name is blank");
            private final String message;
            Invalid(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }
    }

    /// B-40: tags attached to at least one article, in byte order (`name` is `COLLATE "C"`).
    @Query("""
        SELECT t.name FROM tags t
        WHERE EXISTS (SELECT 1 FROM article_tags at WHERE at.tag_id = t.id)
        ORDER BY t.name""")
    Promise<List<Tag>> findAttached();

    @Query("""
        SELECT t.name FROM article_tags at JOIN tags t ON t.id = at.tag_id
        WHERE at.article_id = :articleId
        ORDER BY t.name""")
    Promise<List<Tag>> findByArticle(ArticleId articleId);

    /// The tag lists of one page of articles, in one round trip.
    @Query("""
        SELECT at.article_id, t.name FROM article_tags at JOIN tags t ON t.id = at.tag_id
        WHERE at.article_id = ANY(:articleIds)
        ORDER BY at.article_id, t.name""")
    Promise<List<ArticleTagRow>> findByArticles(long[] articleIds);

    @Query("""
        INSERT INTO article_tags (article_id, tag_id)
        SELECT :articleId, t.id FROM tags t WHERE t.name = ANY(:names)
        ON CONFLICT DO NOTHING""")
    Promise<Unit> link(ArticleId articleId, String[] names);

    @Query("""
        DELETE FROM article_tags
        WHERE article_id = :articleId AND tag_id IN (SELECT t.id FROM tags t WHERE t.name = ANY(:names))""")
    Promise<Unit> unlink(ArticleId articleId, String[] names);
}
