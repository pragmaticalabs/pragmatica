package org.pragmatica.aether.slice.annotation;


/// Marker interface for configuration section resources.
///
/// Used as the `type` parameter in @ResourceQualifier to indicate
/// that this qualifier provides typed configuration from TOML sections.
///
/// Unlike regular resource qualifiers that provision infrastructure (e.g., database
/// connectors), configuration section qualifiers trigger compile-time generation
/// of type-safe config parsers that read values from the merged config hierarchy.
///
/// Example defining a config qualifier:
/// ```{@code
/// @ResourceQualifier(type = ConfigurationSection.class, config = "app.myservice")
/// @Retention(RetentionPolicy.RUNTIME)
/// @Target(ElementType.PARAMETER)
/// public @interface MyServiceConfig {}
/// }```
///
/// Example config record with factory method:
/// ```{@code
/// public record ServiceConfig(String host, int port, boolean enableTls) {
///     public static Result<ServiceConfig> serviceConfig(String host, int port, boolean enableTls) {
///         return Result.success(new ServiceConfig(host, port, enableTls));
///     }
/// }
/// }```
///
/// The annotation processor detects `ConfigurationSection` as the resource type
/// and generates code that reads individual fields from the TOML config section:
/// ```{@code
/// Result.all(
///     ctx.config().requireString("app.myservice", "host"),
///     ctx.config().requireInt("app.myservice", "port"),
///     ctx.config().requireBoolean("app.myservice", "enable_tls")
/// ).flatMap(ServiceConfig::serviceConfig).async()
/// }```
///
/// Configuration is merged from 3 sources (lowest to highest priority):
///   1. Bundled META-INF/config.toml (slice defaults)
///   2. aether.toml [app.*] sections (environment config)
///   3. KV-Store entries (runtime overrides)
public interface ConfigurationSection {}
