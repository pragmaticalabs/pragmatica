package org.pragmatica.aether.forge.load;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoadConfigLoaderTest {

    @Test
    void loadFromString_succeeds_forValidConfig() {
        var toml = """
            [[load]]
            target = "InventoryService.checkStock"
            rate = "100/s"
            """;

        LoadConfigLoader.loadFromString(toml)
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                assertThat(config.targets()).hasSize(1);
                var target = config.targets().getFirst();
                assertThat(target.target()).isEqualTo("InventoryService.checkStock");
                assertThat(target.rate().requestsPerSecond()).isEqualTo(100);
                assertThat(target.isContinuous()).isTrue();
            });
    }

    @Test
    void loadFromString_succeeds_withDuration() {
        var toml = """
            [[load]]
            target = "/api/orders"
            rate = "50/s"
            duration = "5m"
            """;

        LoadConfigLoader.loadFromString(toml)
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                var target = config.targets().getFirst();
                assertThat(target.duration().isPresent()).isTrue();
                assertThat(target.duration().unwrap()).isEqualTo(Duration.ofMinutes(5));
                assertThat(target.isContinuous()).isFalse();
            });
    }

    @Test
    void loadFromString_succeeds_withPathVars() {
        var toml = """
            [[load]]
            target = "/api/inventory/{sku}"
            rate = "10/s"

            [load.path]
            sku = "${random:SKU-#####}"
            """;

        LoadConfigLoader.loadFromString(toml)
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                var target = config.targets().getFirst();
                assertThat(target.pathVars()).containsEntry("sku", "${random:SKU-#####}");
            });
    }

    @Test
    void loadFromString_succeeds_withBody() {
        var toml = """
            [[load]]
            target = "OrderService.placeOrder"
            rate = "5/s"
            body = "{\\"quantity\\": 10}"
            """;

        LoadConfigLoader.loadFromString(toml)
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                var target = config.targets().getFirst();
                assertThat(target.body().isPresent()).isTrue();
                assertThat(target.body().unwrap()).contains("quantity");
            });
    }

    @Test
    void loadFromString_succeeds_withMultipleTargets() {
        var toml = """
            [[load]]
            target = "ServiceA.methodA"
            rate = "100/s"

            [[load]]
            target = "ServiceB.methodB"
            rate = "50/s"

            [[load]]
            target = "/api/health"
            rate = "1/s"
            """;

        LoadConfigLoader.loadFromString(toml)
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                assertThat(config.targets()).hasSize(3);
                assertThat(config.totalRequestsPerSecond()).isEqualTo(151);
            });
    }

    @Test
    void loadFromString_succeeds_withOptionalName() {
        var toml = """
            [[load]]
            name = "Inventory Check Load"
            target = "InventoryService.checkStock"
            rate = "100/s"
            """;

        LoadConfigLoader.loadFromString(toml)
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                var target = config.targets().getFirst();
                assertThat(target.name().isPresent()).isTrue();
                assertThat(target.name().unwrap()).isEqualTo("Inventory Check Load");
            });
    }

    @Test
    void loadFromString_fails_withoutTarget() {
        var toml = """
            [[load]]
            rate = "100/s"
            """;

        LoadConfigLoader.loadFromString(toml)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("target is required"));
    }

    @Test
    void loadFromString_fails_withoutRate() {
        var toml = """
            [[load]]
            target = "SomeService.method"
            """;

        LoadConfigLoader.loadFromString(toml)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("rate is required"));
    }

    @Test
    void loadFromString_fails_withNoLoadSections() {
        var toml = """
            [config]
            name = "test"
            """;

        LoadConfigLoader.loadFromString(toml)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("No [[load]] sections found"));
    }

    @Test
    void loadFromString_handlesPerMinuteRate() {
        var toml = """
            [[load]]
            target = "SlowService.method"
            rate = "60/m"
            """;

        LoadConfigLoader.loadFromString(toml)
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                var target = config.targets().getFirst();
                assertThat(target.rate().requestsPerSecond()).isEqualTo(1);
            });
    }

    @Test
    void loadFromString_handlesPerHourRate() {
        var toml = """
            [[load]]
            target = "VerySlowService.method"
            rate = "3600/h"
            """;

        LoadConfigLoader.loadFromString(toml)
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                var target = config.targets().getFirst();
                assertThat(target.rate().requestsPerSecond()).isEqualTo(1);
            });
    }

    @Test
    void loadFromString_handlesDurationUnits() {
        var toml1 = """
            [[load]]
            target = "Service.method"
            rate = "10/s"
            duration = "30s"
            """;
        var toml2 = """
            [[load]]
            target = "Service.method"
            rate = "10/s"
            duration = "2h"
            """;
        var toml3 = """
            [[load]]
            target = "Service.method"
            rate = "10/s"
            duration = "500ms"
            """;

        LoadConfigLoader.loadFromString(toml1)
            .onFailureRun(Assertions::fail)
            .onSuccess(c -> assertThat(c.targets().getFirst().duration().unwrap())
                .isEqualTo(Duration.ofSeconds(30)));

        LoadConfigLoader.loadFromString(toml2)
            .onFailureRun(Assertions::fail)
            .onSuccess(c -> assertThat(c.targets().getFirst().duration().unwrap())
                .isEqualTo(Duration.ofHours(2)));

        LoadConfigLoader.loadFromString(toml3)
            .onFailureRun(Assertions::fail)
            .onSuccess(c -> assertThat(c.targets().getFirst().duration().unwrap())
                .isEqualTo(Duration.ofMillis(500)));
    }
}
