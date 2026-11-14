package conduit.domain;

import org.pragmatica.lang.Option;


/// A user as a viewer sees them. `following` is computed per viewer by the query, so this is a projection rather
/// than the stored `User`.
public record Profile(UserId id, Username username, Option<String> bio, Option<String> image, boolean following) {
    public static Profile profile(UserId id,
                                  Username username,
                                  Option<String> bio,
                                  Option<String> image,
                                  boolean following) {
        return new Profile(id, username, bio, image, following);
    }
}
