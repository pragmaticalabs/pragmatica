package org.pragmatica.aether.environment.aws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.LoadBalancerState;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.cloud.aws.api.TargetHealth;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AwsLoadBalancerProviderTest {

    private static final String TARGET_GROUP_ARN = "arn:aws:elasticloadbalancing:us-east-1:123:targetgroup/my-tg/abc";

    private TestAwsClient testClient;
    private AwsLoadBalancerProvider provider;

    @BeforeEach
    void setUp() {
        testClient = new TestAwsClient();
        provider = AwsLoadBalancerProvider.awsLoadBalancerProvider(testClient, TARGET_GROUP_ARN).unwrap();
    }

    @Nested
    class OnRouteChangedTests {

        @Test
        void onRouteChanged_registersTargets() {
            var routeChange = new RouteChange("GET", "/api", Set.of("i-1", "i-2"));

            provider.onRouteChanged(routeChange)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastRegisterTargetGroupArn).isEqualTo(TARGET_GROUP_ARN);
            assertThat(testClient.lastRegisteredIds).containsExactlyInAnyOrder("i-1", "i-2");
        }
    }

    @Nested
    class OnNodeRemovedTests {

        @Test
        void onNodeRemoved_deregistersTarget() {
            provider.onNodeRemoved("i-1")
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastDeregisterTargetGroupArn).isEqualTo(TARGET_GROUP_ARN);
            assertThat(testClient.lastDeregisteredIds).containsExactly("i-1");
        }
    }

    @Nested
    class ReconcileTests {

        @Test
        void reconcile_registersNewAndDeregistersOld() {
            testClient.describeTargetHealthResponse = Promise.success(List.of(
                new TargetHealth("i-old", 80, "healthy", ""),
                new TargetHealth("i-keep", 80, "healthy", "")));

            var state = new LoadBalancerState(Set.of("i-keep", "i-new"), List.of());

            provider.reconcile(state)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastRegisteredIds).containsExactly("i-new");
            assertThat(testClient.lastDeregisteredIds).containsExactly("i-old");
        }

        @Test
        void reconcile_noChanges_doesNothing() {
            testClient.describeTargetHealthResponse = Promise.success(List.of(
                new TargetHealth("i-1", 80, "healthy", "")));

            var state = new LoadBalancerState(Set.of("i-1"), List.of());

            provider.reconcile(state)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastRegisteredIds).isNull();
            assertThat(testClient.lastDeregisteredIds).isNull();
        }
    }
}
