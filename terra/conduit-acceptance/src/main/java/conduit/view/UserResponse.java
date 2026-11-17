package conduit.view;

/// `{"user": {...}}`.
public record UserResponse(UserView user) {
    public static UserResponse userResponse(UserView user) {
        return new UserResponse(user);
    }
}
