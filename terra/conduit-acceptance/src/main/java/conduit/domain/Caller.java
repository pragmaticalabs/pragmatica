package conduit.domain;

import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import conduit.domain.ConduitError.Unauthorized;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Who is making the request, as the slices see it.
///
/// Token verification (signature, algorithm, expiry, B-03/B-05/B-56) happens before a slice runs; a request that
/// reaches a slice is either anonymous or carries a verified subject. This type is the one place that reads
/// Pragmatica's `SecurityContext` and the `Authorization` header, and it reads the token once, at authentication.
///
/// - The principal is the token's `sub`, with or without Pragmatica's `user:` prefix.
/// - The token exactly as presented is the part of the first `Authorization` value after the `Token` scheme (matched
///   case-insensitively, B-01), untouched: B-07 returns it byte for byte from `GET`/`PUT /user`.
public sealed interface Caller {
    String USER_PREFIX = "user:";
    String TOKEN_SCHEME = "token ";

    record Authenticated(UserId id, String token) {
        public static Authenticated authenticated(UserId id, String token) {
            return new Authenticated(id, token);
        }

        /// The caller as the viewer of `following`/`favorited` probes.
        public Option<UserId> viewer() {
            return some(id);
        }
    }

    /// For required-auth operations: anonymous is `token: is missing` (B-02); an authenticated request whose
    /// `Authorization` header is not the `Token` scheme is `token: is invalid`.
    static Result<Authenticated> required(SecurityContext security, RequestHeaders headers) {
        return security.isAuthenticated()
               ? identity(security).flatMap(id -> presented(headers).map(token -> Authenticated.authenticated(id, token)))
               : Unauthorized.TOKEN_MISSING.result();
    }

    /// For optional-auth operations: the viewer of the `following`/`favorited` probes, empty when anonymous. Bound
    /// as a nullable SQL parameter, `= NULL` is never true, so one query shape serves both (B-47).
    static Result<Option<UserId>> viewer(SecurityContext security) {
        return security.isAuthenticated()
               ? identity(security).map(Option::some)
               : success(none());
    }

    private static Result<UserId> identity(SecurityContext security) {
        var subject = security.principal().value();
        var bare = subject.startsWith(USER_PREFIX)
                   ? subject.substring(USER_PREFIX.length())
                   : subject;

        return UserId.userId(bare);
    }

    private static Result<String> presented(RequestHeaders headers) {
        return headers.first("authorization")
                      .filter(value -> value.regionMatches(true,
                                                           0,
                                                           TOKEN_SCHEME,
                                                           0,
                                                           TOKEN_SCHEME.length()))
                      .map(value -> value.substring(TOKEN_SCHEME.length()))
                      .toResult(Unauthorized.TOKEN_INVALID);
    }

    record unused() implements Caller {}
}
