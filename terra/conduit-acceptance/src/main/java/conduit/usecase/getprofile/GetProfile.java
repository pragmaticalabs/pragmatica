package conduit.usecase.getprofile;

import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import conduit.domain.Caller;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.Profile;
import conduit.domain.UserId;
import conduit.domain.Username;
import conduit.persistence.ProfilePersistenceFactory;
import conduit.shared.Views;
import conduit.view.ProfileResponse;


/// `GET /profiles/{username}`: optional auth; `following` is false for anonymous callers (B-47). Usernames are
/// matched byte for byte (B-14).
@Slice
public interface GetProfile {
    record Request(String username) {
        public static Request request(String username) {
            return new Request(username);
        }
    }

    record Target(Option<UserId> viewer, Username username) {
        public static Target target(Option<UserId> viewer, Username username) {
            return new Target(viewer, username);
        }
    }

    Promise<ProfileResponse> execute(Request request, SecurityContext security);

    @FunctionalInterface
    interface LoadProfile {
        Promise<Option<Profile>> load(Target target);

        static LoadProfile loadProfile(@PgSql PgSqlConnector db) {
            var profiles = ProfilePersistenceFactory.profilePersistence(db);

            return target -> profiles.findProfile(target.viewer(), target.username());
        }
    }

    static GetProfile getProfile(LoadProfile loadProfile) {
        return (request, security) -> Caller.viewer(security)
                                            .flatMap(viewer -> Username.inPath(request.username())
                                                                       .toResult(NotFound.PROFILE)
                                                                       .map(username -> Target.target(viewer, username)))
                                            .async()
                                            .flatMap(loadProfile::load)
                                            .flatMap(found -> found.async(NotFound.PROFILE))
                                            .map(profile -> ProfileResponse.profileResponse(Views.profile(profile)));
    }
}
