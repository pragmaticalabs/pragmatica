package org.pragmatica.aether.http.handler.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.ADMIN;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.OPERATOR;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.VIEWER;
import static org.pragmatica.aether.http.handler.security.RoutePermission.ADMIN_ONLY;
import static org.pragmatica.aether.http.handler.security.RoutePermission.ALL_AUTHENTICATED;
import static org.pragmatica.aether.http.handler.security.RoutePermission.OPERATOR_AND_ABOVE;
import static org.pragmatica.aether.http.handler.security.RoutePermission.routePermission;

class RoutePermissionTest {
    @Nested
    class AdminOnly {
        @Test
        void adminOnly_allowsAdmin() {
            assertThat(ADMIN_ONLY.allows(ADMIN)).isTrue();
        }

        @Test
        void adminOnly_rejectsOperator() {
            assertThat(ADMIN_ONLY.allows(OPERATOR)).isFalse();
        }

        @Test
        void adminOnly_rejectsViewer() {
            assertThat(ADMIN_ONLY.allows(VIEWER)).isFalse();
        }
    }

    @Nested
    class OperatorAndAbove {
        @Test
        void operatorAndAbove_allowsAdmin() {
            assertThat(OPERATOR_AND_ABOVE.allows(ADMIN)).isTrue();
        }

        @Test
        void operatorAndAbove_allowsOperator() {
            assertThat(OPERATOR_AND_ABOVE.allows(OPERATOR)).isTrue();
        }

        @Test
        void operatorAndAbove_rejectsViewer() {
            assertThat(OPERATOR_AND_ABOVE.allows(VIEWER)).isFalse();
        }
    }

    @Nested
    class AllAuthenticated {
        @Test
        void allAuthenticated_allowsAdmin() {
            assertThat(ALL_AUTHENTICATED.allows(ADMIN)).isTrue();
        }

        @Test
        void allAuthenticated_allowsOperator() {
            assertThat(ALL_AUTHENTICATED.allows(OPERATOR)).isTrue();
        }

        @Test
        void allAuthenticated_allowsViewer() {
            assertThat(ALL_AUTHENTICATED.allows(VIEWER)).isTrue();
        }
    }

    @Nested
    class FactoryMethod {
        @Test
        void routePermission_createsWithSpecifiedRole() {
            var permission = routePermission(OPERATOR);
            assertThat(permission.minimumRole()).isEqualTo(OPERATOR);
        }

        @Test
        void routePermission_behavesIdenticallyToConstant() {
            var permission = routePermission(ADMIN);
            assertThat(permission.allows(ADMIN)).isEqualTo(ADMIN_ONLY.allows(ADMIN));
            assertThat(permission.allows(OPERATOR)).isEqualTo(ADMIN_ONLY.allows(OPERATOR));
            assertThat(permission.allows(VIEWER)).isEqualTo(ADMIN_ONLY.allows(VIEWER));
        }
    }
}
