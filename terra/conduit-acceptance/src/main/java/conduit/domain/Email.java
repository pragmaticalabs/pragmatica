package conduit.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import org.pragmatica.lang.vo.ValueMapping;
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

    /// Register, update and login: blank, then format. Login validates before the credential lookup (RealWorld spec, upstream b8a37f107c):
    /// a malformed address is a 422 `email: is invalid`, not the 401 an unknown address gets (B-09).
    public static Result<Email> email(Option<String> raw) {
        return blankChecked(raw).flatMap(value -> Verify.ensure(value, Verify.Is::matches, FORMAT, INVALID))
                           .map(Email::normalized);
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
        return ValueMapping.valueMapping(Email::value, Email::stored);
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
