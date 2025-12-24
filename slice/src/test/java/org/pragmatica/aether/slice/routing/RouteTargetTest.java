package org.pragmatica.aether.slice.routing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteTargetTest {

    @Test
    void routeTarget_succeeds_withNoParams() {
        RouteTarget.routeTarget("user-service:getUser()")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(target -> {
                       assertThat(target.sliceId()).isEqualTo("user-service");
                       assertThat(target.methodName()).isEqualTo("getUser");
                       assertThat(target.params()).isEmpty();
                   });
    }

    @Test
    void routeTarget_succeeds_withSingleParam() {
        RouteTarget.routeTarget("user-service:getUser(userId)")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(target -> {
                       assertThat(target.sliceId()).isEqualTo("user-service");
                       assertThat(target.methodName()).isEqualTo("getUser");
                       assertThat(target.params()).containsExactly("userId");
                   });
    }

    @Test
    void routeTarget_succeeds_withMultipleParams() {
        RouteTarget.routeTarget("user-service:getUser(userId, page)")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(target -> {
                       assertThat(target.sliceId()).isEqualTo("user-service");
                       assertThat(target.methodName()).isEqualTo("getUser");
                       assertThat(target.params()).containsExactly("userId", "page");
                   });
    }

    @Test
    void routeTarget_succeeds_withWhitespace() {
        RouteTarget.routeTarget("user-service:getUser( userId , page )")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(target -> {
                       assertThat(target.params()).containsExactly("userId", "page");
                   });
    }

    @Test
    void routeTarget_fails_withoutColon() {
        RouteTarget.routeTarget("user-service-getUser()")
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("Invalid route target format"));
    }

    @Test
    void routeTarget_fails_withoutParentheses() {
        RouteTarget.routeTarget("user-service:getUser")
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("Invalid route target format"));
    }

    @Test
    void routeTarget_fails_withInvalidSliceId() {
        RouteTarget.routeTarget("User-Service:getUser()")
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("Invalid slice ID"));
    }

    @Test
    void routeTarget_fails_withInvalidMethodName() {
        RouteTarget.routeTarget("user-service:get-user()")
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("Invalid method name"));
    }

    @Test
    void routeTarget_fails_withInvalidParam() {
        RouteTarget.routeTarget("user-service:getUser(user-id)")
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("Invalid parameter name"));
    }

    @Test
    void asString_formats_correctly() {
        RouteTarget.routeTarget("user-service:getUser(userId, page)")
                   .map(RouteTarget::asString)
                   .onSuccess(result -> assertThat(result).isEqualTo("user-service:getUser(userId, page)"))
                   .onFailureRun(Assertions::fail);
    }

    @Test
    void asString_formats_noParams() {
        RouteTarget.routeTarget("user-service:getUser()")
                   .map(RouteTarget::asString)
                   .onSuccess(result -> assertThat(result).isEqualTo("user-service:getUser()"))
                   .onFailureRun(Assertions::fail);
    }
}
