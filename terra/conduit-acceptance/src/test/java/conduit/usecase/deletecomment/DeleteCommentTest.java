package conduit.usecase.deletecomment;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import conduit.domain.ArticleId;
import conduit.domain.ArticleKey;
import conduit.domain.Caller.Authenticated;
import conduit.domain.CommentId;
import conduit.domain.ConduitError.Forbidden;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.Slug;
import conduit.domain.UserId;
import conduit.persistence.ArticlePersistence;
import conduit.persistence.CommentPersistence;
import conduit.persistence.CommentPersistence.CommentKey;
import conduit.usecase.deletecomment.DeleteComment.Store;
import conduit.usecase.deletecomment.DeleteComment.Target;

import static conduit.domain.Expect.failure;
import static conduit.domain.Expect.success;
import static org.assertj.core.api.Assertions.assertThat;


/// The delete steps on stubbed stores: only the comment's author may delete it (B-45), a missing article or comment is a 404 first (B-46).
class DeleteCommentTest {
    private static final UserId COMMENT_AUTHOR = success(UserId.userId(7));
    private static final UserId ARTICLE_AUTHOR = success(UserId.userId(8));
    private static final Slug SLUG = success(Slug.slug("an-article"));
    private static final ArticleId ARTICLE_ID = success(ArticleId.articleId(1));
    private static final CommentId COMMENT_ID = success(CommentId.commentId(5));
    private static final CommentKey COMMENT = CommentKey.commentKey(COMMENT_ID, COMMENT_AUTHOR);

    private final AtomicInteger deletes = new AtomicInteger();

    private ArticlePersistence articles(Option<ArticleKey> found) {
        return stub(ArticlePersistence.class, "findKey", Promise.success(found));
    }

    private CommentPersistence comments(Option<CommentKey> found) {
        return (CommentPersistence) Proxy.newProxyInstance(CommentPersistence.class.getClassLoader(),
                                                           new Class<?>[]{CommentPersistence.class},
                                                           (proxy, method, args) -> answer(method.getName(), found));
    }

    private Object answer(String name, Option<CommentKey> found) {
        return switch (name) {
            case "findKey" -> Promise.success(found);
            case "delete" -> {
                deletes.incrementAndGet();
                yield Promise.unitPromise();
            }
            default -> throw new UnsupportedOperationException(name);
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, String answered, Object value) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(),
                                          new Class<?>[]{type},
                                          (proxy, method, args) -> {
                                              if (method.getName().equals(answered)) {
                                                  return value;
                                              }
                                              throw new UnsupportedOperationException(method.getName());
                                          });
    }

    private static Target by(UserId caller, Option<CommentId> id) {
        return Target.target(Authenticated.authenticated(caller, "t"), SLUG, id);
    }

    private static Option<ArticleKey> article() {
        return Option.some(ArticleKey.articleKey(ARTICLE_ID, ARTICLE_AUTHOR));
    }

    @Test
    void theCommentsAuthorDeletesIt() {
        var result = success(Store.delete(articles(article()), comments(Option.some(COMMENT)), by(COMMENT_AUTHOR, Option.some(COMMENT_ID))));

        assertThat(result).isEqualTo(Unit.unit());
        assertThat(deletes).hasValue(1);
    }

    @Test
    void anotherUserGetsForbiddenAndNothingIsDeleted() {
        var cause = failure(Store.delete(articles(article()), comments(Option.some(COMMENT)), by(ARTICLE_AUTHOR, Option.some(COMMENT_ID))));

        assertThat(cause).isSameAs(Forbidden.COMMENT);
        assertThat(deletes).hasValue(0);
    }

    @Test
    void missingArticleIsNotFound() {
        var cause = failure(Store.delete(articles(Option.none()), comments(Option.some(COMMENT)), by(COMMENT_AUTHOR, Option.some(COMMENT_ID))));

        assertThat(cause).isSameAs(NotFound.ARTICLE);
    }

    @Test
    void missingCommentIsNotFound() {
        var cause = failure(Store.delete(articles(article()), comments(Option.none()), by(COMMENT_AUTHOR, Option.some(COMMENT_ID))));

        assertThat(cause).isSameAs(NotFound.COMMENT);
        assertThat(deletes).hasValue(0);
    }
}
