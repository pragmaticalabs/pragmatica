package org.pragmatica.aether.controller;
/// Metrics used for scaling decisions in the Lizard Brain scaling algorithm.
///
///
/// The composite load factor combines these metrics with configurable weights
/// to make scaling decisions based on relative change detection.
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
