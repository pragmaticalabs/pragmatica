package conduit.shared;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Result;

import conduit.domain.Article;
import conduit.domain.ArticleListing;
import conduit.domain.Comment;
import conduit.domain.CommentBody;
import conduit.domain.CommentId;
import conduit.domain.Profile;
import conduit.domain.Timestamps;
import conduit.domain.User;
import conduit.view.ArticleSummary;
import conduit.view.ArticleView;
import conduit.view.CommentView;
import conduit.view.ProfileView;
import conduit.view.UserView;


/// Rows to wire views. The one place timestamps are rendered (B-48).
public sealed interface Views {
    static UserView user(User row, String token) {
        return UserView.userView(row.email().value(),
                                 token,
                                 row.username().value(),
                                 row.bio(),
                                 row.image());
    }

    static ProfileView profile(Profile row) {
        return ProfileView.profileView(row.username().value(),
                                       row.bio(),
                                       row.image(),
                                       row.following());
    }

    /// The caller's own profile, as the author of what it just wrote: nobody follows themselves (B-44).
    static ProfileView ownProfile(User row) {
        return ProfileView.profileView(row.username().value(),
                                       row.bio(),
                                       row.image(),
                                       false);
    }

    static Result<ArticleView> article(Article row, List<String> tags) {
        return ArticleView.articleView(row.slug().value(),
                                       row.title().value(),
                                       row.description().value(),
                                       row.body().value(),
                                       List.copyOf(tags),
                                       Timestamps.wire(row.createdAt()),
                                       Timestamps.wire(row.updatedAt()),
                                       row.favorited(),
                                       row.favoritesCount(),
                                       ProfileView.profileView(row.username().value(),
                                                               row.bio(),
                                                               row.image(),
                                                               row.following()));
    }

    static Result<ArticleSummary> summary(ArticleListing row, List<String> tags) {
        return ArticleSummary.articleSummary(row.slug().value(),
                                             row.title().value(),
                                             row.description().value(),
                                             List.copyOf(tags),
                                             Timestamps.wire(row.createdAt()),
                                             Timestamps.wire(row.updatedAt()),
                                             row.favorited(),
                                             row.favoritesCount(),
                                             ProfileView.profileView(row.username().value(),
                                                                     row.bio(),
                                                                     row.image(),
                                                                     row.following()));
    }

    static Result<CommentView> comment(Comment row) {
        return CommentView.commentView(row.id().value(),
                                       Timestamps.wire(row.createdAt()),
                                       Timestamps.wire(row.updatedAt()),
                                       row.body().value(),
                                       ProfileView.profileView(row.username().value(),
                                                               row.bio(),
                                                               row.image(),
                                                               row.following()));
    }

    /// A comment just written: it cannot have been edited (B-49), and its author is the caller (B-44).
    static Result<CommentView> comment(CommentId id, CommentBody body, Instant createdAt, User author) {
        return CommentView.commentView(id.value(),
                                       Timestamps.wire(createdAt),
                                       Timestamps.wire(createdAt),
                                       body.value(),
                                       ownProfile(author));
    }

    record unused() implements Views {}
}
