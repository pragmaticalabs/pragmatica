package conduit.domain;

import java.time.Instant;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// A comment with its author's profile columns, as a viewer sees it.
public record Comment(CommentId id,
                      CommentBody body,
                      Instant createdAt,
                      Instant updatedAt,
                      Username username,
                      Option<String> bio,
                      Option<String> image,
                      boolean following) {
    /// Invariant: `updatedAt >= createdAt` (B-49: a comment cannot be edited, so they are equal).
    ///
    /// Generated code (target/) still builds this through `::new`, bypassing the factory: a known generator bug (pragmatica #3018).
    public static Result<Comment> comment(CommentId id,
                                          CommentBody body,
                                          Instant createdAt,
                                          Instant updatedAt,
                                          Username username,
                                          Option<String> bio,
                                          Option<String> image,
                                          boolean following) {
        return Verify.ensure(updatedAt,
                             updated -> !updated.isBefore(createdAt),
                             Invalid.UPDATED_BEFORE_CREATED)
                     .map(_ -> new Comment(id, body, createdAt, updatedAt, username, bio, image, following));
    }

    /// The invariant violations of this record, each a typed cause.
    public enum Invalid implements Cause {
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
