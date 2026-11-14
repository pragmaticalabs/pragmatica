package conduit.view;

/// `{"profile": {...}}`.
public record ProfileResponse(ProfileView profile) {
    public static ProfileResponse profileResponse(ProfileView profile) {
        return new ProfileResponse(profile);
    }
}
