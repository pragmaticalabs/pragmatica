package org.pragmatica.aether.http.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.lang.Option;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class JwtSecurityValidatorTest {
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final String TEST_KID = "test-key-1";
    private static final String TEST_ISSUER = "https://auth.example.com";
    private static final String TEST_AUDIENCE = "my-api";
    private static final String TEST_SUBJECT = "user-123";

    private KeyPair rsaKeyPair;
    private JwtSecurityValidator validator;
    private JwtSecurityValidator validatorWithIssuer;
    private JwtSecurityValidator validatorWithAudience;

    @BeforeEach
    void setUp() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        rsaKeyPair = kpg.generateKeyPair();

        var keyStore = testKeyStore(rsaKeyPair.getPublic());

        var baseConfig = JwtConfig.jwtConfig("https://auth.example.com/.well-known/jwks.json").unwrap();
        validator = new JwtSecurityValidator(baseConfig, keyStore);

        var issuerConfig = JwtConfig.jwtConfig("https://auth.example.com/.well-known/jwks.json",
                                                Option.some(TEST_ISSUER),
                                                Option.empty(),
                                                "role",
                                                3600).unwrap();
        validatorWithIssuer = new JwtSecurityValidator(issuerConfig, keyStore);

        var audienceConfig = JwtConfig.jwtConfig("https://auth.example.com/.well-known/jwks.json",
                                                  Option.empty(),
                                                  Option.some(TEST_AUDIENCE),
                                                  "role",
                                                  3600).unwrap();
        validatorWithAudience = new JwtSecurityValidator(audienceConfig, keyStore);
    }

    @Nested
    class ValidationTests {
        @Test
        void validate_succeeds_forValidRs256Token() {
            var token = buildToken(Map.of("sub", TEST_SUBJECT,
                                          "exp", futureExp(),
                                          "role", "ADMIN"));

            validator.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(ctx -> {
                         assertThat(ctx.isAuthenticated()).isTrue();
                         assertThat(ctx.principal().isUser()).isTrue();
                         assertThat(ctx.principal().value()).isEqualTo("user:" + TEST_SUBJECT);
                     });
        }

        @Test
        void validate_extractsClaims_fromPayload() {
            var token = buildToken(Map.of("sub", TEST_SUBJECT,
                                          "exp", futureExp(),
                                          "custom", "value123"));

            validator.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(ctx -> {
                         assertThat(ctx.claim("sub")).isEqualTo(TEST_SUBJECT);
                         assertThat(ctx.claim("custom")).isEqualTo("value123");
                     });
        }

        @Test
        void validate_allowsAnonymous_forPublicRoute() {
            validator.validate(requestWithoutToken(), RouteSecurityPolicy.publicRoute())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(ctx -> assertThat(ctx.isAuthenticated()).isFalse());
        }
    }

    @Nested
    class TokenExpirationTests {
        @Test
        void validate_rejects_expiredToken() {
            var token = buildToken(Map.of("sub", TEST_SUBJECT,
                                          "exp", pastExp()));

            validator.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                     .onSuccess(ctx -> fail("Expected failure"))
                     .onFailure(cause -> {
                         assertThat(cause).isInstanceOf(SecurityError.TokenExpired.class);
                         assertThat(cause.message()).contains("expired");
                     });
        }
    }

    @Nested
    class IssuerValidationTests {
        @Test
        void validate_rejects_wrongIssuer() {
            var token = buildToken(Map.of("sub", TEST_SUBJECT,
                                          "exp", futureExp(),
                                          "iss", "https://wrong-issuer.com"));

            validatorWithIssuer.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                               .onSuccess(ctx -> fail("Expected failure"))
                               .onFailure(cause -> {
                                   assertThat(cause).isInstanceOf(SecurityError.IssuerMismatch.class);
                                   assertThat(cause.message()).contains("mismatch");
                               });
        }

        @Test
        void validate_accepts_correctIssuer() {
            var token = buildToken(Map.of("sub", TEST_SUBJECT,
                                          "exp", futureExp(),
                                          "iss", TEST_ISSUER));

            validatorWithIssuer.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                               .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                               .onSuccess(ctx -> assertThat(ctx.isAuthenticated()).isTrue());
        }
    }

    @Nested
    class AudienceValidationTests {
        @Test
        void validate_rejects_wrongAudience() {
            var token = buildToken(Map.of("sub", TEST_SUBJECT,
                                          "exp", futureExp(),
                                          "aud", "wrong-audience"));

            validatorWithAudience.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                                 .onSuccess(ctx -> fail("Expected failure"))
                                 .onFailure(cause -> {
                                     assertThat(cause).isInstanceOf(SecurityError.AudienceMismatch.class);
                                     assertThat(cause.message()).contains("mismatch");
                                 });
        }

        @Test
        void validate_accepts_correctAudience() {
            var token = buildToken(Map.of("sub", TEST_SUBJECT,
                                          "exp", futureExp(),
                                          "aud", TEST_AUDIENCE));

            validatorWithAudience.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                                 .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                                 .onSuccess(ctx -> assertThat(ctx.isAuthenticated()).isTrue());
        }
    }

    @Nested
    class MissingHeaderTests {
        @Test
        void validate_rejects_missingAuthorizationHeader() {
            validator.validate(requestWithoutToken(), RouteSecurityPolicy.bearerTokenRequired())
                     .onSuccess(ctx -> fail("Expected failure"))
                     .onFailure(cause -> {
                         assertThat(cause).isInstanceOf(SecurityError.MissingCredentials.class);
                         assertThat(cause.message()).contains("Bearer");
                     });
        }
    }

    @Nested
    class MalformedTokenTests {
        @Test
        void validate_rejects_malformedToken_notThreeParts() {
            var request = requestWithToken("not.a.valid.jwt.token");

            validator.validate(request, RouteSecurityPolicy.bearerTokenRequired())
                     .onSuccess(ctx -> fail("Expected failure"))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(SecurityError.InvalidCredentials.class));
        }

        @Test
        void validate_rejects_malformedToken_twoParts() {
            var request = requestWithToken("header.payload");

            validator.validate(request, RouteSecurityPolicy.bearerTokenRequired())
                     .onSuccess(ctx -> fail("Expected failure"))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(SecurityError.InvalidCredentials.class));
        }
    }

    @Nested
    class SignatureValidationTests {
        @Test
        void validate_rejects_invalidSignature() throws Exception {
            // Build a token with a different key
            var otherKpg = KeyPairGenerator.getInstance("RSA");
            otherKpg.initialize(2048);
            var otherKeyPair = otherKpg.generateKeyPair();

            var header = BASE64URL.encodeToString(
                ("{\"alg\":\"RS256\",\"kid\":\"" + TEST_KID + "\"}").getBytes(StandardCharsets.UTF_8));
            var payload = BASE64URL.encodeToString(
                ("{\"sub\":\"" + TEST_SUBJECT + "\",\"exp\":" + futureExp() + "}").getBytes(StandardCharsets.UTF_8));
            var signedContent = header + "." + payload;

            // Sign with the OTHER key (not the one in our key store)
            var sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(otherKeyPair.getPrivate());
            sig.update(signedContent.getBytes(StandardCharsets.US_ASCII));
            var signature = BASE64URL.encodeToString(sig.sign());

            var token = signedContent + "." + signature;

            validator.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                     .onSuccess(ctx -> fail("Expected failure"))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(SecurityError.SignatureInvalid.class));
        }
    }

    @Nested
    class RoleExtractionTests {
        @Test
        void validate_extractsAdminRole_fromRoleClaim() {
            var token = buildToken(Map.of("sub", TEST_SUBJECT,
                                          "exp", futureExp(),
                                          "role", "ADMIN"));

            validator.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(ctx -> assertThat(ctx.authorizationRole()).isEqualTo(AuthorizationRole.ADMIN));
        }

        @Test
        void validate_extractsOperatorRole_fromRoleClaim() {
            var token = buildToken(Map.of("sub", TEST_SUBJECT,
                                          "exp", futureExp(),
                                          "role", "OPERATOR"));

            validator.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(ctx -> assertThat(ctx.authorizationRole()).isEqualTo(AuthorizationRole.OPERATOR));
        }

        @Test
        void validate_defaultsToViewer_whenRoleClaimMissing() {
            var token = buildToken(Map.of("sub", TEST_SUBJECT,
                                          "exp", futureExp()));

            validator.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(ctx -> assertThat(ctx.authorizationRole()).isEqualTo(AuthorizationRole.VIEWER));
        }

        @Test
        void validate_defaultsToViewer_whenRoleClaimUnknown() {
            var token = buildToken(Map.of("sub", TEST_SUBJECT,
                                          "exp", futureExp(),
                                          "role", "SUPERUSER"));

            validator.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(ctx -> assertThat(ctx.authorizationRole()).isEqualTo(AuthorizationRole.VIEWER));
        }

        @Test
        void validate_extractsRoles_intoRoleSet() {
            var token = buildToken(Map.of("sub", TEST_SUBJECT,
                                          "exp", futureExp(),
                                          "role", "admin"));

            validator.validate(requestWithToken(token), RouteSecurityPolicy.bearerTokenRequired())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(ctx -> assertThat(ctx.hasRole(Role.ADMIN)).isTrue());
        }
    }

    @Nested
    class CacheTests {
        @Test
        void validate_usesCache_forSecondCall() {
            var token1 = buildToken(Map.of("sub", "user-1", "exp", futureExp()));
            var token2 = buildToken(Map.of("sub", "user-2", "exp", futureExp()));

            validator.validate(requestWithToken(token1), RouteSecurityPolicy.bearerTokenRequired())
                     .onFailure(cause -> fail("First call failed: " + cause.message()));

            // Second call should use cached keys (no JWKS fetch)
            validator.validate(requestWithToken(token2), RouteSecurityPolicy.bearerTokenRequired())
                     .onFailure(cause -> fail("Second call failed: " + cause.message()))
                     .onSuccess(ctx -> assertThat(ctx.principal().value()).isEqualTo("user:user-2"));
        }
    }

    // ================== Test Helpers ==================

    private String buildToken(Map<String, Object> claims) {
        return buildRs256Token(claims, rsaKeyPair, TEST_KID);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String buildRs256Token(Map<String, Object> claims, KeyPair keyPair, String kid) {
        var header = BASE64URL.encodeToString(
            ("{\"alg\":\"RS256\",\"kid\":\"" + kid + "\"}").getBytes(StandardCharsets.UTF_8));
        var payloadJson = toJson(claims);
        var payload = BASE64URL.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        var signedContent = header + "." + payload;

        try {
            var sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(keyPair.getPrivate());
            sig.update(signedContent.getBytes(StandardCharsets.US_ASCII));
            var signature = BASE64URL.encodeToString(sig.sign());
            return signedContent + "." + signature;
        } catch (Exception e) {
            throw new AssertionError("Failed to sign test JWT", e);
        }
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static String toJson(Map<String, Object> map) {
        var sb = new StringBuilder("{");
        var first = true;
        for (var entry : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            if (entry.getValue() instanceof Number n) {
                sb.append(n);
            } else {
                sb.append("\"").append(entry.getValue()).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static long futureExp() {
        return System.currentTimeMillis() / 1000 + 3600;
    }

    private static long pastExp() {
        return System.currentTimeMillis() / 1000 - 3600;
    }

    private static HttpRequestContext requestWithToken(String token) {
        return HttpRequestContext.httpRequestContext("/test",
                                                     "GET",
                                                     Map.of(),
                                                     Map.of("Authorization", List.of("Bearer " + token)),
                                                     new byte[0],
                                                     "test-request-id");
    }

    private static HttpRequestContext requestWithoutToken() {
        return HttpRequestContext.httpRequestContext("/test",
                                                     "GET",
                                                     Map.of(),
                                                     Map.of(),
                                                     new byte[0],
                                                     "test-request-id");
    }

    /// Creates a test JwksKeyStore pre-loaded with the given public key under TEST_KID.
    private static JwksKeyStore testKeyStore(PublicKey publicKey) {
        var store = new JwksKeyStore("https://unused.example.com/.well-known/jwks.json", 3600);
        // Directly populate the cache via reflection-free approach: use the internal cache
        store.keyCache.put(TEST_KID, publicKey);
        store.lastFetchTime.set(System.currentTimeMillis());
        return store;
    }
}
