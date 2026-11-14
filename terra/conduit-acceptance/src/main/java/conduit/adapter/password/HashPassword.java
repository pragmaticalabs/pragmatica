package conduit.adapter.password;

import java.security.SecureRandom;

import org.pragmatica.lang.Promise;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;

import conduit.domain.Password;
import conduit.domain.PasswordHash;


/// bcrypt, cost 10, `$2a$` (B-10), via BouncyCastle's `OpenBSDBCrypt`: Pragmatica ships no password hasher.
///
/// Hashing costs tens of milliseconds of CPU, so it runs through `Promise.lift`, off the thread that delivered the
/// request, never inline on an I/O thread. A step, so use-case tests can run without paying cost 10.
@FunctionalInterface
public interface HashPassword {
    int COST = 10;
    int SALT_BYTES = 16;
    String VERSION = "2a";
    Promise<PasswordHash> hash(Password password);

    static HashPassword hashPassword() {
        var random = new SecureRandom();

        return password -> Promise.lift(() -> OpenBSDBCrypt.generate(VERSION,
                                                                     password.value().toCharArray(),
                                                                     salt(random),
                                                                     COST)).flatMap(raw -> PasswordHash.passwordHash(raw).async());
    }

    private static byte[] salt(SecureRandom random) {
        var salt = new byte[SALT_BYTES];

        random.nextBytes(salt);

        return salt;
    }
}
