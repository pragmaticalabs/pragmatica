package conduit.view;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// `{"articles": [...], "articlesCount": n}`; the count is the exact total for the filters, ignoring the page
/// (B-37).
public record ArticlesResponse(List<ArticleSummary> articles, long articlesCount) {
    /// Invariant: `articlesCount >= 0`, an exact total (B-37).
    public static Result<ArticlesResponse> articlesResponse(List<ArticleSummary> articles, long articlesCount) {
        return Verify.ensure(articlesCount, count -> count >= 0, Invalid.NEGATIVE_COUNT).map(_ -> new ArticlesResponse(List.copyOf(articles),
                                                                                                                       articlesCount));
    }

    /// The invariant violations of this record, each a typed cause.
    public enum Invalid implements Cause {
        NEGATIVE_COUNT("articlesCount is negative");
        private final String message;
        Invalid(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
