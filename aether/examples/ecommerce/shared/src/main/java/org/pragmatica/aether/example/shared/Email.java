package org.pragmatica.aether.example.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.regex.Pattern;

/**
 * Validated email address.
 */
public record Email(String value) {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    private static final Fn1<Cause, String> INVALID_EMAIL = Causes.forOneValue("Invalid email: %s");

    public static Result<Email> email(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank, INVALID_EMAIL)
                     .map(String::trim)
                     .map(String::toLowerCase)
                     .filter(INVALID_EMAIL,
                             EMAIL_PATTERN.asPredicate())
                     .map(Email::new);
    }

    public String domain() {
        return value.substring(value.indexOf('@') + 1);
    }
}
