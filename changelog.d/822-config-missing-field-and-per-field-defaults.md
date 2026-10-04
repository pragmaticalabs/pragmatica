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
