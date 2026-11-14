package conduit.domain;

import java.util.regex.Pattern;

import org.pragmatica.aether.slice.mapping.ValueMapping;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import conduit.domain.ConduitError.Unauthorized;

import static org.pragmatica.lang.Option.option;


/// The internal identity of a user; the JWT `sub` claim carries it as a decimal string (B-04).
public record UserId(long value) {
    private static final Pattern DECIMAL = Pattern.compile("^[0-9]{1,18}$");

    /// Parses a token subject. Anything but a positive decimal integer is a bad token (B-03).
    public static Result<UserId> userId(String subject) {
        return option(subject).toResult(Unauthorized.TOKEN_INVALID)
                     .flatMap(value -> Verify.ensure(value, Verify.Is::matches, DECIMAL, Unauthorized.TOKEN_INVALID))
                     .map(Long::parseLong)
                     .flatMap(id -> Verify.ensure(id, Verify.Is::positive, Unauthorized.TOKEN_INVALID))
                     .map(UserId::new);
    }

    /// The column representation: a bigint.
    public static ValueMapping<UserId, Long> valueMapping() {
        return ValueMapping.of(UserId::value, UserId::stored);
    }

    /// The minimal stable invariant: a positive id. A rule that tightens later must not make old rows unreadable.
    public static Result<UserId> userId(long id) {
        return Verify.ensure(id, Verify.Is::positive, Invalid.NOT_POSITIVE).map(UserId::new);
    }

    /// Rebuilds the value from its column through the same minimal check (`null` fails it too).
    public static Result<UserId> stored(Long id) {
        return option(id).toResult(Invalid.NOT_POSITIVE)
                     .flatMap(UserId::userId);
    }

    /// The value breaks the minimal invariant: for a stored value that is corrupt data, a 500, never a client error.
    public enum Invalid implements Cause {
        NOT_POSITIVE("UserId is not positive");
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
