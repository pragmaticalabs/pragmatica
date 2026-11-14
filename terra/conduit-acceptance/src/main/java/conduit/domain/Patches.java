package conduit.domain;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Patch;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Reads of Pragmatica's tri-state `Patch` for partial updates (B-17, B-26): absent leaves the field, `null` and a
/// value are both handed to the parser, which decides whether `null` is allowed.
public sealed interface Patches {
    /// Validates a present field: absent stays absent (nothing to change).
    static <T, U> Result<Option<U>> parsePresent(Patch<T> patch, Fn1<Result<U>, Option<T>> parser) {
        return patch.fold(() -> success(none()), () -> parsed(parser, none()), value -> parsed(parser, some(value)));
    }

    /// Maps a present field with a step that cannot fail: absent stays absent, `null` and a value are both mapped.
    static <T, U> Option<U> mapPresent(Patch<T> patch, Fn1<U, Option<T>> mapper) {
        return patch.fold(Option::<U> none, () -> some(mapper.apply(none())), value -> some(mapper.apply(some(value))));
    }

    static boolean isAbsent(Patch<?> patch) {
        return patch instanceof Patch.Absent<?>;
    }

    private static <T, U> Result<Option<U>> parsed(Fn1<Result<U>, Option<T>> parser, Option<T> raw) {
        return parser.apply(raw)
                     .map(Option::some);
    }

    record unused() implements Patches {}
}
