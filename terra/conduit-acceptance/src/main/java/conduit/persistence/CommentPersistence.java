package conduit.persistence;

import java.time.Instant;
import java.util.List;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import conduit.domain.ArticleId;
import conduit.domain.Comment;
import conduit.domain.CommentBody;
import conduit.domain.CommentId;
import conduit.domain.UserId;


/// The `comments` table.
@PgSql
public interface CommentPersistence {
    record CommentKey(CommentId id, UserId authorId) {
        public static CommentKey commentKey(CommentId id, UserId authorId) {
            return new CommentKey(id, authorId);
        }
    }

    /// `updated_at` = `created_at`: comments cannot be edited (B-49).
    /// Returns the raw id: a scalar value-object @Query return is decoded as a record (pragmatica #3015); the caller wraps it with `stored`.
    @Query("""
        INSERT INTO comments (article_id, author_id, body, created_at, updated_at)
        VALUES (:articleId, :authorId, :body, :createdAt, :createdAt)
        RETURNING id""")
    Promise<Long> insert(ArticleId articleId, UserId authorId, CommentBody body, Instant createdAt);

    /// Oldest first, all of them (B-41).
    @Query("""
        SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image,
               EXISTS (SELECT 1 FROM follows f WHERE f.follower_id = :viewer AND f.followee_id = c.author_id) AS following
        FROM comments c JOIN users u ON u.id = c.author_id
        WHERE c.article_id = :articleId
        ORDER BY c.id""")
    Promise<List<Comment>> findByArticle(Option<UserId> viewer, ArticleId articleId);

    /// A comment addressed under another article is not found (B-46).
    @Query("SELECT id, author_id FROM comments WHERE id = :id AND article_id = :articleId")
    Promise<Option<CommentKey>> findKey(CommentId id, ArticleId articleId);

    @Query("DELETE FROM comments WHERE id = :id")
    Promise<Unit> delete(CommentId id);
}
