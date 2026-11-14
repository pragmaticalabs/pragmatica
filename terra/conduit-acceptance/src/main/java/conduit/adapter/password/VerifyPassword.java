package conduit.adapter.password;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;

import conduit.domain.ConduitError.Unauthorized;
import conduit.domain.Password;
import conduit.domain.PasswordHash;


/// Checks a password against a stored bcrypt hash; accepts `$2a$` and `$2b$` (B-10).
///
/// Checking costs tens of milliseconds of CPU, so it runs through `Promise.lift`, off the thread that delivered the
/// request. A step, so use-case tests can run without paying cost 10.
@FunctionalInterface
public interface VerifyPassword {
    /// Succeeds only when the password matches; a mismatch, and a hash bcrypt cannot read, are the B-09 401
    /// (design-out: an unreadable hash is a mismatch).
    Promise<Unit> verify(PasswordHash hash, Password password);

    static VerifyPassword verifyPassword() {
        return (hash, password) -> Promise.lift(Unauthorized.CREDENTIALS_INVALID,
                                                () -> OpenBSDBCrypt.checkPassword(hash.value(),
                                                                                  password.value().toCharArray()))
                                          .filter(Unauthorized.CREDENTIALS_INVALID, matches -> matches)
                                          .mapToUnit();
    }
}
