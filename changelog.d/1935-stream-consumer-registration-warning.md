### Fixed (2026-10-04 — #1935: an unregistrable declared stream consumer was only logged)
- **When a slice's declarative stream consumer cannot be registered, the node now raises a CRITICAL operator warning
  `stream-consumer-not-registered`** (an `OPERATOR_WARNING` cluster event) naming the slice, method, stream section and
  cause, in addition to the ERROR log line it always wrote. Before, the slice activated, reported healthy, and the
  consumer received nothing indefinitely with no event and no status signal. The event is throttled per
  (code, subject) like every operator warning.
- **The slice still activates** (a choice): one unregistered consumer should not take down the slice's other entry
  points, the cause is usually configuration an operator can correct, and failing activation would put the slice into a
  retry loop for a condition a retry cannot fix. There is no slice-status field for an activated-with-caveat
  condition, so none was added; the event is the signal. `NodeDeploymentManager` gains `setOperatorWarningSink`, bound
  by the node to its own sink.
- **Raised on publish only, never on removal.** Deactivation and undeploy re-read the same manifest; an unresolvable
  consumer there is logged at DEBUG, so undeploying an affected slice does not raise a false CRITICAL.
- **The resolved counterpart `stream-consumer-registered-again` (WARNING)** is raised when a consumer previously raised as
  not registered registers, once, for that same subject only; removing the slice clears the memory silently.
