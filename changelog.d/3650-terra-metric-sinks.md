### Added (2026-11-24 — #3650 part 1: Terra metric sinks)
- **A Terra application now carries a `MeterRegistry`**, so `@Metrics` interceptors provision on Terra (before, every
  `[metrics.*]` interceptor failed with "Context does not contain MeterRegistry"). `TerraApplication.metrics()` exposes
  it; a registry the caller put in the provisioning context is kept, never replaced
  [verified: terra/examples/src/test/java/org/pragmatica/terra/example/TerraMeteredSliceTest.java,
  terra/runtime/src/test/java/org/pragmatica/terra/TerraApplicationTest.java].
- **Prometheus and OTLP sinks**, each off unless configured in terra.toml's `management` section (Spring Boot names):
  `management.prometheus.metrics.export.enabled` builds the registry `TerraMetrics.prometheusScrape()` renders (text
  format 0.0.4); `management.otlp.metrics.export.url` (with `step`, default 1m, and
  `management.opentelemetry.resource-attributes.<key>`) pushes to an OpenTelemetry collector. A setting under either
  export prefix that Terra does not implement, or an OTLP step without a url, fails at plan load instead of being
  ignored [verified: terra/runtime/src/test/java/org/pragmatica/terra/TerraMetricsTest.java,
  terra/launcher/src/test/java/org/pragmatica/terra/launcher/TerraConfigurationTest.java].
- The sinks are built after the graph resolves and close with the application, also when a start is refused
  [verified: `TerraApplicationTest#start_failedConstruction_closesTheMetricSinks`]. Serving `/actuator/prometheus` is
  #3651; traces (part 2) wait on #3652's cell seams.
