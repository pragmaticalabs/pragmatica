package conduit.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import org.pragmatica.aether.slice.mapping.ValueMapping;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import conduit.domain.ConduitError.RequestInvalid;

import static org.pragmatica.lang.Option.option;


/// An email address, lowercased with `Locale.ROOT` (B-12) and checked only for one `@` with a non-space run on
/// each side (B-13).
///
/// Pragmatica's `org.pragmatica.lang.vo.Email` is not used because the Conduit rules differ from it on three points:
/// it trims the input, where B-16 forbids trimming any value; it keeps the local part's case, where B-12 lowercases the
/// whole address; and it rejects addresses that B-13 accepts (it applies the full RFC 5321 grammar, B-13 only the
/// one-`@`, no-whitespace shape).
public record Email(String value) {
    private static final Pattern FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

    private static final RequestInvalid BLANK = RequestInvalid.blank("email");
    private static final RequestInvalid INVALID = RequestInvalid.requestInvalid("email", "is invalid");

    /// Register and update: blank, then format.
    public static Result<Email> email(Option<String> raw) {
        return blankChecked(raw).flatMap(value -> Verify.ensure(value, Verify.Is::matches, FORMAT, INVALID))
                           .map(Email::normalized);
    }

    /// Login: blank only. A malformed address cannot match a stored one, so it ends in the same 401 as an unknown
    /// email (B-09) instead of revealing which check failed.
    public static Result<Email> loginEmail(Option<String> raw) {
        return blankChecked(raw).map(Email::normalized);
    }

    private static Result<String> blankChecked(Option<String> raw) {
        return raw.filter(Verify.Is::present)
                  .toResult(BLANK);
    }

    /// Whether the address is in the stored form: lowercase (`users_email_lowercase` CHECK, B-12).
    public static boolean isNormalized(Email email) {
        return email.value()
                    .equals(email.value().toLowerCase(Locale.ROOT));
    }

    private static Email normalized(String value) {
        return new Email(value.toLowerCase(Locale.ROOT));
    }

    /// The column representation (`ValueMapping`): `value()` out, `stored` back in.
    public static ValueMapping<Email, String> valueMapping() {
        return ValueMapping.of(Email::value, Email::stored);
    }

    /// Rebuilds the value from its column. Checks only the stable minimum, non-blank: a registration rule that
    /// tightens later must not make old rows unreadable.
    public static Result<Email> stored(String raw) {
        return option(raw).filter(Verify.Is::notBlank)
                     .toResult(Invalid.BLANK)
                     .map(Email::new);
    }

    /// The value breaks the minimal invariant: for a stored value that is corrupt data, a 500, never a client error.
    public enum Invalid implements Cause {
        BLANK("Email is blank");
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
