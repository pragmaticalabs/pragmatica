package conduit.domain;

/// What an operation needs to address an article it does not read: its id and who wrote it.
public record ArticleKey(ArticleId id, UserId authorId) {
    public static ArticleKey articleKey(ArticleId id, UserId authorId) {
        return new ArticleKey(id, authorId);
    }
}
