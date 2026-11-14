package conduit.usecase.listtags;

import java.util.List;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

import conduit.domain.Tag;
import conduit.persistence.TagPersistenceFactory;
import conduit.view.TagsResponse;


/// `GET /tags`: no auth; every tag attached to at least one article, byte order, no limit (B-40).
@Slice
public interface ListTags {
    Promise<TagsResponse> execute();

    @FunctionalInterface
    interface LoadTags {
        Promise<List<String>> load();

        static LoadTags loadTags(@PgSql PgSqlConnector db) {
            var tags = TagPersistenceFactory.tagPersistence(db);

            return () -> tags.findAttached()
                             .map(Tag::names);
        }
    }

    static ListTags listTags(LoadTags loadTags) {
        return () -> loadTags.load()
                             .map(TagsResponse::tagsResponse);
    }
}
