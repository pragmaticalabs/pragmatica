package conduit.persistence;

import java.time.Instant;
import java.util.List;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import conduit.domain.Article;
import conduit.domain.ArticleBody;
import conduit.domain.ArticleId;
import conduit.domain.ArticleKey;
import conduit.domain.ArticleListing;
import conduit.domain.Description;
import conduit.domain.Slug;
import conduit.domain.Title;
import conduit.domain.UserId;
import conduit.domain.Username;


/// Articles, their favorites, and the list queries.
///
/// Every list shape has its own statement rather than one query with optional filters: `(:tag IS NULL OR ...)`
/// hides the filter from the planner, while the shared schema's indexes were chosen for exactly these shapes
/// (schema/V1__conduit.sql "serves:" notes). Order is always `created_at DESC, id DESC` (B-34); counts are exact
/// and per request (B-37). An anonymous viewer is an empty `Option`, bound as NULL: `= NULL` matches no row, so `favorited` and
/// `following` are false (B-47).
@PgSql
public interface ArticlePersistence {
    @Query("""
        SELECT a.id, a.slug, a.title, a.description, a.body, a.created_at, a.updated_at, u.username, u.bio, u.image,
               (SELECT count(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count,
               EXISTS (SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = :viewer) AS favorited,
               EXISTS (SELECT 1 FROM follows fo WHERE fo.follower_id = :viewer AND fo.followee_id = a.author_id) AS following
        FROM articles a JOIN users u ON u.id = a.author_id
        WHERE a.slug = :slug""")
    Promise<Option<Article>> findBySlug(Option<UserId> viewer, Slug slug);

    @Query("SELECT id, author_id FROM articles WHERE slug = :slug")
    Promise<Option<ArticleKey>> findKey(Slug slug);

    /// Returns the raw id: a scalar value-object @Query return is decoded as a record (pragmatica #3015); the caller wraps it with `stored`.
    @Query("""
        INSERT INTO articles (slug, author_id, title, description, body, created_at, updated_at)
        VALUES (:slug, :authorId, :title, :description, :body, :createdAt, :createdAt)
        RETURNING id""")
    Promise<Long> insert(Slug slug,
                         UserId authorId,
                         Title title,
                         Description description,
                         ArticleBody body,
                         Instant createdAt);

    /// Absent fields keep their value (`COALESCE` of a NULL parameter); `updated_at` moves on every update (B-29).
    @Query("""
        UPDATE articles
        SET title = COALESCE(:title, title),
            description = COALESCE(:description, description),
            body = COALESCE(:body, body),
            updated_at = :updatedAt
        WHERE id = :id""")
    Promise<Unit> update(Option<Title> title,
                         Option<Description> description,
                         Option<ArticleBody> body,
                         Instant updatedAt,
                         ArticleId id);

    /// Comments, favorites and tag links go with it by `ON DELETE CASCADE` (B-32).
    @Query("DELETE FROM articles WHERE id = :id")
    Promise<Unit> delete(ArticleId id);

    /// Idempotent, and concurrent duplicates are absorbed rather than surfaced (B-43).
    @Query("INSERT INTO favorites (article_id, user_id) VALUES (:articleId, :userId) ON CONFLICT DO NOTHING")
    Promise<Unit> favorite(ArticleId articleId, UserId userId);

    @Query("DELETE FROM favorites WHERE article_id = :articleId AND user_id = :userId")
    Promise<Unit> unfavorite(ArticleId articleId, UserId userId);

    @Query("""
        SELECT a.id, a.slug, a.title, a.description, a.created_at, a.updated_at, u.username, u.bio, u.image,
               (SELECT count(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count,
               EXISTS (SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = :viewer) AS favorited,
               EXISTS (SELECT 1 FROM follows fo WHERE fo.follower_id = :viewer AND fo.followee_id = a.author_id) AS following
        FROM articles a JOIN users u ON u.id = a.author_id
        ORDER BY a.created_at DESC, a.id DESC
        LIMIT :limit OFFSET :offset""")
    Promise<List<ArticleListing>> pageAll(Option<UserId> viewer, int limit, int offset);

    @Query("SELECT count(*) AS n FROM articles")
    Promise<Long> countAll();

    @Query("""
        SELECT a.id, a.slug, a.title, a.description, a.created_at, a.updated_at, u.username, u.bio, u.image,
               (SELECT count(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count,
               EXISTS (SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = :viewer) AS favorited,
               EXISTS (SELECT 1 FROM follows fo WHERE fo.follower_id = :viewer AND fo.followee_id = a.author_id) AS following
        FROM articles a JOIN users u ON u.id = a.author_id
        WHERE EXISTS (SELECT 1 FROM article_tags at JOIN tags t ON t.id = at.tag_id
                      WHERE at.article_id = a.id AND t.name = :tag)
        ORDER BY a.created_at DESC, a.id DESC
        LIMIT :limit OFFSET :offset""")
    Promise<List<ArticleListing>> pageByTag(Option<UserId> viewer, String tag, int limit, int offset);

    @Query("SELECT count(*) AS n FROM article_tags at JOIN tags t ON t.id = at.tag_id WHERE t.name = :tag")
    Promise<Long> countByTag(String tag);

