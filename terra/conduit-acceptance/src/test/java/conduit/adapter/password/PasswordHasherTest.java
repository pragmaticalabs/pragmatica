package conduit.adapter.password;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import conduit.domain.ConduitError.Unauthorized;
import conduit.domain.Password;
import conduit.domain.PasswordHash;

import static conduit.domain.Expect.failure;
import static conduit.domain.Expect.success;
import static org.assertj.core.api.Assertions.assertThat;



class PasswordHasherTest {
    private final HashPassword hasher = HashPassword.hashPassword();
    private final VerifyPassword verifier = VerifyPassword.verifyPassword();
    private final Password password = success(Password.password(Option.option("password123")));

    @Test
    void hashIsBcrypt2aCost10_andVerifiesItsPassword() {
        var hash = success(hasher.hash(password));

        assertThat(hash.value()).startsWith("$2a$10$")
                                .hasSize(60);
        assertThat(success(verifier.verify(hash, password))).isEqualTo(Unit.unit());
        assertThat(failure(verifier.verify(hash, success(Password.password(Option.option("password124")))))).isEqualTo(Unauthorized.CREDENTIALS_INVALID);
    }

    /// The seed's hashes are `$2a$10$`; a `$2b$` hash must verify too (B-10).
    @Test
    void acceptsForeign2bHashes() {
        var foreign2b = success(hasher.hash(password)).value()
                                                      .replaceFirst("^\\$2a\\$", "\\$2b\\$");

        assertThat(success(verifier.verify(success(PasswordHash.passwordHash(foreign2b)), password))).isNotNull();
    }

    @Test
    void unreadableStoredHash_isInvalidCredentials_notA500() {
        assertThat(failure(verifier.verify(success(PasswordHash.passwordHash("not-a-hash")), password))).isEqualTo(Unauthorized.CREDENTIALS_INVALID);
    }
}
