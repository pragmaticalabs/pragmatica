package conduit.view;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// A comment; `updatedAt` equals `createdAt` because comments cannot be edited (B-49).
public record CommentView(long id, String createdAt, String updatedAt, String body, ProfileView author) {
    /// Invariants: `updatedAt >= createdAt` (B-49: equal). The timestamps are wire strings of one fixed width, so string order is time order.
    public static Result<CommentView> commentView(long id,
                                                  String createdAt,
                                                  String updatedAt,
                                                  String body,
                                                  ProfileView author) {
        return Verify.ensure(updatedAt,
                             updated -> updated.compareTo(createdAt) >= 0,
                             Invalid.UPDATED_BEFORE_CREATED)
                     .map(_ -> new CommentView(id, createdAt, updatedAt, body, author));
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
