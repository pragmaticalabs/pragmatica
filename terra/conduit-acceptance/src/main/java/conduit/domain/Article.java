package conduit.domain;

import java.time.Instant;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// One article with its body, its author's profile columns and the viewer's counts and flags.
public record Article(ArticleId id,
                      Slug slug,
                      Title title,
                      Description description,
                      ArticleBody body,
                      Instant createdAt,
                      Instant updatedAt,
                      Username username,
                      Option<String> bio,
                      Option<String> image,
                      long favoritesCount,
                      boolean favorited,
                      boolean following) {
    /// Invariants: `favoritesCount >= 0` (a count of favorites rows, B-47) and `updatedAt >= createdAt` (B-29 sets it to now).
    ///
    /// Generated code (target/) still builds this through `::new`, bypassing the factory: a known generator bug (pragmatica #3018).
    public static Result<Article> article(ArticleId id,
                                          Slug slug,
                                          Title title,
                                          Description description,
                                          ArticleBody body,
                                          Instant createdAt,
                                          Instant updatedAt,
                                          Username username,
                                          Option<String> bio,
                                          Option<String> image,
                                          long favoritesCount,
                                          boolean favorited,
                                          boolean following) {
        return Verify.ensure(favoritesCount, Verify.Is::nonNegative, Invalid.NEGATIVE_FAVORITES)
                     .flatMap(_ -> Verify.ensure(updatedAt,
                                                 updated -> !updated.isBefore(createdAt),
                                                 Invalid.UPDATED_BEFORE_CREATED))
                     .map(_ -> new Article(id,
                                           slug,
                                           title,
                                           description,
                                           body,
                                           createdAt,
                                           updatedAt,
                                           username,
                                           bio,
                                           image,
                                           favoritesCount,
                                           favorited,
                                           following));
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
