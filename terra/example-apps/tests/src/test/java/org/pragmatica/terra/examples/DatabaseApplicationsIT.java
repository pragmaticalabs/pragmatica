// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.examples;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Opt-in process proofs: only uniquely named databases created by this test are removed.
@EnabledIfSystemProperty(named = "terra.test.jdbcUrl", matches = ".+")
class DatabaseApplicationsIT {
    @TempDir Path directory;
    String database;
    String jdbcUrl;
    String adminUrl;
    URI address;

    @BeforeEach void createDatabase() throws Exception {
        adminUrl = System.getProperty("terra.test.jdbcUrl");
        address = URI.create(adminUrl.substring(5));
        database = "terra_app_" + UUID.randomUUID().toString().replace("-", "");
        try (var db = DriverManager.getConnection(adminUrl); var sql = db.createStatement()) {
            sql.execute("CREATE DATABASE " + database);
        }
        jdbcUrl = "jdbc:postgresql://" + address.getAuthority() + "/" + database + "?" + address.getRawQuery();
    }

    @AfterEach void dropDatabase() throws Exception {
        if (database == null) return;
        try (var db = DriverManager.getConnection(adminUrl); var sql = db.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            sql.execute("DROP DATABASE IF EXISTS " + database + "_analytics WITH (FORCE)");
        }
    }

    @Test void ecommerce_extractedApplication_placesOrderAndReusesMigrationHistory() throws Exception {
        var bundle = extract("ecommerce-app", "terra-ecommerce-app");
        var body = """
            {"customerId":"CUST-TERRA", "items":[{"productId":"LAPTOP-PRO","quantity":1}],
             "shippingAddress":{"street":"1 Main St","city":"Seattle","state":"WA","postalCode":"98101","country":"US"},
             "paymentMethod":{"cardNumber":"4242424242424242","expiryMonth":"12","expiryYear":"2099","cvv":"123","cardholderName":"Terra Test"},
             "shippingOption":"STANDARD"}
            """;
        try (var host = launch(bundle); var client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = null;
            // The existing demo intentionally declines 5% of payments. Its compensation must leave
            // stock unchanged; retrying a new order is legitimate demo behavior, not masking 500s.
            for (int attempt = 0; attempt < 20; attempt++) {
                response = send(client, host.port, "/api/v1/orders", body, false);
                if (response.statusCode() == 200) break;
                assertThat(response.statusCode()).as(response.body()).isEqualTo(402);
                assertThat(query("SELECT stock FROM products WHERE product_id='LAPTOP-PRO'")).isEqualTo(50);
            }
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains("CONFIRMED");
            assertThat(query("SELECT COUNT(*) FROM transactions")).isEqualTo(1);
            assertThat(query("SELECT COUNT(*) FROM shipments")).isEqualTo(1);
            assertThat(query("SELECT stock FROM products WHERE product_id='LAPTOP-PRO'")).isEqualTo(49);
        }
        try (var host = launch(bundle); var client = HttpClient.newHttpClient()) {
            assertThat(send(client, host.port, "/__terra/health/ready", null, false).statusCode()).isEqualTo(200);
            assertThat(query("SELECT COUNT(*) FROM aether_schema_history WHERE status='SUCCESS'")).isEqualTo(2);
            assertThat(query("SELECT stock FROM products WHERE product_id='LAPTOP-PRO'")).isEqualTo(49);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"url-shortener", "url-shortener-v2"})
    void urlShortener_extractedApplication_authenticatesPersistsAndDeliversClicks(String module) throws Exception {
        var bundle = extract(module, "terra-" + module);
        String code;
        try (var host = launch(bundle); var client = HttpClient.newHttpClient()) {
            assertThat(send(client, host.port, "/api/v1/urls", "{\"url\":\"https://example.com/terra\"}", false).statusCode()).isEqualTo(401);
            var shortened = send(client, host.port, "/api/v1/urls", "{\"url\":\"https://example.com/terra\"}", true);
            assertThat(shortened.statusCode()).as(shortened.body()).isEqualTo(200);
            var matcher = Pattern.compile("\"shortCode\"\\s*:\\s*\"([^\"]+)\"").matcher(shortened.body());
            assertThat(matcher.find()).isTrue();
            code = matcher.group(1);
            var resolved = send(client, host.port, "/api/v1/urls/" + code, null, false);
            assertThat(resolved.statusCode()).as(resolved.body()).isEqualTo(200);
            assertThat(resolved.body()).contains("https://example.com/terra");
            var stats = send(client, host.port, "/api/v1/analytics/" + code, null, false);
            assertThat(stats.statusCode()).as(stats.body()).isEqualTo(200);
            assertThat(stats.body()).contains("\"clickCount\":1");
            assertThat(query("SELECT COUNT(*) FROM clicks")).isEqualTo(1);
        }
        try (var host = launch(bundle); var client = HttpClient.newHttpClient()) {
            assertThat(send(client, host.port, "/api/v1/urls/" + code, null, false).statusCode()).isEqualTo(200);
            assertThat(query("SELECT COUNT(*) FROM clicks")).isEqualTo(2);
            assertThat(query("SELECT COUNT(*) FROM urls")).isEqualTo(1);
        }
    }

