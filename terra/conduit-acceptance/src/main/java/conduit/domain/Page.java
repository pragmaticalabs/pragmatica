package conduit.domain;

import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import conduit.domain.ConduitError.RequestInvalid;

import static org.pragmatica.lang.Result.success;


/// One page of an article list (B-35): `limit` 1..100 (default 20), `offset` >= 0 (default 0), both decimal
/// integers. Anything else is rejected, not clamped: `limit: is invalid` / `offset: is invalid`, both reported when
/// both are wrong (B-20). The query values arrive as text so that `abc` is this 422 and not a binder's 400.
public record Page(int limit, int offset) {
    private static final Pattern DECIMAL = Pattern.compile("^[0-9]{1,9}$");
    private static final int DEFAULT_LIMIT = 20;
    private static final int DEFAULT_OFFSET = 0;
    private static final int MAX_LIMIT = 100;
    private static final RequestInvalid LIMIT_INVALID = RequestInvalid.requestInvalid("limit", "is invalid");
    private static final RequestInvalid OFFSET_INVALID = RequestInvalid.requestInvalid("offset", "is invalid");

    /// From the query text: each value is parsed and range-checked on its own, so both errors are reported when both
    /// are wrong. The range rules are the ones `page(int, int)` applies.
    public static Result<Page> page(Option<String> limit, Option<String> offset) {
        return Result.all(parsed(limit, DEFAULT_LIMIT, LIMIT_INVALID).flatMap(Page::limitInRange),
                          parsed(offset, DEFAULT_OFFSET, OFFSET_INVALID).flatMap(Page::offsetInRange))
                     .map(Page::new)
                     .mapError(RequestInvalid::merged);
    }

    /// Invariants (B-35): `limit` 1..100, `offset` >= 0; every violated one is reported.
    public static Result<Page> page(int limit, int offset) {
        return Result.all(limitInRange(limit),
                          offsetInRange(offset))
                     .map(Page::new)
                     .mapError(RequestInvalid::merged);
    }

    private static Result<Integer> limitInRange(int limit) {
        return Verify.ensure(limit, Verify.Is::between, 1, MAX_LIMIT, LIMIT_INVALID);
    }

    private static Result<Integer> offsetInRange(int offset) {
        return Verify.ensure(offset, Verify.Is::nonNegative, OFFSET_INVALID);
    }

    private static Result<Integer> parsed(Option<String> raw, int fallback, RequestInvalid invalid) {
        return raw.map(value -> Verify.ensure(value, Verify.Is::matches, DECIMAL, invalid).map(Integer::parseInt))
                  .or(success(fallback));
    }
}
