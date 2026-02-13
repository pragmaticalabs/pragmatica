package org.pragmatica.aether.controller;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
/// Immutable sliding window for relative change calculation.
///
///
/// Uses circular buffer approach for O(1) operations.
/// Creates new instance on each {@link #record(double)} call to maintain immutability.
///
///
/// The relative change model compares current value against rolling average:
///
///   - `relativeLoad = currentValue / rollingAverage`
///   - `relativeLoad > 1.5` suggests scale up
///   - `relativeLoad < 0.5` suggests scale down
///
///
///
/// Implementation note: The array is cloned on each {@link #record(double)} call to
/// maintain immutability. This creates GC pressure but is necessary for thread safety
/// and functional semantics. The trade-off is acceptable given the low frequency of
/// metric sampling (typically every 5 seconds).
///
///
/// **WARNING:** Do not use `valuesInternal()` directly - it exposes
/// the mutable backing array which breaks immutability. Always use {@link #values()} for
/// safe read-only access. This is a limitation of records with array fields.
///
/// @param valuesInternal Circular buffer of recorded values (INTERNAL - use {@link #values()} instead)
/// @param head           Next write position in the buffer
/// @param count          Number of values recorded (capped at windowSize)
/// @param sum            Running sum of values in the window
/// @param recordCount    Total records since creation (for periodic sum recalculation)
public record MetricWindow(double[] valuesInternal, int head, int count, double sum, long recordCount) {
    /// Recalculate sum from scratch every N records to prevent floating-point drift.
    private static final int SUM_RECALCULATION_INTERVAL = 100;

    /// Create a new empty window with the specified capacity.
    ///
    /// @param windowSize Maximum number of samples to retain (must be > 0)
    /// @return Result containing empty MetricWindow or validation error
    public static Result<MetricWindow> metricWindow(int windowSize) {
        return windowSize > 0
               ? Result.success(new MetricWindow(new double[windowSize], 0, 0, 0.0, 0))
               : Causes.cause("windowSize must be positive, got: " + windowSize).result();
    }

    /// Returns a defensive copy of the internal values array.
    /// This prevents external modification of the internal state.
    ///
    /// @return Copy of the values array
    public double[] values() {
        return valuesInternal.clone();
    }

    /// Check if the window has collected enough samples to be considered full.
    /// Scaling decisions should only be made when windows are full.
    ///
    /// @return true if window is at capacity
    public boolean isFull() {
        return count == valuesInternal.length;
    }

    /// Calculate the average of values in the window.
    ///
    /// @return Average value, or 0.0 if empty
    public double average() {
        return count > 0
               ? sum / count
               : 0.0;
    }

    /// Get the most recently recorded value.
    ///
    /// @return The last recorded value, or 0.0 if empty
    public double lastValue() {
        if (count == 0) {
            return 0.0;
        }
        // head points to next write position, so last value is at (head - 1)
        var lastIndex = (head - 1 + valuesInternal.length) % valuesInternal.length;
        return valuesInternal[lastIndex];
    }

    /// Calculate the relative change of a current value compared to the rolling average.
    ///
    ///
    /// This is the core of the relative change detection model:
    ///
    ///   - Result > 1.0 means current is above average
    ///   - Result < 1.0 means current is below average
    ///   - Result = 1.0 means current equals average
    ///
    ///
    /// @param current Current metric value
    /// @return Relative change ratio (current / average), or 1.0 if average is 0
    public double relativeChange(double current) {
        var avg = average();
        return avg > 0.0
               ? current / avg
               : 1.0;
    }

    /// Record a new value, returning a new window with updated state.
    ///
    ///
    /// If window is full, the oldest value is evicted (circular buffer).
    ///
    ///
    /// Periodically recalculates the sum from scratch to prevent floating-point
    /// drift that can accumulate over many operations.
    ///
    /// @param value New value to record
    /// @return New MetricWindow with the value recorded
    public MetricWindow record(double value) {
        var newValues = valuesInternal.clone();
        var windowSize = newValues.length;
        var newRecordCount = recordCount + 1;
        // Calculate new sum: add new value, subtract evicted value if full
        var newSum = sum + value;
        if (count >= windowSize) {
            newSum -= newValues[head];
        }
        // Write new value at head position
        newValues[head] = value;
        // Advance head (circular)
        var newHead = (head + 1) % windowSize;
        // Update count (capped at window size)
        var newCount = Math.min(count + 1, windowSize);
        // Periodically recalculate sum from scratch to prevent floating-point drift
        if (newRecordCount % SUM_RECALCULATION_INTERVAL == 0) {
            newSum = recalculateSum(newValues, newCount);
        }
        return new MetricWindow(newValues, newHead, newCount, newSum, newRecordCount);
    }

    /// Recalculate sum from array values to correct floating-point drift.
    private static double recalculateSum(double[] values, int count) {
        var sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += values[i];
        }
        return sum;
    }

    /// Get the window size (capacity).
    ///
    /// @return Maximum number of samples this window can hold
    public int windowSize() {
        return valuesInternal.length;
    }
}
