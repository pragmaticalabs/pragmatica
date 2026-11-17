package conduit.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Option;

import conduit.domain.Caller.Authenticated;
import conduit.domain.ConduitError.Unauthorized;

import static conduit.domain.Expect.failure;
import static conduit.domain.Expect.success;
import static org.assertj.core.api.Assertions.assertThat;



class CallerTest {
    @Test
    void anonymousContext_hasNoViewer_andIsTokenMissingWhereAuthIsRequired() {
        var anonymous = SecurityContext.securityContext();

        assertThat(success(Caller.viewer(anonymous))).isEqualTo(Option.none());
        assertThat(failure(Caller.required(anonymous, headers("Token abc")))).isEqualTo(Unauthorized.TOKEN_MISSING);
    }

    @Test
    void userPrincipal_withOrWithoutPrefix_carriesIdAndToken() {
        var prefixed = SecurityContext.securityContext("17", Set.of(Role.USER), Map.of())
                                      .unwrap();
        var bare = SecurityContext.securityContext(Principal.principal("18")
                                                            .unwrap(),
                                                   Set.of(),
                                                   Map.of());

        assertThat(success(Caller.required(prefixed, headers("Token abc")))).isEqualTo(Authenticated.authenticated(success(UserId.userId(17)),
                                                                                                                  "abc"));
        assertThat(success(Caller.required(bare, headers("Token abc"))).id()).isEqualTo(success(UserId.userId(18)));
        assertThat(success(Caller.viewer(prefixed))).isEqualTo(Option.some(success(UserId.userId(17))));
    }

    @Test
    void token_isTheTokenSchemeValueAsSent_whateverTheSchemeCase_untouched() {
        var user = SecurityContext.securityContext("17", Set.of(Role.USER), Map.of())
                                  .unwrap();

        assertThat(success(Caller.required(user, headers("Token abc.def.ghi"))).token()).isEqualTo("abc.def.ghi");
        assertThat(success(Caller.required(user, headers("token abc.def.ghi"))).token()).isEqualTo("abc.def.ghi");
        assertThat(success(Caller.required(user, headers("Token  padded "))).token()).isEqualTo(" padded ");
    }

    @Test
    void authenticatedWithoutTokenScheme_isTokenInvalid() {
        var user = SecurityContext.securityContext("17", Set.of(Role.USER), Map.of())
                                  .unwrap();

        assertThat(failure(Caller.required(user, headers("Bearer abc.def.ghi")))).isEqualTo(Unauthorized.TOKEN_INVALID);
        assertThat(failure(Caller.required(user, RequestHeaders.empty()))).isEqualTo(Unauthorized.TOKEN_INVALID);
    }

    private static RequestHeaders headers(String authorization) {
        return RequestHeaders.requestHeaders(Map.of("Authorization", List.of(authorization)));
    }

    @Test
    void nonNumericSubject_isTokenInvalid() {
        var context = SecurityContext.securityContext("alice", Set.of(), Map.of())
                                     .unwrap();

        assertThat(failure(Caller.viewer(context))).isEqualTo(Unauthorized.TOKEN_INVALID);
    }
}
