package conduit.usecase.getcurrentuser;

import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import conduit.domain.Caller;
import conduit.domain.Caller.Authenticated;
import conduit.domain.ConduitError.Unauthorized;
import conduit.domain.User;
import conduit.domain.UserId;
import conduit.persistence.UserPersistenceFactory;
import conduit.shared.Views;
import conduit.view.UserResponse;


/// `GET /user`: the caller's account, with the token that was presented, byte for byte (B-07). A verified token whose
/// subject matches no user is `token: is invalid` (B-06).
@Slice
public interface GetCurrentUser {
    Promise<UserResponse> execute(SecurityContext security, RequestHeaders headers);

    @FunctionalInterface
    interface LoadUser {
        Promise<Option<User>> load(UserId id);

        static LoadUser loadUser(@PgSql PgSqlConnector db) {
            var users = UserPersistenceFactory.userPersistence(db);

            return users::findById;
        }
    }

    static GetCurrentUser getCurrentUser(LoadUser loadUser) {
        return (security, headers) -> Caller.required(security, headers)
                                            .async()
                                            .flatMap(caller -> respond(loadUser, caller));
    }

    private static Promise<UserResponse> respond(LoadUser loadUser, Authenticated caller) {
        return loadUser.load(caller.id())
                       .flatMap(found -> found.async(Unauthorized.TOKEN_INVALID))
                       .map(user -> UserResponse.userResponse(Views.user(user,
                                                                         caller.token())));
    }
}
