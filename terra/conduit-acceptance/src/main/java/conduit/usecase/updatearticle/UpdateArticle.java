package conduit.usecase.updatearticle;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

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

import conduit.domain.ArticleBody;
import conduit.domain.ArticleId;
import conduit.domain.Caller.Authenticated;
import conduit.domain.Caller;
import conduit.domain.Clock;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.ConduitError.RequestInvalid;
import conduit.domain.Description;
import conduit.domain.Patches;
import conduit.domain.Slug;
import conduit.domain.Tag;
import conduit.domain.TagList;
import conduit.domain.Title;
import conduit.persistence.ArticlePersistence;
import conduit.persistence.ArticlePersistenceFactory;
import conduit.persistence.TagPersistence;
import conduit.persistence.TagPersistenceFactory;
import conduit.persistence.TagVocabularyFactory;
import conduit.persistence.Transaction;
import conduit.shared.Articles;
import conduit.shared.Ownership;
import conduit.view.ArticleResponse;
import conduit.view.ArticleView;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// `PUT /articles/{slug}`: required auth, author only. The slug never changes, even when the title does (B-24);
/// `updatedAt` moves on every update (B-29). `"article": {}` is `article: can't be empty`; `null` or blank
/// title/description/body is `can't be blank` (B-28). `tagList` absent keeps the tags, `[]` removes them all, `null`
/// is 422 (B-26). Tags are diffed: only removed links are deleted and only added ones inserted, new names created in
/// sorted order. One transaction.
@Slice
public interface UpdateArticle {
    record Request(String slug, Changes article) {
        public static Request request(String slug, Changes article) {
            return new Request(slug, article);
        }

        public record Changes(Patch<String> title,
                              Patch<String> description,
                              Patch<String> body,
                              Patch<List<String>> tagList) {
            public static Changes changes(Patch<String> title,
                                          Patch<String> description,
                                          Patch<String> body,
                                          Patch<List<String>> tagList) {
                return new Changes(title, description, body, tagList);
            }
        }

        static final Changes NONE = Changes.changes(Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent());
    }

    record ValidChanges(Option<Title> title,
                        Option<Description> description,
                        Option<ArticleBody> body,
                        Option<TagList> tags) {
        private static final RequestInvalid EMPTY = RequestInvalid.requestInvalid("article", "can't be empty");

        static Result<ValidChanges> validChanges(Request request) {
            var changes = option(request.article()).or(Request.NONE);

            return nonEmpty(changes).flatMap(ValidChanges::parsed);
        }

        private static Result<Request.Changes> nonEmpty(Request.Changes changes) {
            var anyPresent = Stream.of(changes.title(),
                                       changes.description(),
                                       changes.body(),
                                       changes.tagList())
                                   .anyMatch(patch -> !Patches.isAbsent(patch));

            return anyPresent
                   ? success(changes)
                   : EMPTY.result();
        }

        private static Result<ValidChanges> parsed(Request.Changes changes) {
            return Result.all(Patches.parsePresent(changes.title(),
                                                   Title::title),
                              Patches.parsePresent(changes.description(),
                                                   Description::description),
                              Patches.parsePresent(changes.body(),
                                                   ArticleBody::articleBody),
                              Patches.parsePresent(changes.tagList(),
                                                   TagList::tagList))
                         .map(ValidChanges::new)
                         .mapError(RequestInvalid::merged);
        }
    }

    record Edit(Authenticated caller, Slug slug, ValidChanges changes, Instant updatedAt) {
        public static Edit edit(Authenticated caller, Slug slug, ValidChanges changes, Instant updatedAt) {
            return new Edit(caller, slug, changes, updatedAt);
        }
    }

    /// The tag links to delete and to add, so an update writes only what changed.
    record TagDiff(List<String> removed, List<String> added) {
        static TagDiff tagDiff(List<String> current, TagList wanted) {
            var removed = current.stream().filter(name -> !wanted.names()
                                                                 .contains(name)).toList();
            var added = wanted.names().stream().filter(name -> !current.contains(name)).toList();

            return new TagDiff(removed, added);
        }
    }

    Promise<ArticleResponse> execute(Request request, SecurityContext security, RequestHeaders headers);

    @FunctionalInterface
    interface Store {
        Promise<ArticleView> update(Edit edit);

        static Store store(@PgSql PgSqlConnector db) {
            return edit -> Transaction.inTransaction(db, tx -> update(tx, edit));
        }

        private static Promise<ArticleView> update(PgSqlConnector tx, Edit edit) {
            var articles = ArticlePersistenceFactory.articlePersistence(tx);

            return Ownership.owned(articles,
                                   edit.slug(),
                                   edit.caller())
                            .flatMap(key -> writeFields(articles,
                                                        edit,
                                                        key.id()).map(_ -> key.id()))
                            .flatMap(articleId -> retag(tx,
                                                        edit.changes(),
                                                        articleId))
                            .flatMap(_ -> Articles.read(tx,
                                                        edit.caller().viewer(),
                                                        edit.slug()));
        }

        private static Promise<Unit> writeFields(ArticlePersistence articles, Edit edit, ArticleId articleId) {
            var changes = edit.changes();

            return articles.update(changes.title(), changes.description(), changes.body(), edit.updatedAt(), articleId);
        }

        private static Promise<Unit> retag(PgSqlConnector tx, ValidChanges changes, ArticleId articleId) {
            return changes.tags()
                          .map(wanted -> relink(tx, articleId, wanted))
                          .or(Promise.unitPromise());
        }

        private static Promise<Unit> relink(PgSqlConnector tx, ArticleId articleId, TagList wanted) {
            var tags = TagPersistenceFactory.tagPersistence(tx);

            return tags.findByArticle(articleId)
                       .map(current -> TagDiff.tagDiff(Tag.names(current),
                                                       wanted))
                       .flatMap(diff -> unlink(tags, articleId, diff).flatMap(_ -> link(tx, articleId, diff)));
        }

        private static Promise<Unit> unlink(TagPersistence tags, ArticleId articleId, TagDiff diff) {
            return diff.removed()
                       .isEmpty()
                   ? Promise.unitPromise()
                   : tags.unlink(articleId,
                                 diff.removed().toArray(String[]::new));
        }

        private static Promise<Unit> link(PgSqlConnector tx, ArticleId articleId, TagDiff diff) {
            return diff.added()
                       .isEmpty()
                   ? Promise.unitPromise()
                   : insertAndLink(tx,
                                   articleId,
                                   diff.added().toArray(String[]::new));
        }

        private static Promise<Unit> insertAndLink(PgSqlConnector tx, ArticleId articleId, String[] names) {
            return TagVocabularyFactory.tagVocabulary(tx)
                                       .insertMissing(names)
                                       .flatMap(_ -> TagPersistenceFactory.tagPersistence(tx).link(articleId, names));
        }
    }

    static UpdateArticle updateArticle(Store store, Clock clock) {
        return (request, security, headers) -> Caller.required(security, headers)
                                                     .flatMap(caller -> edit(request, caller, clock))
                                                     .async()
                                                     .flatMap(store::update)
                                                     .map(ArticleResponse::articleResponse);
    }

    private static Result<Edit> edit(Request request, Authenticated caller, Clock clock) {
        return ValidChanges.validChanges(request).flatMap(changes -> Slug.inPath(request.slug())
                                                                         .toResult(NotFound.ARTICLE)
                                                                         .map(slug -> Edit.edit(caller,
                                                                                                slug,
                                                                                                changes,
                                                                                                clock.now())));
    }
}
