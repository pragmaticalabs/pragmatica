package conduit.usecase.deletecomment;

import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import conduit.domain.Caller.Authenticated;
import conduit.domain.Caller;
import conduit.domain.CommentId;
import conduit.domain.ConduitError.Forbidden;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.Slug;
import conduit.persistence.ArticlePersistence;
import conduit.persistence.ArticlePersistenceFactory;
import conduit.persistence.CommentPersistence;
import conduit.persistence.CommentPersistenceFactory;
import conduit.persistence.Transaction;
import conduit.shared.Ownership;


/// `DELETE /articles/{slug}/comments/{id}`: required auth. A missing article is `article: not found`; a non-integer
/// id, or a comment under another article, is `comment: not found` (B-46); only the comment's author may delete it,
/// the article's author included gets `comment: forbidden` (B-45). One transaction. The suite expects 204.
@Slice
public interface DeleteComment {
    /// `id` stays text so that `abc` is a 404 here rather than a binder's 400.
    record Request(String slug, String id) {
        public static Request request(String slug, String id) {
            return new Request(slug, id);
        }
    }

    /// `id` is empty when the path text is not an integer. That is `comment: not found`, but only after the article
    /// lookup succeeds (B-46: a missing article is `article: not found` whatever the id), so the store answers it.
    record Target(Authenticated caller, Slug slug, Option<CommentId> id) {
        public static Target target(Authenticated caller, Slug slug, Option<CommentId> id) {
            return new Target(caller, slug, id);
        }
    }

    Promise<Unit> execute(Request request, SecurityContext security, RequestHeaders headers);

    @FunctionalInterface
    interface Store {
        Promise<Unit> delete(Target target);

        static Store store(@PgSql PgSqlConnector db) {
            return target -> Transaction.inTransaction(db, tx -> delete(tx, target));
        }

        private static Promise<Unit> delete(PgSqlConnector tx, Target target) {
            return delete(ArticlePersistenceFactory.articlePersistence(tx),
                          CommentPersistenceFactory.commentPersistence(tx),
                          target);
        }

        /// The steps over the two stores, public so that a test can run them on stubs: no database is needed to pin
        /// the 404-before-403 order and the author check.
        static Promise<Unit> delete(ArticlePersistence articles, CommentPersistence comments, Target target) {
            return Ownership.existing(articles,
                                      target.slug())
                            .flatMap(article -> target.id()
                                                      .async(NotFound.COMMENT)
                                                      .flatMap(id -> comments.findKey(id,
                                                                                      article.id())))
                            .flatMap(found -> found.async(NotFound.COMMENT))
                            .filter(Forbidden.COMMENT,
                                    comment -> comment.authorId()
                                                      .equals(target.caller().id()))
                            .flatMap(comment -> comments.delete(comment.id()));
        }
    }

    static DeleteComment deleteComment(Store store) {
        return (request, security, headers) -> Caller.required(security, headers)
                                                     .flatMap(caller -> Slug.inPath(request.slug())
                                                                            .toResult(NotFound.ARTICLE)
                                                                            .map(slug -> Target.target(caller,
                                                                                                       slug,
                                                                                                       CommentId.inPath(request.id()))))
                                                     .async()
                                                     .flatMap(store::delete);
    }
}
