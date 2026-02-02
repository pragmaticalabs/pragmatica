package org.pragmatica.aether.infra.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigServiceTest {
    private ConfigService configService;

    @BeforeEach
    void setUp() {
        configService = ConfigService.configService();
    }

    @Test
    void loadToml_parsesValidContent() {
        var toml = """
            [database]
            host = "localhost"
            port = 5432
            enabled = true
            timeout = 30.5
            """;

        configService.loadToml(ConfigScope.GLOBAL, toml)
            .onFailureRun(Assertions::fail);

        configService.getString("database", "host")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> assertThat(opt.fold(() -> "", v -> v)).isEqualTo("localhost"));

        configService.getInt("database", "port")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> assertThat(opt.fold(() -> 0, v -> v)).isEqualTo(5432));

        configService.getBoolean("database", "enabled")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> assertThat(opt.fold(() -> false, v -> v)).isTrue());

        configService.getDouble("database", "timeout")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> assertThat(opt.fold(() -> 0.0, v -> v)).isEqualTo(30.5));
    }

    @Test
    void loadToml_failsOnInvalidContent() {
        var invalidToml = "invalid [ toml";

        configService.loadToml(ConfigScope.GLOBAL, invalidToml)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause).isInstanceOf(ConfigError.ParseFailed.class));
    }

    @Test
    void hierarchicalLookup_sliceOverridesNodeOverridesGlobal() {
        var globalToml = """
            [server]
            port = 8080
            timeout = 30
            name = "global"
            """;
        var nodeToml = """
            [server]
            port = 9090
            name = "node"
            """;
        var sliceToml = """
            [server]
            port = 3000
            """;

        configService.loadToml(ConfigScope.GLOBAL, globalToml);
        configService.loadToml(ConfigScope.NODE, nodeToml);
        configService.loadToml(ConfigScope.SLICE, sliceToml);

        // Port should come from SLICE
        configService.getInt("server", "port")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> assertThat(opt.fold(() -> 0, v -> v)).isEqualTo(3000));

        // Name should come from NODE (not in SLICE)
        configService.getString("server", "name")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> assertThat(opt.fold(() -> "", v -> v)).isEqualTo("node"));

        // Timeout should come from GLOBAL (not in SLICE or NODE)
        configService.getInt("server", "timeout")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> assertThat(opt.fold(() -> 0, v -> v)).isEqualTo(30));
    }

    @Test
    void getScopedValue_returnsOnlyFromSpecificScope() {
        var globalToml = """
            [server]
            port = 8080
            """;
        var nodeToml = """
            [server]
            port = 9090
            """;

        configService.loadToml(ConfigScope.GLOBAL, globalToml);
        configService.loadToml(ConfigScope.NODE, nodeToml);

        // Explicitly get from GLOBAL
        configService.getInt(ConfigScope.GLOBAL, "server", "port")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> assertThat(opt.fold(() -> 0, v -> v)).isEqualTo(8080));

        // Explicitly get from NODE
        configService.getInt(ConfigScope.NODE, "server", "port")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> assertThat(opt.fold(() -> 0, v -> v)).isEqualTo(9090));
    }

    @Test
    void set_updatesConfigurationValue() {
        configService.set(ConfigScope.GLOBAL, "app", "name", "TestApp")
            .await()
            .onFailureRun(Assertions::fail);

        configService.getString("app", "name")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> assertThat(opt.fold(() -> "", v -> v)).isEqualTo("TestApp"));
    }

    @Test
    void getStringList_returnsListValue() {
        var toml = """
            [tags]
            values = ["alpha", "beta", "gamma"]
            """;

        configService.loadToml(ConfigScope.GLOBAL, toml);

        configService.getStringList("tags", "values")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> {
                assertThat(opt.isPresent()).isTrue();
                var list = opt.fold(java.util.List::<String>of, v -> v);
                assertThat(list).hasSize(3);
                assertThat(list.get(0)).isEqualTo("alpha");
                assertThat(list.get(1)).isEqualTo("beta");
                assertThat(list.get(2)).isEqualTo("gamma");
            });
    }

    @Test
    void watch_notifiesOnValueChange() {
        var received = new AtomicReference<Option<String>>(Option.none());

        configService.watch("app", "version", value -> {
            received.set(value);
            return Unit.unit();
        }).await().onFailureRun(Assertions::fail);

        configService.set(ConfigScope.GLOBAL, "app", "version", "1.0.0")
            .await()
            .onFailureRun(Assertions::fail);

        assertThat(received.get().fold(() -> "", v -> v)).isEqualTo("1.0.0");
    }

    @Test
    void watch_subscriptionCanBeCancelled() {
        var received = new AtomicReference<Option<String>>(Option.none());

        var subscription = configService.watch("app", "test", value -> {
            received.set(value);
            return Unit.unit();
        }).await().fold(c -> null, v -> v);

        assertThat(subscription).isNotNull();
        assertThat(subscription.isActive()).isTrue();

        subscription.cancel();
        assertThat(subscription.isActive()).isFalse();

        // After cancel, updates should not be received
        received.set(Option.none());
        configService.set(ConfigScope.GLOBAL, "app", "test", "updated")
            .await();

        assertThat(received.get().isEmpty()).isTrue();
    }

    @Test
    void getDocument_returnsTomlDocument() {
        var toml = """
            [server]
            port = 8080
            """;

        configService.loadToml(ConfigScope.GLOBAL, toml);

        var doc = configService.getDocument(ConfigScope.GLOBAL);
        assertThat(doc.getInt("server", "port").fold(() -> 0, v -> v)).isEqualTo(8080);
    }

    @Test
    void start_succeeds() {
        configService.start()
            .await()
            .onFailureRun(Assertions::fail);
    }

    @Test
    void stop_succeeds() {
        configService.stop()
            .await()
            .onFailureRun(Assertions::fail);
    }

    @Test
    void methods_returnsEmptyList() {
        assertThat(configService.methods()).isEmpty();
    }

    @Test
    void missingKey_returnsEmptyOption() {
        configService.getString("nonexistent", "key")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
    }
}
