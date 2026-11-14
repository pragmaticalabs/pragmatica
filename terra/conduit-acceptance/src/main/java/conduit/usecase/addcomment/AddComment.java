package conduit.usecase.addcomment;

import java.time.Instant;

import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import conduit.domain.ArticleId;
import conduit.domain.Caller.Authenticated;
import conduit.domain.Caller;
import conduit.domain.Clock;
import conduit.domain.CommentBody;
import conduit.domain.CommentId;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.ConduitError.RequestInvalid;
import conduit.domain.ConduitError.Unauthorized;
import conduit.domain.Slug;
import conduit.persistence.ArticlePersistenceFactory;
import conduit.persistence.CommentPersistenceFactory;
import conduit.persistence.Transaction;
import conduit.persistence.UserPersistenceFactory;
import conduit.shared.Ownership;
import conduit.shared.Views;
import conduit.view.CommentResponse;
import conduit.view.CommentView;

import static org.pragmatica.lang.Option.option;


/// `POST /articles/{slug}/comments`: required auth; blank body is 422, then a missing article 404 (B-51).
/// `updatedAt` = `createdAt` (B-49); the article's own `updatedAt` does not move (B-29). One transaction.
@Slice
public interface AddComment {
    record Request(String slug, NewComment comment) {
        public static Request request(String slug, NewComment comment) {
            return new Request(slug, comment);
        }

        public record NewComment(Option<String> body) {
            public static NewComment newComment(Option<String> body) {
                return new NewComment(body);
            }

            public static NewComment newComment(String body) {
                return newComment(option(body));
            }
        }
    }

    record CommentDraft(Authenticated author, Slug slug, CommentBody body, Instant createdAt) {
        public static CommentDraft commentDraft(Authenticated author, Slug slug, CommentBody body, Instant createdAt) {
            return new CommentDraft(author, slug, body, createdAt);
        }
    }

    Promise<CommentResponse> execute(Request request, SecurityContext security, RequestHeaders headers);

    @FunctionalInterface
    interface Store {
        Promise<CommentView> add(CommentDraft draft);

        static Store store(@PgSql PgSqlConnector db) {
            return draft -> Transaction.inTransaction(db, tx -> add(tx, draft));
        }

        private static Promise<CommentView> add(PgSqlConnector tx, CommentDraft draft) {
            return Ownership.existing(ArticlePersistenceFactory.articlePersistence(tx),
                                      draft.slug())
                            .flatMap(key -> insert(tx,
                                                   key.id(),
                                                   draft))
                            .flatMap(commentId -> authored(tx, commentId, draft));
        }

        private static Promise<CommentId> insert(PgSqlConnector tx, ArticleId articleId, CommentDraft draft) {
            return CommentPersistenceFactory.commentPersistence(tx)
                                            .insert(articleId,
                                                    draft.author().id(),
                                                    draft.body(),
                                                    draft.createdAt())
                                            .flatMap(id -> CommentId.stored(id).async());
        }

        private static Promise<CommentView> authored(PgSqlConnector tx, CommentId commentId, CommentDraft draft) {
            return UserPersistenceFactory.userPersistence(tx)
                                         .findById(draft.author().id())
                                         .flatMap(found -> found.async(Unauthorized.TOKEN_INVALID))
                                         .flatMap(author -> Views.comment(commentId,
                                                                          draft.body(),
                                                                          draft.createdAt(),
                                                                          author)
                                                                 .async());
        }
    }

    static AddComment addComment(Store store, Clock clock) {
        return (request, security, headers) -> Caller.required(security, headers)
                                                     .flatMap(caller -> draft(request, caller, clock))
                                                     .async()
                                                     .flatMap(store::add)
                                                     .map(CommentResponse::commentResponse);
    }

    private static Result<CommentDraft> draft(Request request, Authenticated caller, Clock clock) {
        return validBody(request).flatMap(body -> Slug.inPath(request.slug())
                                                      .toResult(NotFound.ARTICLE)
                                                      .map(slug -> CommentDraft.commentDraft(caller,
                                                                                             slug,
                                                                                             body,
                                                                                             clock.now())));
    }

    private static Result<CommentBody> validBody(Request request) {
        return option(request.comment()).toResult(RequestInvalid.blank("body"))
                     .flatMap(comment -> CommentBody.commentBody(comment.body()));
    }
}