    @Test void pricingEngine_extractedApplication_callsDependenciesAndPublishesAnalytics() throws Exception {
        var bundle = extract("pricing-engine", "terra-pricing-engine");
        try (var host = launch(bundle); var client = HttpClient.newHttpClient()) {
            var priced = send(client, host.port, "/api/v1/pricing/calculate", "{\"productId\":\"WIDGET-J\",\"quantity\":5,\"regionCode\":\"US-CA\",\"couponCode\":\"SAVE10\"}", false);
            assertThat(priced.statusCode()).as(priced.body()).isEqualTo(200);
            assertThat(query("SELECT COUNT(*) FROM high_value_orders")).isEqualTo(1);
            assertThat(query("SELECT total_cents FROM high_value_orders")).isPositive();
        }
    }

    @Test void stepComposition_originalSteps_persistOrderAndReceiveTransitiveSubscription() throws Exception {
        var bundle = extract("step-composition", "terra-step-composition");
        var applicationDirectory = bundle.resolve("application");
        writeDatabaseConfiguration(applicationDirectory);
        var config = org.pragmatica.config.ConfigurationProvider.configurationProvider(org.pragmatica.config.source.TomlConfigSource.tomlConfigSource(applicationDirectory.resolve("resources.toml")).unwrap());
        var factories = new java.util.ArrayList<org.pragmatica.terra.TerraFactory<?>>();
        java.util.ServiceLoader.load(org.pragmatica.terra.TerraFactory.class).forEach(factories::add);
        record PublisherFactory() implements org.pragmatica.terra.TerraFactory<org.pragmatica.aether.slice.Publisher> {
            public String artifact() { return "test:order-events:1"; }
            public Class<org.pragmatica.aether.slice.Publisher> sliceType() { return org.pragmatica.aether.slice.Publisher.class; }
            public java.util.List<Class<?>> dependencies() { return java.util.List.of(); }
            public org.pragmatica.lang.Promise<org.pragmatica.aether.slice.Publisher> create(org.pragmatica.terra.TerraContext ctx) {
                return ctx.resources().provide(org.pragmatica.aether.slice.Publisher.class, "order-events");
            }
        }
        factories.add(new PublisherFactory());
        var blueprint = org.pragmatica.terra.TerraBlueprint.parse(Files.readString(applicationDirectory.resolve("blueprint.toml")) + "\n[[slices]]\nartifact='test:order-events:1'\n").unwrap();
        var resources = org.pragmatica.aether.resource.SpiResourceProvider.spiResourceProvider((_, _) -> org.pragmatica.lang.utils.Causes.cause("Expected scoped config").result());
        var migrations = org.pragmatica.terra.TerraMigrations.fromDirectory(blueprint, applicationDirectory, config).await(timeSpan(15).seconds()).unwrap();
        var app = org.pragmatica.terra.TerraApplication.start(blueprint, factories, _ -> config, resources.facade(), migrations).await(timeSpan(15).seconds()).unwrap();
        try {
            var order = app.slice(org.pragmatica.aether.example.composition.OrderProcessor.class).unwrap()
                .processOrder(new org.pragmatica.aether.example.composition.OrderProcessor.OrderRequest("customer", "product", 2, "12.50")).await(timeSpan(15).seconds()).unwrap();
            var event = org.pragmatica.aether.example.composition.OrderPlacedEvent.orderPlacedEvent(order.orderId().value(), "customer", org.pragmatica.aether.example.composition.shared.Money.money("12.50").unwrap());
            app.slice(org.pragmatica.aether.slice.Publisher.class).unwrap().publish(event).await(timeSpan(15).seconds()).unwrap();
            assertThat(query("SELECT COUNT(*) FROM orders")).isEqualTo(1);
            assertThat(query("SELECT COUNT(*) FROM order_audit WHERE event_type='ORDER_PLACED'")).isEqualTo(1);
        } finally { app.close().await(timeSpan(15).seconds()).unwrap(); }
    }

