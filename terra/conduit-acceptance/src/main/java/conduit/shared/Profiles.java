package conduit.shared;

import org.pragmatica.lang.Promise;

import conduit.domain.Caller.Authenticated;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.ConduitError.RequestInvalid;
import conduit.domain.Profile;
import conduit.domain.Username;
import conduit.persistence.ProfilePersistence;


/// The target lookup shared by follow and unfollow: 404 when nobody has the username, then 422 when it is the
/// caller (B-44; the target must exist before it can be the caller, so this order agrees with B-51).
public sealed interface Profiles {
    RequestInvalid SELF_FOLLOW = RequestInvalid.requestInvalid("profile", "cannot follow yourself");

    static Promise<Profile> otherUser(ProfilePersistence profiles, Authenticated caller, Username username) {
        return profiles.findProfile(caller.viewer(),
                                    username)
                       .flatMap(found -> found.async(NotFound.PROFILE))
                       .filter(SELF_FOLLOW,
                               row -> !row.id()
                                          .equals(caller.id()));
    }

    static Profile withFollowing(Profile row, boolean following) {
        return Profile.profile(row.id(), row.username(), row.bio(), row.image(), following);
    }

    record unused() implements Profiles {}
}
