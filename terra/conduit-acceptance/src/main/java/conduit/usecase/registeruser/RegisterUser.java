package conduit.usecase.registeruser;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import conduit.adapter.password.HashPassword;
import conduit.adapter.token.TokenIssuer;
import conduit.domain.Clock;
import conduit.domain.ConduitError.RequestInvalid;
import conduit.domain.Email;
import conduit.domain.Password;
import conduit.domain.PasswordHash;
import conduit.domain.User;
import conduit.domain.Username;
import conduit.persistence.Transaction;
import conduit.persistence.UserPersistence;
import conduit.persistence.UserPersistenceFactory;
import conduit.shared.Conflicts;
import conduit.shared.Sessions;
import conduit.view.UserResponse;

import static org.pragmatica.lang.Option.option;


/// `POST /users`: create an account and mint its first token (B-07).
///
/// Order: validation (422, every field at once, B-20) -> bcrypt (off the I/O thread, no connection held) -> ONE
/// transaction: conflict read (409, every taken field, B-19), then the INSERT. A unique violation from a
/// concurrent register is the same 409.
@Slice
public interface RegisterUser {
    record Request(NewUser user) {
        public static Request request(NewUser user) {
            return new Request(user);
        }

        public record NewUser(Option<String> username, Option<String> email, Option<String> password) {
            public static NewUser newUser(Option<String> username, Option<String> email, Option<String> password) {
                return new NewUser(username, email, password);
            }

            public static NewUser newUser(String username, String email, String password) {
                return newUser(option(username), option(email), option(password));
            }
        }
    }

    record ValidRequest(Username username, Email email, Password password) {
        static Result<ValidRequest> validRequest(Request request) {
            return option(request.user()).toResult(RequestInvalid.blanks("username", "email", "password"))
                         .flatMap(ValidRequest::parsed);
        }

        private static Result<ValidRequest> parsed(Request.NewUser user) {
            return Result.all(Username.username(user.username()),
                              Email.email(user.email()),
                              Password.password(user.password()))
                         .map(ValidRequest::new)
                         .mapError(RequestInvalid::merged);
        }
    }

    Promise<UserResponse> execute(Request request);

    /// B-19 and the insert in ONE transaction: the taken-fields read, the 409 verdict, then the INSERT.
    @FunctionalInterface
    interface Store {
        Promise<User> register(Username username, Email email, PasswordHash hash);

        static Store store(@PgSql PgSqlConnector db) {
            return (username, email, hash) -> Transaction.inTransaction(db,
                                                                        tx -> register(UserPersistenceFactory.userPersistence(tx),
                                                                                       username,
                                                                                       email,
                                                                                       hash));
        }

        private static Promise<User> register(UserPersistence users,
                                              Username username,
                                              Email email,
                                              PasswordHash hash) {
            return users.findTaken(username, email)
                        .flatMap(taken -> Conflicts.verdict(taken, username, email).async())
                        .flatMap(_ -> users.insert(username, email, hash)
                                           .mapError(Conflicts::fromUniqueViolation));
        }
    }

    static RegisterUser registerUser(HashPassword hashPassword, Store store, TokenIssuer tokenIssuer, Clock clock) {
        return request -> ValidRequest.validRequest(request)
                                      .async()
                                      .flatMap(valid -> create(hashPassword, store, valid))
                                      .flatMap(user -> Sessions.respond(tokenIssuer, clock, user));
    }

    private static Promise<User> create(HashPassword hashPassword, Store store, ValidRequest valid) {
        return hashPassword.hash(valid.password())
                           .flatMap(hash -> store.register(valid.username(),
                                                           valid.email(),
                                                           hash));
    }
}