    @Query("""
        SELECT a.id, a.slug, a.title, a.description, a.created_at, a.updated_at, u.username, u.bio, u.image,
               (SELECT count(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count,
               EXISTS (SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = :viewer) AS favorited,
               EXISTS (SELECT 1 FROM follows fo WHERE fo.follower_id = :viewer AND fo.followee_id = a.author_id) AS following
        FROM articles a JOIN users u ON u.id = a.author_id
        WHERE u.username = :author
        ORDER BY a.created_at DESC, a.id DESC
        LIMIT :limit OFFSET :offset""")
    Promise<List<ArticleListing>> pageByAuthor(Option<UserId> viewer, String author, int limit, int offset);

    @Query("SELECT count(*) AS n FROM articles a JOIN users u ON u.id = a.author_id WHERE u.username = :author")
    Promise<Long> countByAuthor(String author);

    /// Ordered by the article's `created_at`, not by when it was favorited (B-38).
    @Query("""
        SELECT a.id, a.slug, a.title, a.description, a.created_at, a.updated_at, u.username, u.bio, u.image,
               (SELECT count(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count,
               EXISTS (SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = :viewer) AS favorited,
               EXISTS (SELECT 1 FROM follows fo WHERE fo.follower_id = :viewer AND fo.followee_id = a.author_id) AS following
        FROM articles a JOIN users u ON u.id = a.author_id
        WHERE EXISTS (SELECT 1 FROM favorites fv JOIN users fu ON fu.id = fv.user_id
                      WHERE fv.article_id = a.id AND fu.username = :favorited)
        ORDER BY a.created_at DESC, a.id DESC
        LIMIT :limit OFFSET :offset""")
    Promise<List<ArticleListing>> pageByFavorited(Option<UserId> viewer, String favorited, int limit, int offset);

    @Query("SELECT count(*) AS n FROM favorites fv JOIN users fu ON fu.id = fv.user_id WHERE fu.username = :favorited")
    Promise<Long> countByFavorited(String favorited);

    /// Two or three filters at once (AND, B-38): rare, so one statement with optional filters serves them all.
    @Query("""
        SELECT a.id, a.slug, a.title, a.description, a.created_at, a.updated_at, u.username, u.bio, u.image,
               (SELECT count(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count,
               EXISTS (SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = :viewer) AS favorited,
               EXISTS (SELECT 1 FROM follows fo WHERE fo.follower_id = :viewer AND fo.followee_id = a.author_id) AS following
        FROM articles a JOIN users u ON u.id = a.author_id
        WHERE (CAST(:tag AS text) IS NULL OR EXISTS (SELECT 1 FROM article_tags at JOIN tags t ON t.id = at.tag_id
                                                     WHERE at.article_id = a.id AND t.name = :tag))
          AND (CAST(:author AS text) IS NULL OR u.username = :author)
          AND (CAST(:favorited AS text) IS NULL OR EXISTS (SELECT 1 FROM favorites fv JOIN users fu ON fu.id = fv.user_id
                                                           WHERE fv.article_id = a.id AND fu.username = :favorited))
        ORDER BY a.created_at DESC, a.id DESC
        LIMIT :limit OFFSET :offset""")
    Promise<List<ArticleListing>> pageFiltered(Option<UserId> viewer,
                                               Option<String> tag,
                                               Option<String> author,
                                               Option<String> favorited,
                                               int limit,
                                               int offset);

    /// `AS total` is load-bearing: pg-codegen reads the scalar column name from the alias, and an unaliased expression
    /// scalar fails the build (pragmatica #2886). Before the fix the generator took the `AS text` of the first CAST in
    /// the WHERE clause for the column name.
    @Query("""
        SELECT count(*) AS total FROM articles a JOIN users u ON u.id = a.author_id
        WHERE (CAST(:tag AS text) IS NULL OR EXISTS (SELECT 1 FROM article_tags at JOIN tags t ON t.id = at.tag_id
                                                     WHERE at.article_id = a.id AND t.name = :tag))
          AND (CAST(:author AS text) IS NULL OR u.username = :author)
          AND (CAST(:favorited AS text) IS NULL OR EXISTS (SELECT 1 FROM favorites fv JOIN users fu ON fu.id = fv.user_id
                                                           WHERE fv.article_id = a.id AND fu.username = :favorited))""")
    Promise<Long> countFiltered(Option<String> tag, Option<String> author, Option<String> favorited);

    /// B-39: articles by authors the viewer follows; never the viewer's own (self-follow is impossible, B-44).
    @Query("""
        SELECT a.id, a.slug, a.title, a.description, a.created_at, a.updated_at, u.username, u.bio, u.image,
               (SELECT count(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count,
               EXISTS (SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = :viewer) AS favorited,
               EXISTS (SELECT 1 FROM follows fo WHERE fo.follower_id = :viewer AND fo.followee_id = a.author_id) AS following
        FROM articles a JOIN users u ON u.id = a.author_id
        WHERE EXISTS (SELECT 1 FROM follows ff WHERE ff.follower_id = :viewer AND ff.followee_id = a.author_id)
        ORDER BY a.created_at DESC, a.id DESC
        LIMIT :limit OFFSET :offset""")
    Promise<List<ArticleListing>> pageFeed(UserId viewer, int limit, int offset);

    @Query("""
        SELECT count(*) AS n FROM articles a JOIN follows ff ON ff.followee_id = a.author_id
        WHERE ff.follower_id = :viewer""")
    Promise<Long> countFeed(UserId viewer);
}
