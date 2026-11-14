package conduit.domain;

import org.pragmatica.aether.slice.mapping.ValueMapping;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import conduit.domain.ConduitError.RequestInvalid;

import static org.pragmatica.lang.Option.option;


/// An article title; required, stored as sent (B-15, B-16, B-55).
public record Title(String value) {
    private static final RequestInvalid BLANK = RequestInvalid.blank("title");

    /// `null`, absent, empty and whitespace-only are all `can't be blank` (B-15); the value is never trimmed (B-16).
    public static Result<Title> title(Option<String> raw) {
        return raw.filter(Verify.Is::present)
                  .toResult(BLANK)
                  .map(Title::new);
    }

    /// The column representation (`ValueMapping`): `value()` out, `stored` back in.
    public static ValueMapping<Title, String> valueMapping() {
        return ValueMapping.of(Title::value, Title::stored);
    }

    /// Rebuilds the value from its column. Checks only the stable minimum, non-blank: a registration rule that
    /// tightens later must not make old rows unreadable.
    public static Result<Title> stored(String raw) {
        return option(raw).filter(Verify.Is::notBlank)
                     .toResult(Invalid.BLANK)
                     .map(Title::new);
    }

    /// The value breaks the minimal invariant: for a stored value that is corrupt data, a 500, never a client error.
    public enum Invalid implements Cause {
        BLANK("Title is blank");
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
