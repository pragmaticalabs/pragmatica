package conduit.domain;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import conduit.domain.ConduitError.RequestInvalid;


/// A plaintext password, held only until it is hashed or checked.
///
/// The B-11 policy applies on register and update: at least 8 code points, at most 72 UTF-8 bytes (bcrypt ignores
/// the rest, so longer input is refused rather than silently truncated). Login only requires a value.
public record Password(String value) {
    private static final int MIN_CODE_POINTS = 8;
    private static final int MAX_UTF8_BYTES = 72;
    private static final RequestInvalid BLANK = RequestInvalid.blank("password");

    private static final RequestInvalid TOO_SHORT = RequestInvalid.requestInvalid("password",
                                                                                  "is too short (minimum is 8 characters)");

    private static final RequestInvalid TOO_LONG = RequestInvalid.requestInvalid("password",
                                                                                 "is too long (maximum is 72 bytes)");

    public static Result<Password> password(Option<String> raw) {
        return blankChecked(raw).flatMap(value -> Verify.ensure(value, Password::longEnough, TOO_SHORT))
                           .flatMap(value -> Verify.ensure(value, Password::shortEnough, TOO_LONG))
                           .map(Password::new);
    }

    public static Result<Password> loginPassword(Option<String> raw) {
        return blankChecked(raw).map(Password::new);
    }

    private static Result<String> blankChecked(Option<String> raw) {
        return raw.filter(Verify.Is::present)
                  .toResult(BLANK);
    }

    private static boolean longEnough(String value) {
        return value.codePointCount(0, value.length()) >= MIN_CODE_POINTS;
    }

    private static boolean shortEnough(String value) {
        return value.codePoints()
                    .map(Password::utf8Length)
                    .sum() <= MAX_UTF8_BYTES;
    }

    /// UTF-8 encoded length of one code point (RFC 3629): 1 byte below U+0080, 2 below U+0800, 3 below U+10000,
    /// else 4.
    private static int utf8Length(int codePoint) {
        return 1 + (codePoint >= 0x80
                    ? 1
                    : 0) + (codePoint >= 0x800
                            ? 1
                            : 0) + (codePoint >= 0x10000
                                    ? 1
                                    : 0);
    }

    @Override
    public String toString() {
        return "Password[***]";
    }
}
