package conduit.view;

import org.pragmatica.lang.Option;


/// A profile as another user sees it; `following` is always present, false for anonymous callers (B-47).
public record ProfileView(String username, Option<String> bio, Option<String> image, boolean following) {
    public static ProfileView profileView(String username,
                                          Option<String> bio,
                                          Option<String> image,
                                          boolean following) {
        return new ProfileView(username, bio, image, following);
    }
}
