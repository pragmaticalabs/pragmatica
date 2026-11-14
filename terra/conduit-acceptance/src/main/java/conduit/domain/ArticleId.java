package conduit.domain;

import org.pragmatica.lang.vo.ValueMapping;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.option;


/// An article's identity: the key its comments, favorites and tag links refer to. It never appears on the wire.
public record ArticleId(long value) {
    /// The column representation (`ValueMapping`): `value()` out, `stored` back in.
    public static ValueMapping<ArticleId, Long> valueMapping() {
        return ValueMapping.valueMapping(ArticleId::value, ArticleId::stored);
    }

    /// The minimal stable invariant: a positive id. A rule that tightens later must not make old rows unreadable.
    public static Result<ArticleId> articleId(long id) {
        return Verify.ensure(id, Verify.Is::positive, Invalid.NOT_POSITIVE).map(ArticleId::new);
    }

    /// Rebuilds the value from its column through the same minimal check (`null` fails it too).
    public static Result<ArticleId> stored(Long raw) {
        return option(raw).toResult(Invalid.NOT_POSITIVE)
                     .flatMap(ArticleId::articleId);
    }

    /// The value breaks the minimal invariant: for a stored value that is corrupt data, a 500, never a client error.
    public enum Invalid implements Cause {
        NOT_POSITIVE("ArticleId is not positive");
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
