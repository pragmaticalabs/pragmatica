package conduit.view;

import org.pragmatica.lang.Option;


/// The `user` object of `{"user": {...}}`: the caller's own account plus a token (B-07).
public record UserView(String email, String token, String username, Option<String> bio, Option<String> image) {
    public static UserView userView(String email,
                                    String token,
                                    String username,
                                    Option<String> bio,
                                    Option<String> image) {
        return new UserView(email, token, username, bio, image);
    }
}
