package conduit.usecase.createarticle;

import java.time.Instant;
import java.util.List;

import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Patch;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import conduit.adapter.random.Randomness;
import conduit.domain.ArticleBody;
import conduit.domain.ArticleId;
import conduit.domain.Caller.Authenticated;
import conduit.domain.Caller;
import conduit.domain.Clock;
import conduit.domain.ConduitError.RequestInvalid;
import conduit.domain.Description;
import conduit.domain.Patches;
import conduit.domain.Slug;
import conduit.domain.TagList;
import conduit.domain.Title;
import conduit.persistence.ArticlePersistenceFactory;
import conduit.persistence.TagPersistenceFactory;
import conduit.persistence.TagVocabularyFactory;
import conduit.persistence.Transaction;
import conduit.shared.Articles;
import conduit.view.ArticleResponse;
import conduit.view.ArticleView;

import static org.pragmatica.lang.Option.option;


/// `POST /articles`: required auth. One INSERT with a generated slug and no existence query (B-22, B-25); missing
/// tags created in sorted order, then linked; all in one transaction, which also reads the answer back.
/// `tagList` absent is `[]`, `null` or a blank tag is 422 (B-26).
@Slice
public interface CreateArticle {
    record Request(NewArticle article) {
        public static Request request(NewArticle article) {
            return new Request(article);
        }

        public record NewArticle(Option<String> title,
                                 Option<String> description,
                                 Option<String> body,
                                 Patch<List<String>> tagList) {
            public static NewArticle newArticle(Option<String> title,
                                                Option<String> description,
                                                Option<String> body,
                                                Patch<List<String>> tagList) {
                return new NewArticle(title, description, body, tagList);
            }

            public static NewArticle newArticle(String title,
                                                String description,
                                                String body,
                                                Patch<List<String>> tagList) {
                return newArticle(option(title), option(description), option(body), tagList);
            }
        }
    }

    record ValidArticle(Title title, Description description, ArticleBody body, TagList tags) {
        static Result<ValidArticle> validArticle(Request request) {
            return option(request.article()).toResult(RequestInvalid.blanks("title", "description", "body"))
                         .flatMap(ValidArticle::parsed);
        }

        private static Result<ValidArticle> parsed(Request.NewArticle article) {
            return Result.all(Title.title(article.title()),
                              Description.description(article.description()),
                              ArticleBody.articleBody(article.body()),
                              tags(article.tagList()))
                         .map(ValidArticle::new)
                         .mapError(RequestInvalid::merged);
        }

        private static Result<TagList> tags(Patch<List<String>> raw) {
            return Patches.parsePresent(option(raw).or(Patch.absent()),
                                        TagList::tagList)
                          .map(parsed -> parsed.or(TagList.empty()));
        }
    }

    record ArticleDraft(Authenticated author, ValidArticle article, Slug slug, Instant createdAt) {
        public static ArticleDraft articleDraft(Authenticated author,
                                                ValidArticle article,
                                                Slug slug,
                                                Instant createdAt) {
            return new ArticleDraft(author, article, slug, createdAt);
        }
    }

    Promise<ArticleResponse> execute(Request request, SecurityContext security, RequestHeaders headers);

    @FunctionalInterface
    interface Store {
        Promise<ArticleView> create(ArticleDraft draft);

        static Store store(@PgSql PgSqlConnector db) {
            return draft -> Transaction.inTransaction(db, tx -> create(tx, draft));
        }

        private static Promise<ArticleView> create(PgSqlConnector tx, ArticleDraft draft) {
            var articles = ArticlePersistenceFactory.articlePersistence(tx);
            var article = draft.article();
            var authorId = draft.author().id();

            return articles.insert(draft.slug(),
                                   authorId,
                                   article.title(),
                                   article.description(),
                                   article.body(),
                                   draft.createdAt())
                           .flatMap(raw -> ArticleId.stored(raw).async())
                           .flatMap(articleId -> attachTags(tx,
                                                            articleId,
                                                            article.tags()))
                           .flatMap(_ -> Articles.read(tx,
                                                       draft.author().viewer(),
                                                       draft.slug()));
        }

        private static Promise<Unit> attachTags(PgSqlConnector tx, ArticleId articleId, TagList tags) {
            return tags.names()
                       .isEmpty()
                   ? Promise.unitPromise()
                   : insertAndLink(tx, articleId, tags.asArray());
        }

        private static Promise<Unit> insertAndLink(PgSqlConnector tx, ArticleId articleId, String[] names) {
            return TagVocabularyFactory.tagVocabulary(tx)
                                       .insertMissing(names)
                                       .flatMap(_ -> TagPersistenceFactory.tagPersistence(tx).link(articleId, names));
        }
    }

    static CreateArticle createArticle(Store store, Clock clock, Randomness random) {
        return (request, security, headers) -> Caller.required(security, headers)
                                                     .flatMap(caller -> ValidArticle.validArticle(request).map(article -> draft(caller,
                                                                                                                                article,
                                                                                                                                clock,
                                                                                                                                random)))
                                                     .async()
                                                     .flatMap(store::create)
                                                     .map(ArticleResponse::articleResponse);
    }

    private static ArticleDraft draft(Authenticated caller, ValidArticle article, Clock clock, Randomness random) {
        return ArticleDraft.articleDraft(caller,
                                         article,
                                         Slug.generate(article.title(), random),
                                         clock.now());
    }
}
