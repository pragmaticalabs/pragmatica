package conduit.view;

import java.util.List;


/// `{"comments": [...]}`, oldest first, all of them (B-41).
public record CommentsResponse(List<CommentView> comments) {
    public static CommentsResponse commentsResponse(List<CommentView> comments) {
        return new CommentsResponse(List.copyOf(comments));
    }
}
