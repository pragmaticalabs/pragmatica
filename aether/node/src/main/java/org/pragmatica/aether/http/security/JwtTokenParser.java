package org.pragmatica.aether.http.security;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.pragmatica.lang.Result.success;

/// Parses JWT tokens into header, payload, and signature components.
///
/// Uses JDK Base64url decoder and Jackson for JSON parsing.
/// No external JWT library dependencies.
@SuppressWarnings("JBCT-RET-03")
final class JwtTokenParser {
    private static final JsonMapper JSON = JsonMapper.defaultJsonMapper();

    private static final TypeToken<Map<String, Object>> MAP_TYPE = new TypeToken<>() {};

    private static final Base64.Decoder BASE64URL = Base64.getUrlDecoder();

    private JwtTokenParser() {}

    /// Parsed JWT with decoded header, payload, and raw signature bytes.
    record JwtHeader(String alg, String kid) {}

    record ParsedJwt(JwtHeader header,
                     Map<String, Object> payload,
                     byte[] signature,
                     String signedContent) {}

    /// Parse a raw JWT string into its three components.
    static Result<ParsedJwt> parseToken(String token) {
        return splitToken(token).flatMap(JwtTokenParser::decodeComponents);
    }

    private static Result<String[]> splitToken(String token) {
        var parts = token.split("\\.");
        return parts.length == 3
               ? success(parts)
               : SecurityError.MALFORMED_TOKEN.result();
    }

    private static Result<ParsedJwt> decodeComponents(String[] parts) {
        return decodeHeader(parts[0])
        .flatMap(header -> decodePayload(parts[1])
        .flatMap(payload -> decodeSignature(parts[2])
        .map(sig -> new ParsedJwt(header, payload, sig, parts[0] + "." + parts[1]))));
    }

    private static Result<JwtHeader> decodeHeader(String headerB64) {
        return decodeBase64Json(headerB64).flatMap(JwtTokenParser::extractHeader);
    }

    private static Result<JwtHeader> extractHeader(Map<String, Object> headerMap) {
        var alg = Option.option(headerMap.get("alg"))
                        .map(Object::toString)
                        .or("RS256");
        var kid = Option.option(headerMap.get("kid"))
                        .map(Object::toString)
                        .or("");
        return success(new JwtHeader(alg, kid));
    }

    private static Result<Map<String, Object>> decodePayload(String payloadB64) {
        return decodeBase64Json(payloadB64);
    }

    private static Result<byte[]> decodeSignature(String signatureB64) {
        return decodeBase64Bytes(signatureB64);
    }

    private static Result<Map<String, Object>> decodeBase64Json(String base64) {
        return decodeBase64Bytes(base64).map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                                .flatMap(json -> JSON.readString(json, MAP_TYPE));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<byte[]> decodeBase64Bytes(String base64) {
        return Result.lift(JwtTokenParser::decodeFailed, () -> BASE64URL.decode(base64));
    }

    private static SecurityError decodeFailed(Throwable t) {
        return new SecurityError.InvalidCredentials("Base64 decode failed: " + t.getMessage());
    }
}
