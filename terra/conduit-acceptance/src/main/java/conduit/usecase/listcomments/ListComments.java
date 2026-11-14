package conduit.usecase.listcomments;

import java.util.List;

import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import conduit.domain.Caller;
import conduit.domain.Comment;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.Slug;
import conduit.domain.UserId;
import conduit.persistence.ArticlePersistenceFactory;
import conduit.persistence.CommentPersistenceFactory;
import conduit.shared.Ownership;
import conduit.shared.Views;
import conduit.view.CommentsResponse;


/// `GET /articles/{slug}/comments`: optional auth; oldest first, all of them (B-41); a missing article is 404.
@Slice
public interface ListComments {
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

    Promise<CommentsResponse> execute(Request request, SecurityContext security);

    @FunctionalInterface
    interface LoadComments {
        Promise<List<Comment>> load(Target target);

        static LoadComments loadComments(@PgSql PgSqlConnector db) {
            var articles = ArticlePersistenceFactory.articlePersistence(db);
            var comments = CommentPersistenceFactory.commentPersistence(db);

            return target -> Ownership.existing(articles,
                                                target.slug())
                                      .flatMap(key -> comments.findByArticle(target.viewer(),
                                                                             key.id()));
        }
    }

    static ListComments listComments(LoadComments loadComments) {
        return (request, security) -> Caller.viewer(security)
                                            .flatMap(viewer -> Slug.inPath(request.slug())
                                                                   .toResult(NotFound.ARTICLE)
                                                                   .map(slug -> Target.target(viewer, slug)))
                                            .async()
                                            .flatMap(loadComments::load)
                                            .flatMap(comments -> response(comments).async());
    }

    private static Result<CommentsResponse> response(List<Comment> comments) {
        return Result.allOf(comments.stream().map(Views::comment)).map(CommentsResponse::commentsResponse);
    }
}
