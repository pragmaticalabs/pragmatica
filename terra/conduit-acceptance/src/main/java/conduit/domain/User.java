package conduit.domain;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// An account as stored. The `@Query` result type of the user lookups: its components decode through the value
/// objects' `valueMapping()`, so a stored row arrives already typed.
public record User(UserId id,
                   Username username,
                   Email email,
                   PasswordHash passwordHash,
                   Option<String> bio,
                   Option<String> image) {
    /// Invariant: the email is lowercase (`users_email_lowercase` CHECK, B-12).
    ///
    /// Generated code (target/) still builds this through `::new`, bypassing the factory: a known generator bug (pragmatica #3018).
    public static Result<User> user(UserId id,
                                    Username username,
                                    Email email,
                                    PasswordHash passwordHash,
                                    Option<String> bio,
                                    Option<String> image) {
        return Verify.ensure(email, Email::isNormalized, Invalid.EMAIL_NOT_LOWERCASE).map(_ -> new User(id,
                                                                                                        username,
                                                                                                        email,
                                                                                                        passwordHash,
                                                                                                        bio,
                                                                                                        image));
    }

    /// The invariant violations of this record, each a typed cause.
    public enum Invalid implements Cause {
        EMAIL_NOT_LOWERCASE("email is not lowercase");
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
