package conduit.usecase.unfollowuser;

import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

import conduit.domain.Caller.Authenticated;
import conduit.domain.Caller;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.Profile;
import conduit.domain.Username;
import conduit.persistence.ProfilePersistence;
import conduit.persistence.ProfilePersistenceFactory;
import conduit.persistence.Transaction;
import conduit.shared.Profiles;
import conduit.shared.Views;
import conduit.view.ProfileResponse;


/// `DELETE /profiles/{username}/follow`: required auth. Unfollowing someone not followed is 200 `following: false`
/// (B-42). Yourself is 422 `profile: cannot follow yourself` (B-44). The lookup and the write share one transaction.
@Slice
public interface UnfollowUser {
    record Request(String username) {
        public static Request request(String username) {
            return new Request(username);
        }
    }

    record Target(Authenticated caller, Username username) {
        public static Target target(Authenticated caller, Username username) {
            return new Target(caller, username);
        }
    }

    Promise<ProfileResponse> execute(Request request, SecurityContext security, RequestHeaders headers);

    @FunctionalInterface
    interface Store {
        Promise<Profile> unfollow(Target target);

        static Store store(@PgSql PgSqlConnector db) {
            return target -> Transaction.inTransaction(db,
                                                       tx -> unfollow(ProfilePersistenceFactory.profilePersistence(tx),
                                                                      target));
        }

        private static Promise<Profile> unfollow(ProfilePersistence profiles, Target target) {
            return Profiles.otherUser(profiles,
                                      target.caller(),
                                      target.username())
                           .flatMap(other -> profiles.unfollow(target.caller().id(),
                                                               other.id())
                                                     .map(_ -> Profiles.withFollowing(other, false)));
        }
    }

    static UnfollowUser unfollowUser(Store store) {
        return (request, security, headers) -> Caller.required(security, headers)
                                                     .flatMap(caller -> Username.inPath(request.username())
                                                                                .toResult(NotFound.PROFILE)
                                                                                .map(username -> Target.target(caller,
                                                                                                               username)))
                                                     .async()
                                                     .flatMap(store::unfollow)
                                                     .map(profile -> ProfileResponse.profileResponse(Views.profile(profile)));
    }
}
