package conduit.persistence;

import java.util.List;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import conduit.domain.Email;
import conduit.domain.PasswordHash;
import conduit.domain.User;
import conduit.domain.UserId;
import conduit.domain.Username;


/// The `users` table: accounts, credentials, and the B-19 conflict probe.
@PgSql
public interface UserPersistence {
    record TakenRow(Username username, Email email) {
        /// Invariant: the email is lowercase (`users_email_lowercase` CHECK, B-12).
        ///
        /// Generated code (target/) still builds this through `::new`, bypassing the factory: a known generator bug (pragmatica #3018).
        public static Result<TakenRow> takenRow(Username username, Email email) {
            return Verify.ensure(email, Email::isNormalized, Invalid.EMAIL_NOT_LOWERCASE).map(_ -> new TakenRow(username,
                                                                                                                email));
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

    @Query("SELECT id, username, email, password_hash, bio, image FROM users WHERE email = :email")
    Promise<Option<User>> findByEmail(Email email);

    @Query("SELECT id, username, email, password_hash, bio, image FROM users WHERE id = :id")
    Promise<Option<User>> findById(UserId id);

    /// B-19: the read before the insert, so every conflicting field is reported, not only the first constraint hit.
    @Query("SELECT username, email FROM users WHERE username = :username OR email = :email")
    Promise<List<TakenRow>> findTaken(Username username, Email email);

    /// B-19 on update: another user's username or email. Setting your own current value is not a conflict.
    @Query("SELECT username, email FROM users WHERE (username = :username OR email = :email) AND id <> :id")
    Promise<List<TakenRow>> findTakenByOthers(Username username, Email email, UserId id);

    @Query("""
        INSERT INTO users (username, email, password_hash)
        VALUES (:username, :email, :passwordHash)
        RETURNING id, username, email, password_hash, bio, image""")
    Promise<User> insert(Username username, Email email, PasswordHash passwordHash);

    /// Writes every column: the use case has already merged absent fields with the current row (B-17), so one
    /// statement shape serves every combination of changed fields.
    @Query("""
        UPDATE users
        SET username = :username, email = :email, password_hash = :passwordHash, bio = :bio, image = :image
        WHERE id = :id
        RETURNING id, username, email, password_hash, bio, image""")
    Promise<Option<User>> update(Username username,
                                 Email email,
                                 PasswordHash passwordHash,
                                 Option<String> bio,
                                 Option<String> image,
                                 UserId id);
}
