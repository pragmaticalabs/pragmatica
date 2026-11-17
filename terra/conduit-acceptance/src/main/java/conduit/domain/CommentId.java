package conduit.domain;

import java.util.regex.Pattern;

import org.pragmatica.lang.vo.ValueMapping;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.option;


/// A comment's identity as addressed in the path. A non-integer id names no comment: 404, not 422 (B-46).
public record CommentId(long value) {
    private static final Pattern DECIMAL = Pattern.compile("^[0-9]{1,18}$");

    public static Option<CommentId> inPath(String raw) {
        return option(raw).filter(value -> DECIMAL.matcher(value).matches())
                     .map(Long::parseLong)
                     .flatMap(id -> commentId(id).option());
    }

    /// The column representation (`ValueMapping`): `value()` out, `stored` back in.
    public static ValueMapping<CommentId, Long> valueMapping() {
        return ValueMapping.valueMapping(CommentId::value, CommentId::stored);
    }

    /// The minimal stable invariant: a positive id. A rule that tightens later must not make old rows unreadable.
    public static Result<CommentId> commentId(long id) {
        return Verify.ensure(id, Verify.Is::positive, Invalid.NOT_POSITIVE).map(CommentId::new);
    }

    /// Rebuilds the value from its column through the same minimal check (`null` fails it too).
    public static Result<CommentId> stored(Long raw) {
        return option(raw).toResult(Invalid.NOT_POSITIVE)
                     .flatMap(CommentId::commentId);
    }

    /// The value breaks the minimal invariant: for a stored value that is corrupt data, a 500, never a client error.
    public enum Invalid implements Cause {
        NOT_POSITIVE("CommentId is not positive");
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
