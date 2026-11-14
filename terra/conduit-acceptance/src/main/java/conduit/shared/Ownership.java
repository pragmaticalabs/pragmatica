package conduit.shared;

import org.pragmatica.lang.Promise;

import conduit.domain.ArticleKey;
import conduit.domain.Caller.Authenticated;
import conduit.domain.ConduitError.Forbidden;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.Slug;
import conduit.persistence.ArticlePersistence;


/// The article an operation addresses: 404 when no article has the slug, 403 when the caller is not its author
/// (B-51 order: 404 before 403).
public sealed interface Ownership {
    static Promise<ArticleKey> existing(ArticlePersistence articles, Slug slug) {
        return articles.findKey(slug)
                       .flatMap(found -> found.async(NotFound.ARTICLE));
    }

    static Promise<ArticleKey> owned(ArticlePersistence articles, Slug slug, Authenticated caller) {
        return existing(articles, slug).filter(Forbidden.ARTICLE,
                                               key -> key.authorId()
                                                         .equals(caller.id()));
    }

    record unused() implements Ownership {}
}
