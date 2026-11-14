package conduit.usecase.listarticles;

import java.util.List;

import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import conduit.domain.ArticleFilter;
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


/// `GET /articles?tag&author&favorited&limit&offset`: optional auth. Most recent first with an id tie-break
/// (B-34), page bounds checked not clamped (B-35), exact count (B-37), no `body` key (B-30).
@Slice
public interface ListArticles {
    record Request(Option<String> tag,
                   Option<String> author,
                   Option<String> favorited,
                   Option<String> limit,
                   Option<String> offset) {
        public static Request request(Option<String> tag,
                                      Option<String> author,
                                      Option<String> favorited,
                                      Option<String> limit,
                                      Option<String> offset) {
            return new Request(tag, author, favorited, limit, offset);
        }
    }

    record Query(Option<UserId> viewer, ArticleFilter filter, Page page) {
        static Result<Query> query(Request request, Option<UserId> viewer) {
            return Page.page(request.limit(),
                             request.offset())
                       .map(page -> new Query(viewer,
                                              ArticleFilter.articleFilter(request.tag(),
                                                                          request.author(),
                                                                          request.favorited()),
                                              page));
        }
    }

    Promise<ArticlesResponse> execute(Request request, SecurityContext security);

    @FunctionalInterface
    interface Search {
        Promise<ArticlesResponse> search(Query query);

        static Search search(@PgSql PgSqlConnector db) {
            var articles = ArticlePersistenceFactory.articlePersistence(db);
            var tags = TagPersistenceFactory.tagPersistence(db);

            return query -> page(articles, tags, query);
        }

        /// The count runs after the rows, not beside them, on purpose: the benchmark arms must issue the same query
        /// pattern, so the two statements stay sequential.
        private static Promise<ArticlesResponse> page(ArticlePersistence articles, TagPersistence tags, Query query) {
            return rows(articles, query).flatMap(rows -> counted(articles, tags, query, rows));
        }

        private static Promise<ArticlesResponse> counted(ArticlePersistence articles,
                                                         TagPersistence tags,
                                                         Query query,
                                                         List<ArticleListing> rows) {
            return count(articles, query.filter()).flatMap(count -> ArticlePages.assemble(tags, rows, count));
        }

        private static Promise<List<ArticleListing>> rows(ArticlePersistence articles, Query query) {
            var viewer = query.viewer();
            var limit = query.page().limit();
            var offset = query.page().offset();

            return switch (query.filter()) {
                case ArticleFilter.All _ -> articles.pageAll(viewer, limit, offset);
                case ArticleFilter.ByTag byTag -> articles.pageByTag(viewer, byTag.tag(), limit, offset);
                case ArticleFilter.ByAuthor byAuthor -> articles.pageByAuthor(viewer, byAuthor.author(), limit, offset);
                case ArticleFilter.ByFavorited byFavorited -> articles.pageByFavorited(viewer,
                                                                                       byFavorited.username(),
                                                                                       limit,
                                                                                       offset);
                case ArticleFilter.Combined combined -> articles.pageFiltered(viewer,
                                                                              combined.tag(),
                                                                              combined.author(),
                                                                              combined.favorited(),
                                                                              limit,
                                                                              offset);
            };
        }

        private static Promise<Long> count(ArticlePersistence articles, ArticleFilter filter) {
            return switch (filter) {
                case ArticleFilter.All _ -> articles.countAll();
                case ArticleFilter.ByTag byTag -> articles.countByTag(byTag.tag());
                case ArticleFilter.ByAuthor byAuthor -> articles.countByAuthor(byAuthor.author());
                case ArticleFilter.ByFavorited byFavorited -> articles.countByFavorited(byFavorited.username());
                case ArticleFilter.Combined combined -> articles.countFiltered(combined.tag(),
                                                                               combined.author(),
                                                                               combined.favorited());
            };
        }
    }

    static ListArticles listArticles(Search search) {
        return (request, security) -> Caller.viewer(security)
                                            .flatMap(viewer -> Query.query(request, viewer))
                                            .async()
                                            .flatMap(search::search);
    }
}
