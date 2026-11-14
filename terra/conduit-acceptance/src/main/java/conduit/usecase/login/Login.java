package conduit.usecase.login;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import conduit.adapter.password.VerifyPassword;
import conduit.adapter.token.TokenIssuer;
import conduit.domain.Clock;
import conduit.domain.ConduitError.RequestInvalid;
import conduit.domain.ConduitError.Unauthorized;
import conduit.domain.Email;
import conduit.domain.Password;
import conduit.domain.User;
import conduit.persistence.UserPersistenceFactory;
import conduit.shared.Sessions;
import conduit.view.UserResponse;

import static org.pragmatica.lang.Option.option;


/// `POST /users/login`: check credentials and mint a token (B-07).
///
/// A wrong password and an unknown email are the same 401 `credentials: invalid`; no dummy bcrypt runs for an
/// unknown email (B-09). A malformed email is a 422 `email: is invalid` before any lookup; the email is lowercased before the lookup (B-12).
@Slice
public interface Login {
    record Request(Credentials user) {
        public static Request request(Credentials user) {
            return new Request(user);
        }

        public record Credentials(Option<String> email, Option<String> password) {
            public static Credentials credentials(Option<String> email, Option<String> password) {
                return new Credentials(email, password);
            }

            public static Credentials credentials(String email, String password) {
                return credentials(option(email), option(password));
            }
        }
    }

    record ValidRequest(Email email, Password password) {
        static Result<ValidRequest> validRequest(Request request) {
            return option(request.user()).toResult(RequestInvalid.blanks("email", "password"))
                         .flatMap(ValidRequest::parsed);
        }

        private static Result<ValidRequest> parsed(Request.Credentials user) {
            return Result.all(Email.email(user.email()),
                              Password.loginPassword(user.password()))
                         .map(ValidRequest::new)
                         .mapError(RequestInvalid::merged);
        }
    }

    Promise<UserResponse> execute(Request request);

    @FunctionalInterface
    interface LoadAccount {
        Promise<Option<User>> load(Email email);

        static LoadAccount loadAccount(@PgSql PgSqlConnector db) {
            var users = UserPersistenceFactory.userPersistence(db);

            return users::findByEmail;
        }
    }

    static Login login(LoadAccount loadAccount, VerifyPassword verifyPassword, TokenIssuer tokenIssuer, Clock clock) {
        return request -> ValidRequest.validRequest(request)
                                      .async()
                                      .flatMap(valid -> authenticate(loadAccount, verifyPassword, valid))
                                      .flatMap(user -> Sessions.respond(tokenIssuer, clock, user));
    }

    private static Promise<User> authenticate(LoadAccount loadAccount,
                                              VerifyPassword verifyPassword,
                                              ValidRequest valid) {
        return loadAccount.load(valid.email())
                          .flatMap(found -> found.async(Unauthorized.CREDENTIALS_INVALID))
                          .flatMap(user -> verifyPassword.verify(user.passwordHash(),
                                                                 valid.password())
                                                         .map(_ -> user));
    }
}
