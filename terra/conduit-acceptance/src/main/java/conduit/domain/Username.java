package conduit.domain;

import org.pragmatica.aether.slice.mapping.ValueMapping;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import conduit.domain.ConduitError.RequestInvalid;

import static org.pragmatica.lang.Option.option;


/// A username: case-sensitive, byte-compared, stored as sent, no restriction beyond non-blank (B-14, B-16).
public record Username(String value) {
    private static final RequestInvalid BLANK = RequestInvalid.blank("username");

    /// A username taken from the path: empty when there is no text or only whitespace (the stored minimum, non-blank).
    /// Unlike the body field, a path value is never a 422: whether it names a profile is the lookup's answer (404).
    public static Option<Username> inPath(String raw) {
        return stored(raw).option();
    }

    /// `null`, absent, empty and whitespace-only are all `can't be blank` (B-15); the value is never trimmed (B-16).
    public static Result<Username> username(Option<String> raw) {
        return raw.filter(Verify.Is::present)
                  .toResult(BLANK)
                  .map(Username::new);
    }

    /// The column representation (`ValueMapping`): `value()` out, `stored` back in.
    public static ValueMapping<Username, String> valueMapping() {
        return ValueMapping.of(Username::value, Username::stored);
    }

    /// Rebuilds the value from its column. Checks only the stable minimum, non-blank: a registration rule that
    /// tightens later must not make old rows unreadable.
    public static Result<Username> stored(String raw) {
        return option(raw).filter(Verify.Is::notBlank)
                     .toResult(Invalid.BLANK)
                     .map(Username::new);
    }

    /// The value breaks the minimal invariant: for a stored value that is corrupt data, a 500, never a client error.
    public enum Invalid implements Cause {
        BLANK("Username is blank");
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
