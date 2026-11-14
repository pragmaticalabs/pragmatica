package conduit.view;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// An article as a list item: the `body` key is omitted entirely, not null (B-30).
public record ArticleSummary(String slug,
                             String title,
                             String description,
                             List<String> tagList,
                             String createdAt,
                             String updatedAt,
                             boolean favorited,
                             long favoritesCount,
                             ProfileView author) {
    /// Invariants: `favoritesCount >= 0` and `updatedAt >= createdAt`. The timestamps are wire strings of one fixed width, so string order is time order.
    public static Result<ArticleSummary> articleSummary(String slug,
                                                        String title,
                                                        String description,
                                                        List<String> tagList,
                                                        String createdAt,
                                                        String updatedAt,
                                                        boolean favorited,
                                                        long favoritesCount,
                                                        ProfileView author) {
        return Verify.ensure(favoritesCount, count -> count >= 0, Invalid.NEGATIVE_FAVORITES)
                     .flatMap(_ -> Verify.ensure(updatedAt,
                                                 updated -> updated.compareTo(createdAt) >= 0,
                                                 Invalid.UPDATED_BEFORE_CREATED))
                     .map(_ -> new ArticleSummary(slug,
                                                  title,
                                                  description,
                                                  tagList,
                                                  createdAt,
                                                  updatedAt,
                                                  favorited,
                                                  favoritesCount,
                                                  author));
    }

    /// The invariant violations of this record, each a typed cause.
    public enum Invalid implements Cause {
        NEGATIVE_FAVORITES("favoritesCount is negative"),
        UPDATED_BEFORE_CREATED("updatedAt is before createdAt");
        private final String message;
        Invalid(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
