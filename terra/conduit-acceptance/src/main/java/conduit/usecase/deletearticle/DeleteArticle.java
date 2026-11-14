package conduit.usecase.deletearticle;

import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import conduit.domain.Caller.Authenticated;
import conduit.domain.Caller;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.Slug;
import conduit.persistence.ArticlePersistenceFactory;
import conduit.persistence.Transaction;
import conduit.shared.Ownership;


/// `DELETE /articles/{slug}`: required auth, author only (404 before 403). Comments, favorites and tag links go by
/// cascade; tag rows stay (B-32). The suite expects 204.
@Slice
public interface DeleteArticle {
    record Request(String slug) {
        public static Request request(String slug) {
            return new Request(slug);
        }
    }

    record Target(Authenticated caller, Slug slug) {
        public static Target target(Authenticated caller, Slug slug) {
            return new Target(caller, slug);
        }
    }

    Promise<Unit> execute(Request request, SecurityContext security, RequestHeaders headers);

    @FunctionalInterface
    interface Store {
        Promise<Unit> delete(Target target);

        static Store store(@PgSql PgSqlConnector db) {
            return target -> Transaction.inTransaction(db, tx -> delete(tx, target));
        }

        private static Promise<Unit> delete(PgSqlConnector tx, Target target) {
            var articles = ArticlePersistenceFactory.articlePersistence(tx);

            return Ownership.owned(articles,
                                   target.slug(),
                                   target.caller())
                            .flatMap(key -> articles.delete(key.id()));
        }
    }

    static DeleteArticle deleteArticle(Store store) {
        return (request, security, headers) -> Caller.required(security, headers)
                                                     .flatMap(caller -> Slug.inPath(request.slug())
                                                                            .toResult(NotFound.ARTICLE)
                                                                            .map(slug -> Target.target(caller, slug)))
                                                     .async()
                                                     .flatMap(store::delete);
    }
}
