package conduit.view;

/// `{"comment": {...}}`.
public record CommentResponse(CommentView comment) {
    public static CommentResponse commentResponse(CommentView comment) {
        return new CommentResponse(comment);
    }
}
