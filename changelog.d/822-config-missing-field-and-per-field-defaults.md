### Fixed (2026-10-04 — #822: a missing required field was reported as a missing section)
- **A required field missing from a nested record that IS present now fails naming the field.** The nested bind's
  `SectionNotFound("Record.field")` was read by the enclosing record as "this component's section is absent", so
  an operator who wrote `[notification.smtp_config]` without `host` was told `Config section not found:
  NotificationConfig.smtpConfig`. The binder now reports `ConfigError.MissingField` (`Required config field
  'notification.smtp_config.host' is missing (SmtpConfig.host has no default)`) for every record, `Option`-wrapped or
  not. `SectionNotFound` keeps meaning absence: an absent section is still `SectionNotFound`, an absent
  `Option<Record>` is still `none()`, and a record's `DEFAULT` instance still satisfies absence.
  **Behaviour change for callers:** a record with no `DEFAULT` and a required field omitted from a present
  section used to fail with `SectionNotFound`; it now fails with `MissingField`.
- **Per-field defaults.** The binder now also reads a public static final `DEFAULT_<COMPONENT>` constant of the
  component's type when the record has no `DEFAULT` instance. `SmtpConfig` exposes `DEFAULT_PORT` (587),
  `DEFAULT_TLS_MODE` (STARTTLS), `DEFAULT_CONNECT_TIMEOUT` (10s) and `DEFAULT_COMMAND_TIMEOUT` (30s), so
  `[notification.smtp_config]` needs only `host`; it deliberately has no whole-record `DEFAULT`, which would also
  supply a host. `MetricsConfig` (`record_timing`, `record_counts`) and `RateGuardConfig` (`requests_per_second`,
  `burst`, `type`) get the same, delivering the defaults the resource reference already promised.
- **A scalar where a table is expected is now an error.** `hasSection` answers true for a path that exists only as a
  scalar key, so the binder used to try to bind a record there and fail on its first required field; treating the
  scalar as absent instead would have silently bound the record's default (or `none()`) and dropped what the operator
  wrote (`json = "snake_case"` where `HttpClientConfig` expects a `[http.x.json]` table). A nested record or
  `Option<Record>` now binds only from a real section, and a scalar at that position is a
  `ConfigError.TypeMismatch` naming the key and saying a table was expected (the value is not echoed). Callers that
  needed only a stream's name no longer bind the whole record: `NodeDeploymentState.resolveStreamName` takes the
  name from the section's last segment (`StreamConfigParser` accepts no `name` key), so the documented
  `[streams.X] retention = "time"` shorthand, which that parser interprets, keeps working and a declarative stream
  consumer still registers.
- **Behaviour change (disclosed):** a PARTIAL nested section under an outer record that has a whole-record `DEFAULT`
  (e.g. `[streams.x.retention]` with only `max_count`) used to be silently replaced by the outer `DEFAULT` (the
  operator's `max_count = 5` was dropped and 100000 bound); it now fails naming the missing field. Undocumented shape.

