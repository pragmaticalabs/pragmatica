package org.pragmatica.aether.update;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Policy for cleaning up old version instances after update completion.
///
///
/// Determines when and how old version instances are removed after
/// a successful rolling update.
public enum CleanupPolicy {
    IMMEDIATE(timeSpan(0).nanos()),
    GRACE_PERIOD(timeSpan(5).minutes()),
    MANUAL(timeSpan(Long.MAX_VALUE).nanos());
    private final TimeSpan gracePeriod;
    CleanupPolicy(TimeSpan gracePeriod) {
        this.gracePeriod = gracePeriod;
    }
    public TimeSpan gracePeriod() {
        return gracePeriod;
    }
    public boolean isImmediate() {
        return this == IMMEDIATE;
    }
    public boolean isManual() {
        return this == MANUAL;
    }
    public static CleanupPolicyWithDuration gracePeriod(TimeSpan duration) {
        return new CleanupPolicyWithDuration(GRACE_PERIOD, duration);
    }
    public record CleanupPolicyWithDuration(CleanupPolicy policy, TimeSpan duration) {
        public TimeSpan gracePeriod() {
            return duration;
        }
    }
}
