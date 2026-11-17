package conduit.shared;

import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import conduit.domain.ConduitError.NotFound;
import conduit.domain.Slug;
import conduit.domain.Tag;
import conduit.domain.UserId;
import conduit.persistence.ArticlePersistence;
import conduit.persistence.ArticlePersistenceFactory;
import conduit.persistence.TagPersistence;
import conduit.persistence.TagPersistenceFactory;
import conduit.view.ArticleView;


/// Reading one article as a viewer sees it: the row with its counts and flags, then its tags. Shared by every
/// operation that answers with `{"article": {...}}`; it runs on whatever connector it is given, so a write reads its
/// own result inside its transaction.
public sealed interface Articles {
    static Promise<ArticleView> read(PgSqlConnector db, Option<UserId> viewer, Slug slug) {
        ArticlePersistence articles = ArticlePersistenceFactory.articlePersistence(db);
        TagPersistence tags = TagPersistenceFactory.tagPersistence(db);

        return articles.findBySlug(viewer, slug)
                       .flatMap(found -> found.async(NotFound.ARTICLE))
                       .flatMap(row -> tags.findByArticle(row.id())
                                           .flatMap(names -> Views.article(row,
                                                                           Tag.names(names))
                                                                  .async()));
    }

    record unused() implements Articles {}
}
