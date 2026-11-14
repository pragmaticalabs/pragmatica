package conduit.usecase.unfavoritearticle;

import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

import conduit.domain.Caller.Authenticated;
import conduit.domain.Caller;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.Slug;
import conduit.persistence.ArticlePersistenceFactory;
import conduit.persistence.Transaction;
import conduit.shared.Articles;
import conduit.shared.Ownership;
import conduit.view.ArticleResponse;
import conduit.view.ArticleView;


/// `DELETE /articles/{slug}/favorite`: required auth. Unfavoriting a non-favorite is 200 (B-43). The article's
/// `updatedAt` does not move (B-29). The lookup, the write and the read-back share one transaction.
@Slice
public interface UnfavoriteArticle {
    record Request(String slug) {
        public static Request request(String slug) {
            return new Request(slug);
        }
    }

    record Target(Authenticated caller, Slug slug) {
        public static Target target(Authenticated caller, Slug slug) {
            return new Target(caller, slug);
        }
    }

    Promise<ArticleResponse> execute(Request request, SecurityContext security, RequestHeaders headers);

    @FunctionalInterface
    interface Store {
        Promise<ArticleView> unfavorite(Target target);

        static Store store(@PgSql PgSqlConnector db) {
            return target -> Transaction.inTransaction(db, tx -> unfavorite(tx, target));
        }

        private static Promise<ArticleView> unfavorite(PgSqlConnector tx, Target target) {
            var articles = ArticlePersistenceFactory.articlePersistence(tx);
            var userId = target.caller().id();

            return Ownership.existing(articles,
                                      target.slug())
                            .flatMap(key -> articles.unfavorite(key.id(),
                                                                userId))
                            .flatMap(_ -> Articles.read(tx,
                                                        target.caller().viewer(),
                                                        target.slug()));
        }
    }

    static UnfavoriteArticle unfavoriteArticle(Store store) {
        return (request, security, headers) -> Caller.required(security, headers)
                                                     .flatMap(caller -> Slug.inPath(request.slug())
                                                                            .toResult(NotFound.ARTICLE)
                                                                            .map(slug -> Target.target(caller, slug)))
                                                     .async()
                                                     .flatMap(store::unfavorite)
                                                     .map(ArticleResponse::articleResponse);
    }
}
