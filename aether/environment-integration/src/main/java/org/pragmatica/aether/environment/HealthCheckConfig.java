package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Health check configuration for load balancer targets.
public record HealthCheckConfig(String protocol,
                                int port,
                                String path,
                                TimeSpan interval,
                                TimeSpan timeout,
                                int healthyThreshold,
                                int unhealthyThreshold) {
    @SuppressWarnings("JBCT-VO-02")
    public static final HealthCheckConfig DEFAULT = new HealthCheckConfig("http",
                                                                          8080,
                                                                          "/health/ready",
                                                                          timeSpan(10).seconds(),
                                                                          timeSpan(5).seconds(),
                                                                          3,
                                                                          3);

    public static Result<HealthCheckConfig> healthCheckConfig(String protocol,
                                                              int port,
                                                              String path,
                                                              TimeSpan interval,
                                                              TimeSpan timeout,
                                                              int healthyThreshold,
                                                              int unhealthyThreshold) {
        return success(new HealthCheckConfig(protocol,
                                             port,
                                             path,
                                             interval,
                                             timeout,
                                             healthyThreshold,
                                             unhealthyThreshold));
    }
}
