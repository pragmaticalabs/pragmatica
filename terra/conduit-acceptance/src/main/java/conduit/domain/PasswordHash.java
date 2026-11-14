package conduit.domain;

import org.pragmatica.aether.slice.mapping.ValueMapping;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.option;


/// A stored bcrypt hash (B-10). Producing and checking one is the adapter's work (conduit.adapter.password).
public record PasswordHash(String value) {
    /// The minimal stable invariant: non-blank. The hash format is the adapter's business, so it is not checked here.
    public static Result<PasswordHash> passwordHash(String value) {
        return option(value).filter(Verify.Is::notBlank)
                     .toResult(Invalid.BLANK)
                     .map(PasswordHash::new);
    }

    @Override
    public String toString() {
        return "PasswordHash[***]";
    }

    /// The column representation (`ValueMapping`): `value()` out, `stored` back in.
    public static ValueMapping<PasswordHash, String> valueMapping() {
        return ValueMapping.of(PasswordHash::value, PasswordHash::stored);
    }

    /// Rebuilds the value from its column through the same minimal check.
    public static Result<PasswordHash> stored(String raw) {
        return passwordHash(raw);
    }

    /// The value breaks the minimal invariant: for a stored value that is corrupt data, a 500, never a client error.
    public enum Invalid implements Cause {
        BLANK("PasswordHash is blank");
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
