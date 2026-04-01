package org.pragmatica.aether.controller;
public enum ScalingMetric {
    /// CPU usage (0.0 to 1.0).
    /// High CPU indicates compute-bound load.
    CPU,
    /// Number of currently active invocations.
    /// High active invocations indicate concurrent load pressure.
    ACTIVE_INVOCATIONS,
    /// 95th percentile latency in milliseconds.
    /// High latency indicates processing delays.
    P95_LATENCY,
    /// Error rate (0.0 to 1.0).
    /// High error rate blocks scale-up as a guard rail.
    ERROR_RATE
}