    @Test void banking_originalSlices_transferAndInvalidateLocalBalanceCache() throws Exception {
        var bundle = extract("banking-app", "terra-banking-app");
        writeDatabaseConfiguration(bundle.resolve("application"));
        var app = org.pragmatica.terra.launcher.TerraLaunchPlan.load(bundle.resolve("application")).flatMap(org.pragmatica.terra.launcher.TerraLaunchPlan::startApplication).await(timeSpan(15).seconds()).unwrap();
        try {
            var accounts = app.slice(org.pragmatica.aether.example.banking.account.AccountService.class).unwrap();
            var currency = org.pragmatica.aether.example.banking.shared.Currency.USD;
            var from = accounts.openAccount("Sender", "sender@example.test", currency).await(timeSpan(15).seconds()).unwrap();
            var to = accounts.openAccount("Receiver", "receiver@example.test", currency).await(timeSpan(15).seconds()).unwrap();
            assertThat(accounts.getBalance(from.id()).await(timeSpan(15).seconds()).unwrap().available().amount()).isEqualByComparingTo("0");
            accounts.credit(from.id(), org.pragmatica.aether.example.banking.shared.Money.money(new java.math.BigDecimal("100"), currency).unwrap()).await(timeSpan(15).seconds()).unwrap();
            assertThat(accounts.getBalance(from.id()).await(timeSpan(15).seconds()).unwrap().available().amount()).isEqualByComparingTo("100");
            var receipt = app.slice(org.pragmatica.aether.example.banking.transfer.TransferService.class).unwrap()
                .transfer(from.id(), to.id(), org.pragmatica.aether.example.banking.shared.Money.money(new java.math.BigDecimal("10"), currency).unwrap()).await(timeSpan(15).seconds()).unwrap();
            assertThat(receipt.status().name()).isEqualTo("COMPLETED");
            assertThat(accounts.getBalance(from.id()).await(timeSpan(15).seconds()).unwrap().available().amount()).isEqualByComparingTo("90");
            assertThat(accounts.getBalance(to.id()).await(timeSpan(15).seconds()).unwrap().available().amount()).isEqualByComparingTo("10");
        } finally { app.close().await(timeSpan(15).seconds()).unwrap(); }
    }

    @Test void pgShowcase_originalGeneratedPersistence_createsAndReadsOrder() throws Exception {
        var bundle = extract("pg-showcase", "terra-pg-showcase");
        writeDatabaseConfiguration(bundle.resolve("application"));
        var app = org.pragmatica.terra.launcher.TerraLaunchPlan.load(bundle.resolve("application")).flatMap(org.pragmatica.terra.launcher.TerraLaunchPlan::startApplication).await(timeSpan(15).seconds()).unwrap();
        try {
            execute("INSERT INTO users(name,email) VALUES ('Terra','terra@example.test')");
            var orders = app.slice(org.pragmatica.aether.example.pgshowcase.OrderProcessing.class).unwrap();
            var created = orders.placeOrder(new org.pragmatica.aether.example.pgshowcase.OrderProcessing.PlaceOrderRequest("1", "12.50")).await(timeSpan(10).seconds()).unwrap();
            assertThat(created.total()).isEqualByComparingTo("12.50");
            assertThat(orders.getOrder(new org.pragmatica.aether.example.pgshowcase.OrderProcessing.GetOrderRequest(created.orderId())).await(timeSpan(10).seconds()).unwrap().isPresent()).isTrue();
            assertThat(query("SELECT COUNT(*) FROM orders")).isEqualTo(1);
        } finally { app.close().await(timeSpan(10).seconds()).unwrap(); }
    }

    @Test void comprehensivePersistence_twoDatasources_migratesAndQueriesBoth() throws Exception {
        try (var db = DriverManager.getConnection(adminUrl); var sql = db.createStatement()) { sql.execute("CREATE DATABASE " + database + "_analytics"); }
        var bundle = extract("comprehensive-persistence", "terra-comprehensive-persistence");
        var application = bundle.resolve("application");
        writeDatabaseConfiguration(application);
        var config = Files.readString(application.resolve("resources.toml"));
        Files.writeString(application.resolve("resources.toml"), config + config.replace("[database]", "[database.analytics]")
            .replace("[database.pool_config]", "[database.analytics.pool_config]").replace(database, database + "_analytics"));
        var app = org.pragmatica.terra.launcher.TerraLaunchPlan.load(application).flatMap(org.pragmatica.terra.launcher.TerraLaunchPlan::startApplication).await(timeSpan(15).seconds()).unwrap();
        try {
            assertThat(app.migrations()).hasSize(2);
            var orders = app.slice(org.pragmatica.aether.example.comprehensive.OrderSlice.class).unwrap();
            // Original CustomerRow uses Instant for nullable deleted_at; retain its explicit
            // mapping failure instead of silently inventing a value or changing slice sources.
            assertThat(orders.customer(1L).await(timeSpan(10).seconds()).<String>fold(cause -> cause.message(), _ -> "unexpected success"))
                .contains("deleted_at");
            execute("UPDATE products SET tags=ARRAY['terra'] WHERE id=1");
            assertThat(orders.productsWithTag("terra").await(timeSpan(10).seconds()).unwrap()).hasSize(1);
            assertThat(orders.snapshotCount().await(timeSpan(10).seconds()).unwrap()).isZero();
            assertThat(orders.customerOrders("pending").await(timeSpan(10).seconds()).unwrap()).isEmpty();
            assertThat(orders.revenue(true).await(timeSpan(10).seconds()).<String>fold(cause -> cause.message(), _ -> "unexpected success"))
                .contains("total_revenue");
            execute("INSERT INTO orders(customer_id,total) SELECT id,12.50 FROM customers");
            assertThat(orders.revenue(true).await(timeSpan(10).seconds()).unwrap()).hasSize(3);
            assertThat(orders.customerOrders("pending").await(timeSpan(10).seconds()).unwrap()).hasSize(3);
            assertThat(query("SELECT COUNT(*) FROM customers")).isEqualTo(3);
        } finally { app.close().await(timeSpan(10).seconds()).unwrap(); }
    }

