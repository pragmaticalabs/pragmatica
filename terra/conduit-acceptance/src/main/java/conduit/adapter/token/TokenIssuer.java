package conduit.adapter.token;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import conduit.domain.UserId;

import static org.pragmatica.lang.Option.option;


/// Mints the B-04 token: HS256, header `{"alg":"HS256","typ":"JWT"}`, claims exactly `sub` (decimal user id), `iat`,
/// `exp = iat + 86400`, no `iss`/`aud`/`jti`. Register and login mint one each (B-07). The shared secret is
/// `CONDUIT_JWT_SECRET`; there is no default, so a missing secret fails the mint instead of
/// signing with a value nobody else can verify.
@FunctionalInterface
public interface TokenIssuer {
    long LIFETIME_SECONDS = 86_400L;
    String SECRET_VARIABLE = "CONDUIT_JWT_SECRET";
    String ALGORITHM = "HmacSHA256";

    /// The signing secret is absent: nothing can be minted. A design-out: the issuer
    /// never signs with a default, and Terra refuses to launch without its own copy (TERRA_JWT__SECRET).
    enum SecretFailure implements Cause {
        MISSING(SECRET_VARIABLE + " is not set");
        private final String message;
        SecretFailure(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    SecretFailure SECRET_MISSING = SecretFailure.MISSING;
    Result<String> issue(UserId subject, Instant issuedAt);

    static TokenIssuer tokenIssuer() {
        return hs256(option(System.getenv(SECRET_VARIABLE)));
    }

    static TokenIssuer hs256(Option<String> secret) {
        return (subject, issuedAt) -> secret.filter(Verify.Is::notEmpty)
                                            .toResult(SECRET_MISSING)
                                            .flatMap(key -> sign(key, subject, issuedAt));
    }

    private static Result<String> sign(String secret, UserId subject, Instant issuedAt) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        var iat = issuedAt.getEpochSecond();
        var header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        var claims = "{\"sub\":\"" + subject.value()
                   + "\",\"iat\":" + iat
                   + ",\"exp\":" + (iat + LIFETIME_SECONDS)
                   + "}";
        var signingInput = header + "." + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));

        return hmac(secret, signingInput).map(signature -> signingInput + "." + encoder.encodeToString(signature));
    }

    private static Result<byte[]> hmac(String secret, String signingInput) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        var input = signingInput.getBytes(StandardCharsets.UTF_8);

        return Result.lift(Causes::fromThrowable,
                           () -> Mac.getInstance(ALGORITHM))
                     .flatMap(mac -> Result.lift(Causes::fromThrowable,
                                                 () -> mac.init(key))
                                           .map(_ -> mac.doFinal(input)));
    }
}
