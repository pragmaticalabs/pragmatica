package org.pragmatica.aether.resource;
/// Configuration for a scheduled task, loaded from slice TOML.
///
/// Example configuration:
/// ```toml
/// [scheduling.cleanup]
/// interval = "5m"
/// leaderOnly = true
/// ```
///
/// @param interval fixed-rate interval (TimeSpan format); null if cron mode
/// @param cron standard 5-field cron expression; null if interval mode
/// @param leaderOnly whether only the leader node triggers this task (default: true)
public record ScheduleConfig(String interval, String cron, boolean leaderOnly) {
    public ScheduleConfig {
        if (interval == null) interval = "";
        if (cron == null) cron = "";
    }
}
