package conduit.usecase.getarticle;

import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import conduit.domain.Caller;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.Slug;
import conduit.domain.UserId;
import conduit.shared.Articles;
import conduit.view.ArticleResponse;
import conduit.view.ArticleView;


/// `GET /articles/{slug}`: optional auth; the single-article view includes `body` (B-30).
@Slice
public interface GetArticle {
    record Request(String slug) {
        public static Request request(String slug) {
            return new Request(slug);
        }
    }

    record Target(Option<UserId> viewer, Slug slug) {
        public static Target target(Option<UserId> viewer, Slug slug) {
            return new Target(viewer, slug);
        }
    }

    Promise<ArticleResponse> execute(Request request, SecurityContext security);

    @FunctionalInterface
    interface ReadArticle {
        Promise<ArticleView> read(Target target);

        static ReadArticle readArticle(@PgSql PgSqlConnector db) {
            return target -> Articles.read(db, target.viewer(), target.slug());
        }
    }

    static GetArticle getArticle(ReadArticle readArticle) {
        return (request, security) -> Caller.viewer(security)
                                            .flatMap(viewer -> Slug.inPath(request.slug())
                                                                   .toResult(NotFound.ARTICLE)
                                                                   .map(slug -> Target.target(viewer, slug)))
                                            .async()
                                            .flatMap(readArticle::read)
                                            .map(ArticleResponse::articleResponse);
    }
}
