package conduit.usecase.feedarticles;

import java.util.List;

import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import conduit.domain.Caller.Authenticated;
import conduit.domain.ArticleListing;
import conduit.domain.Caller;
import conduit.domain.Page;
import conduit.domain.UserId;
import conduit.persistence.ArticlePersistence;
import conduit.persistence.ArticlePersistenceFactory;
import conduit.persistence.TagPersistence;
import conduit.persistence.TagPersistenceFactory;
import conduit.shared.ArticlePages;
import conduit.view.ArticlesResponse;


/// `GET /articles/feed?limit&offset`: required auth; articles by followed authors, never the caller's own (B-39).
/// `tag`/`author`/`favorited` are not parameters here, so they are ignored by construction.
@Slice
public interface FeedArticles {
    record Request(Option<String> limit, Option<String> offset) {
        public static Request request(Option<String> limit, Option<String> offset) {
            return new Request(limit, offset);
        }
    }

    record Query(Authenticated caller, Page page) {
        static Result<Query> query(Request request, Authenticated caller) {
            return Page.page(request.limit(),
                             request.offset())
                       .map(page -> new Query(caller, page));
        }
    }

    Promise<ArticlesResponse> execute(Request request, SecurityContext security, RequestHeaders headers);

    @FunctionalInterface
    interface Feed {
        Promise<ArticlesResponse> feed(Query query);

        static Feed feed(@PgSql PgSqlConnector db) {
            var articles = ArticlePersistenceFactory.articlePersistence(db);
            var tags = TagPersistenceFactory.tagPersistence(db);

            return query -> page(articles, tags, query);
        }

        private static Promise<ArticlesResponse> page(ArticlePersistence articles, TagPersistence tags, Query query) {
            var viewer = query.caller().id();

            return articles.pageFeed(viewer,
                                     query.page().limit(),
                                     query.page().offset())
                           .flatMap(rows -> counted(articles, tags, viewer, rows));
        }

        /// The count runs after the rows, not beside them, on purpose: the benchmark arms must issue the same query
        /// pattern, so the two statements stay sequential.
        private static Promise<ArticlesResponse> counted(ArticlePersistence articles,
                                                         TagPersistence tags,
                                                         UserId viewer,
                                                         List<ArticleListing> rows) {
            return articles.countFeed(viewer)
                           .flatMap(count -> ArticlePages.assemble(tags, rows, count));
        }
    }

    static FeedArticles feedArticles(Feed feed) {
        return (request, security, headers) -> Caller.required(security, headers)
                                                     .flatMap(caller -> Query.query(request, caller))
                                                     .async()
                                                     .flatMap(feed::feed);
    }
}
