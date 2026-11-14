package conduit.adapter.token;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import conduit.domain.UserId;

import static conduit.domain.Expect.failure;
import static conduit.domain.Expect.success;
import static org.assertj.core.api.Assertions.assertThat;



class TokenIssuerTest {
    private static final String SECRET = "conduit-bench-jwt-secret-do-not-use-in-production";

    @Test
    void mintsTheB04Token_exactHeaderAndClaims_validHs256Signature() throws Exception {
        var token = success(TokenIssuer.hs256(Option.some(SECRET))
                                       .issue(success(UserId.userId(42)), Instant.ofEpochSecond(1_700_000_000L)));
        var parts = token.split("\\.");
        var decoder = Base64.getUrlDecoder();

        assertThat(parts).hasSize(3);
        assertThat(token).doesNotContain("=");
        assertThat(new String(decoder.decode(parts[0]), StandardCharsets.UTF_8)).isEqualTo("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        assertThat(new String(decoder.decode(parts[1]), StandardCharsets.UTF_8)).isEqualTo("{\"sub\":\"42\",\"iat\":1700000000,\"exp\":1700086400}");
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var expected = Base64.getUrlEncoder()
                             .withoutPadding()
                             .encodeToString(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)));
        assertThat(parts[2]).isEqualTo(expected);
    }

    @Test
    void missingOrEmptySecret_failsInsteadOfSigning() {
        assertThat(failure(TokenIssuer.hs256(Option.none())
                                      .issue(success(UserId.userId(1)), Instant.now())).message()).contains("CONDUIT_JWT_SECRET");
        assertThat(failure(TokenIssuer.hs256(Option.some(""))
                                      .issue(success(UserId.userId(1)), Instant.now())).message()).contains("CONDUIT_JWT_SECRET");
    }
}