    private void execute(String sql) throws Exception {
        try (var db = DriverManager.getConnection(jdbcUrl); var statement = db.createStatement()) { statement.execute(sql); }
    }

    private void writeDatabaseConfiguration(Path application) throws Exception {
        var user = java.util.Arrays.stream(address.getRawQuery().split("&")).filter(v -> v.startsWith("user=")).findFirst().orElseThrow().substring(5);
        Files.writeString(application.resolve("resources.toml"), "[database]\njdbc_url='" + jdbcUrl + "'\nhost='" + address.getHost() + "'\nport=" + address.getPort()
            + "\ndatabase='" + database + "'\nusername='" + user + "'\npassword='test'\nasync_url='postgresql://" + address.getAuthority() + "/" + database
            + "'\n[database.pool_config]\nio_threads=2\n");
    }

    private Path extract(String module, String artifact) throws Exception {
        var target = Path.of("../" + module + "/target").toAbsolutePath();
        Path archive;
        try (var files = Files.list(target)) { archive = files.filter(p -> p.getFileName().toString().endsWith("-terra.zip")).findFirst().orElseThrow(); }
        var bundle = Files.createDirectory(directory.resolve(artifact));
        try (var zip = new ZipFile(archive.toFile())) {
            for (var entry : zip.stream().toList()) {
                var file = bundle.resolve(entry.getName()).normalize();
                assertThat(file.startsWith(bundle)).isTrue();
                Files.createDirectories(file.getParent());
                try (var input = zip.getInputStream(entry)) { Files.copy(input, file); }
            }
        }
        return bundle;
    }

    private Host launch(Path bundle) throws Exception {
        var log = directory.resolve(UUID.randomUUID() + ".log");
        var builder = new ProcessBuilder("sh", bundle.resolve("bin/terra").toString()).directory(directory.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile());
        var user = java.util.Arrays.stream(address.getRawQuery().split("&")).filter(v -> v.startsWith("user=")).findFirst().orElseThrow().substring(5);
        builder.environment().putAll(Map.ofEntries(
            Map.entry("JAVA_HOME", System.getProperty("java.home")), Map.entry("TERRA_HTTP__PORT", "0"),
            Map.entry("TERRA_DATABASE__JDBC_URL", jdbcUrl), Map.entry("TERRA_DATABASE__HOST", address.getHost()),
            Map.entry("TERRA_DATABASE__PORT", Integer.toString(address.getPort())), Map.entry("TERRA_DATABASE__DATABASE", database),
            Map.entry("TERRA_DATABASE__USERNAME", user), Map.entry("TERRA_DATABASE__PASSWORD", "test"),
            Map.entry("TERRA_DATABASE__ASYNC_URL", "postgresql://" + address.getAuthority() + "/" + database),
            Map.entry("TERRA_DATABASE__POOL_CONFIG__IO_THREADS", "2"),
            Map.entry("TERRA_API_KEYS__TEST__KEY", "terra-process-key")));
        var process = builder.start();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (process.isAlive() && System.nanoTime() < deadline) {
            var match = Pattern.compile("Terra ready on port (\\d+)").matcher(Files.readString(log));
            if (match.find()) return new Host(process, Integer.parseInt(match.group(1)), log);
            Thread.sleep(25);
        }
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        throw new AssertionError("Terra failed to start: " + Files.readString(log));
    }

    private long query(String sql) throws Exception {
        try (var db = DriverManager.getConnection(jdbcUrl); var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue(); return rows.getLong(1);
        }
    }

    private static HttpResponse<String> send(HttpClient client, int port, String path, String body, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) request.header("X-API-Key", "terra-process-key");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Host(Process process, int port, Path log) implements AutoCloseable {
        @Override public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); throw new AssertionError("Shutdown timed out: " + Files.readString(log)); }
            assertThat(Files.readString(log)).doesNotContain("Terra shutdown failed");
        }
    }
}
