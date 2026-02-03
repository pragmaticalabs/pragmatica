package org.pragmatica.aether.update;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Policy for cleaning up old version instances after update completion.
 *
 * <p>Determines when and how old version instances are removed after
 * a successful rolling update.
 */
public enum CleanupPolicy {
    /**
     * Remove old version instances immediately upon completion.
     */
    IMMEDIATE(timeSpan(0).nanos()),
    /**
     * Keep old version instances for a grace period (default 5 minutes)
     * before removal. Allows for quick rollback if issues are detected
     * after completion.
     */
    GRACE_PERIOD(timeSpan(5).minutes()),
    /**
     * Do not automatically remove old version instances. Requires
     * explicit manual cleanup via API call.
     */
    MANUAL(timeSpan(Long.MAX_VALUE).nanos());
    private final TimeSpan gracePeriod;
    CleanupPolicy(TimeSpan gracePeriod) {
        this.gracePeriod = gracePeriod;
    }
    /**
     * Returns the grace period before cleanup.
     */
    public TimeSpan gracePeriod() {
        return gracePeriod;
    }
    /**
     * Checks if cleanup should happen immediately.
     */
    public boolean isImmediate() {
        return this == IMMEDIATE;
    }
    /**
     * Checks if cleanup requires manual intervention.
     */
    public boolean isManual() {
        return this == MANUAL;
    }
    /**
     * Creates a custom grace period policy.
     */
    public static CleanupPolicyWithDuration gracePeriod(TimeSpan duration) {
        return new CleanupPolicyWithDuration(GRACE_PERIOD, duration);
    }
    /**
     * Wrapper for GRACE_PERIOD with custom duration.
     */
    public record CleanupPolicyWithDuration(CleanupPolicy policy, TimeSpan duration) {
        public TimeSpan gracePeriod() {
            return duration;
        }
    }
}
