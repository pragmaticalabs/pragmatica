package conduit.view;

/// `{"article": {...}}`.
public record ArticleResponse(ArticleView article) {
    public static ArticleResponse articleResponse(ArticleView article) {
        return new ArticleResponse(article);
    }
}
