package conduit.shared;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import conduit.domain.ArticleId;
import conduit.domain.ArticleKey;
import conduit.domain.Caller.Authenticated;
import conduit.domain.ConduitError.Forbidden;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.Slug;
import conduit.domain.UserId;
import conduit.persistence.ArticlePersistence;

import static conduit.domain.Expect.failure;
import static conduit.domain.Expect.success;
import static org.assertj.core.api.Assertions.assertThat;


/// `Ownership.owned` on a stubbed store: the author check and the 404-before-403 order (B-51), without a database.
class OwnershipTest {
    private static final UserId AUTHOR = success(UserId.userId(7));
    private static final UserId OTHER = success(UserId.userId(8));
    private static final Slug SLUG = success(Slug.slug("an-article"));
    private static final ArticleKey KEY = ArticleKey.articleKey(success(ArticleId.articleId(1)), AUTHOR);

    /// Only `findKey` is answered; any other call fails the test with an UnsupportedOperationException.
    static ArticlePersistence storeHolding(Option<ArticleKey> found) {
        return (ArticlePersistence) Proxy.newProxyInstance(ArticlePersistence.class.getClassLoader(),
                                                           new Class<?>[]{ArticlePersistence.class},
                                                           (proxy, method, args) -> method.getName().equals("findKey")
                                                                                    ? Promise.success(found)
                                                                                    : unsupported(method.getName()));
    }

    private static Object unsupported(String name) {
        throw new UnsupportedOperationException(name);
    }

    @Test
    void authorOwnsTheArticle() {
        var owned = success(Ownership.owned(storeHolding(Option.some(KEY)), SLUG, Authenticated.authenticated(AUTHOR, "t")));

        assertThat(owned).isEqualTo(KEY);
    }

    @Test
    void anotherUserGetsForbidden() {
        var cause = failure(Ownership.owned(storeHolding(Option.some(KEY)), SLUG, Authenticated.authenticated(OTHER, "t")));

        assertThat(cause).isSameAs(Forbidden.ARTICLE);
    }

    @Test
    void missingArticleIsNotFoundBeforeAnyOwnershipQuestion() {
        var cause = failure(Ownership.owned(storeHolding(Option.none()), SLUG, Authenticated.authenticated(OTHER, "t")));

        assertThat(cause).isSameAs(NotFound.ARTICLE);
    }
}
